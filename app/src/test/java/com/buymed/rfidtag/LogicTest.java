package com.buymed.rfidtag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Map;

/** Plain-JVM tests for everything that does not need a device. Run with ./gradlew test. */
public class LogicTest {

    // ------------------------------------------------------------ QR parsing

    private static QrParser.Parsed code(String in, String expected) {
        QrParser.Parsed p = QrParser.parse(in);
        assertEquals(in, QrParser.QR_CODE, p.kind);
        assertEquals(in, expected, p.code);
        return p;
    }

    @Test public void parsesTheThreeDocumentedFormats() {
        code("https://qr.v2.circa.vn/item/1757390400000MC011", "1757390400000MC011");
        code("https://thuocsi.vn/qr/1757390400000MC011", "1757390400000MC011");
        code("1757390400000MC011", "1757390400000MC011");
        code("thuocsi.vn/qr/1787548411337B731", "1787548411337B731");
    }

    @Test public void stripsQueryFragmentSlashAndScannerNoise() {
        code("https://thuocsi.vn/qr/1757390400000MC011/", "1757390400000MC011");
        code("https://thuocsi.vn/qr/1757390400000MC011?utm=x#top", "1757390400000MC011");
        code("  thuocsi.vn/qr/1787548411337B731\r\n", "1787548411337B731");
        code("]Q1thuocsi.vn/qr/1787548411337B731", "1787548411337B731");
        code("﻿1787548411337B731\t", "1787548411337B731");
    }

    @Test public void readsProductIdsDirectly() {
        assertEquals(1101363, QrParser.parse("1101363").productId);
        assertEquals(QrParser.PRODUCT_ID, QrParser.parse(" 1109 \n").kind);
        assertEquals(1109, QrParser.parse("https://x.vn/p?productId=1109&lot=A").productId);
        assertEquals(1109, QrParser.parse("https://x.vn/p?product_id=1109").productId);
        assertEquals(1109, QrParser.parse("{\"productId\": 1109, \"lot\": \"L1\"}").productId);
        QrParser.Parsed withP = QrParser.parse("https://thuocsi.vn/qr/1787548411337B731?p=1101363");
        assertEquals(QrParser.PRODUCT_ID, withP.kind);
        assertEquals(1101363, withP.productId);
        assertEquals(QrParser.QR_CODE, QrParser.parse("https://thuocsi.vn/qr/1787548411337B731?utm=1&page=2").kind);
        // 13-digit numbers are Buymed codes / EANs, not product ids
        assertEquals(QrParser.QR_CODE, QrParser.parse("8934567890123").kind);
    }

    @Test public void recognisesConfigAndEmpty() {
        QrParser.Parsed p = QrParser.parse("RFIDCFG:{\"customerId\":1}");
        assertEquals(QrParser.CONFIG, p.kind);
        assertEquals("{\"customerId\":1}", p.code);
        assertEquals(QrParser.EMPTY, QrParser.parse("  \r\n").kind);
        assertEquals(QrParser.EMPTY, QrParser.parse("///").kind);
    }

    @Test public void splitsBatchParts() {
        assertEquals("1787548411337", QrParser.batchTimestamp("1787548411337B731"));
        assertEquals("B731", QrParser.afterTimestamp("1787548411337B731"));
        assertNull(QrParser.batchTimestamp("ABC123"));
        assertNull(QrParser.batchTimestamp("1101363"));
    }

    // ------------------------------------------------------------ storage format

    @Test public void storeRoundTrips() throws Exception {
        Map<Long, Product> in = new java.util.LinkedHashMap<Long, Product>();
        in.put(1107L, new Product(1107, "temthuong", "hộp", "Decontractyl 250mg sanofi (h/60v)"));
        in.put(1L, new Product(1, null, null, "Phụ phí\twith tab"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ProductStore.write(in.values(), out);
        Map<Long, Product> back = ProductStore.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(2, back.size());
        assertEquals("temthuong", back.get(1107L).typeTag);
        assertEquals("hộp", back.get(1107L).rfidUnit);
        assertNull(back.get(1L).typeTag);
        assertEquals("Phụ phí with tab", back.get(1L).name);
    }

    // ------------------------------------------------------------ CSV / TSV import

    private static TableImporter.Result csv(String text) throws Exception {
        return TableImporter.readDelimited(new ByteArrayInputStream(text.getBytes("UTF-8")), null);
    }

    @Test public void importsCsvWithQuotesAndAliases() throws Exception {
        TableImporter.Result r = csv("﻿Product ID,Product Name,Type Tag,type_tag_label,RFID Unit Name\n"
                + "1107,\"Decontractyl, 250mg \"\"sanofi\"\"\",temthuong,Tem thường,hộp\n"
                + "1108,Diclofenac,,Tem gập,hộp\n"
                + "1,Phụ phí,,Chưa cấu hình,\n"
                + "1107,dup,temgap,,\n"
                + ",,,,\n"
                + "abc,bad,,,\n");
        assertEquals(3, r.products.size());
        assertEquals("Decontractyl, 250mg \"sanofi\"", r.products.get(1107L).name);
        assertEquals("temthuong", r.products.get(1107L).typeTag);
        assertEquals("temgap", r.products.get(1108L).typeTag);   // from label
        assertNull(r.products.get(1L).typeTag);
        assertEquals(1, r.temThuong);
        assertEquals(1, r.temGap);
        assertEquals(1, r.unconfigured);
        assertEquals(2, r.skipped);                                 // duplicate + bad id
    }

    @Test public void importsSemicolonAndHeaderlessTsv() throws Exception {
        TableImporter.Result r = csv("product_id;rfidTagType\n1109;TEMGAP\n");
        assertEquals("temgap", r.products.get(1109L).typeTag);
        r = csv("1107\ttemthuong\thộp\tDecontractyl\n1108\t\t\tX\n");
        assertEquals(2, r.products.size());
        assertEquals("hộp", r.products.get(1107L).rfidUnit);
    }

    // ------------------------------------------------------------ XLSX import

    private static TableImporter.PullParserFactory kxml(final boolean namespaces) {
        return new TableImporter.PullParserFactory() {
            @Override public XmlPullParser newParser() throws Exception {
                KXmlParser p = new KXmlParser();
                p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, namespaces);
                return p;
            }
        };
    }

    /** Runs against the real export when -Drfid.xlsx=/path/to/file.xlsx is given. */
    @Test public void importsTheRealExcelExport() throws Exception {
        String path = System.getProperty("rfid.xlsx");
        Assume.assumeTrue(path != null && new File(path).exists());
        // loai_tem_rfid_global_san_pham.xlsx (15/11/2026), sheet global_rfid_tag_type
        for (boolean ns : new boolean[]{false, true}) {
            TableImporter.Result r = TableImporter.readXlsx(new File(path), kxml(ns), null);
            assertEquals(72394, r.products.size());
            assertEquals(42709, r.temThuong);
            assertEquals(21151, r.temGap);
            assertEquals(8534, r.unconfigured);
            Product p = r.products.get(1101363L);
            assertEquals("temthuong", p.typeTag);
            assertEquals("hộp", p.rfidUnit);
            assertEquals("Kẹo giải rượu Party Smart Himalaya (H/2v)", p.name);
            assertEquals("temgap", r.products.get(1109L).typeTag);
            assertEquals("temthuong", r.products.get(1467L).typeTag);
            assertEquals("hộp", r.products.get(1467L).rfidUnit);
            assertFalse(r.products.containsKey(0L));
            if (!ns) assertSameAsBundledAsset(r.products);
        }
    }

    /** The APK's assets/products.dat must be exactly what the app itself reads from the export. */
    private static void assertSameAsBundledAsset(Map<Long, Product> fromXlsx) throws Exception {
        File asset = new File("src/main/assets/products.dat");
        if (!asset.exists()) asset = new File("app/src/main/assets/products.dat");
        Assume.assumeTrue(asset.exists());
        java.io.FileInputStream in = new java.io.FileInputStream(asset);
        Map<Long, Product> bundled;
        try {
            bundled = ProductStore.read(in);
        } finally {
            in.close();
        }
        assertEquals(fromXlsx.size(), bundled.size());
        for (Product x : fromXlsx.values()) {
            Product b = bundled.get(x.id);
            assertTrue("missing " + x.id, b != null);
            assertEquals("type " + x.id, x.typeTag, b.typeTag);
            assertEquals("unit " + x.id, x.rfidUnit, b.rfidUnit);
            // tools/xlsx_to_tsv.py collapses all whitespace (incl. U+00A0, in 7 names); the importer keeps it.
            assertEquals("name " + x.id, spaces(x.name), spaces(b.name));
        }
    }

    private static String spaces(String s) {
        return s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    @Test public void columnLetters() {
        assertEquals(0, TableImporter.columnIndex("A1", 9));
        assertEquals(25, TableImporter.columnIndex("Z10", 9));
        assertEquals(26, TableImporter.columnIndex("AA3", 9));
        assertEquals(9, TableImporter.columnIndex(null, 9));
    }

    // ------------------------------------------------------------ API response parsing

    @Test public void parsesTheCheckResponse() throws Exception {
        String body = "{\"status\":\"OK\",\"message\":\"Thành công.\",\"data\":[{\"canGenerate\":true,"
                + "\"machineCode\":\"B7\",\"productId\":1101363,\"productName\":\"Kẹo giải rượu Party Smart Himalaya (H/2v)\","
                + "\"rfidTagType\":\"temthuong\",\"rfidUnit\":\"hộp\",\"isNotFound\":false,\"warnings\":null}]}";
        ApiClient.Response r = new ApiClient.Response();
        r.http = 200;
        ApiClient.parse(body, r);
        assertTrue(r.ok);
        assertEquals(1101363, r.productId);
        assertEquals("temthuong", r.rfidTagType);
        assertEquals("B7", r.machineCode);

        ApiClient.Response e = new ApiClient.Response();
        e.http = 400;
        ApiClient.parse("{\"status\":\"INVALID\",\"message\":\"Không đọc được QR\",\"errorCode\":\"INVALID_QR_CODE\"}", e);
        assertFalse(e.ok);
        assertEquals("INVALID_QR_CODE", e.errorCode);
    }

    // ------------------------------------------------------------ Circa POS API parsing
    // Shapes copied from real api.v2.circa.vn responses (15/11/2026), trimmed; store ids are fake.

    @Test public void parsesCircaStoresSortedByName() throws Exception {
        String body = "{\"success\":true,\"data\":{\"principal_id\":\"p\",\"clients\":[{\"client_id\":1,\"name\":\"CIRCA\","
                + "\"stores\":["
                + "{\"id\":\"s-2\",\"code\":\"POS0084\",\"name\":\"CIRCA KHO BẮC NINH\",\"capabilities\":[]},"
                + "{\"id\":\"s-1\",\"code\":\"POS0009\",\"name\":\"CIRCA CENTRAL\",\"capabilities\":[\"inventory.view\"]},"
                + "{\"id\":\"s-3\",\"code\":\"POS0010\",\"name\":\"KHO TỔNG HUB LÝ THƯỜNG KIỆT\"}]}]}}";
        java.util.List<CircaClient.Store> stores = CircaClient.parseStores(body);
        assertEquals(3, stores.size());
        assertEquals("CIRCA CENTRAL", stores.get(0).name);
        assertEquals("CIRCA KHO BẮC NINH", stores.get(1).name);
        assertEquals("s-2", stores.get(1).id);
        assertEquals(1, stores.get(1).clientId);
        assertEquals("CIRCA KHO BẮC NINH (POS0084)", stores.get(1).label());
    }

    @Test public void parsesCircaQrLookup() throws Exception {
        String body = "{\"success\":true,\"data\":{\"product_id\":1467,\"product_name\":\"Vita c glucose mekophar (lốc/46 túi/24 viên)\","
                + "\"sku\":\"CIRCA.MEK-VIT-001\",\"lot\":\"25055KN\",\"vat\":5,\"expired_date\":\"23-08-2027\",\"unit_name\":\"túi\","
                + "\"lot_detail\":null,\"retail_unit_detail\":{\"unit\":\"túi\",\"convert_rate\":24,\"is_rfid_unit\":false},"
                + "\"image_urls\":[\"https://cdn-gcs.thuocsi.vn/thuocsi-live/images/a\",\"\",\"http://insecure/b\"]}}";
        CircaClient.QrInfo q = CircaClient.parseQr(body);
        assertEquals(1467, q.productId);
        assertEquals("CIRCA.MEK-VIT-001", q.sku);
        assertEquals("25055KN", q.lot);
        assertEquals("23-08-2027", q.expiry);
        assertEquals("túi", q.unit);
        // only https image urls are kept
        assertEquals(1, q.images.size());

        CircaClient.QrInfo none = CircaClient.parseQr("{\"success\":true,\"data\":{\"product_id\":0,\"lot_detail\":null}}");
        assertEquals(-1, none.productId);
        assertNull(none.lot);
    }

    @Test public void parsesCircaStockAndIgnoresOtherProducts() throws Exception {
        String body = "{\"success\":true,\"data\":{\"list\":["
                + "{\"sku\":\"CIRCA.MEK-VIT-001\",\"product_id\":1467,\"product_name\":\"Vita C\",\"base_unit\":\"viên\","
                + "\"on_hand_qty\":3312,\"available_qty\":0,\"seller_code\":\"CIRCA\",\"items\":[{\"location_id\":\"l1\","
                + "\"location_code\":\"WH-INBOUND\",\"location_name\":\"Kho Inbound\",\"on_hand_qty\":3312,\"details\":["
                + "{\"lot_date\":\"25074KN-181027\",\"on_hand_qty\":1104,\"doc_code\":\"PO00060467\",\"available_qty\":1104},"
                + "{\"lot_date\":\"25048KN-140727\",\"on_hand_qty\":2208,\"doc_code\":\"PO00044608\",\"available_qty\":2208}]}]},"
                + "{\"sku\":\"CIRCA.HXYV84FW\",\"product_id\":108439,\"base_unit\":\"viên\",\"on_hand_qty\":180,\"items\":[]}"
                + "],\"total\":2,\"limit\":50,\"page\":1}}";
        CircaClient.Stock s = CircaClient.parseStock(body, 1467);
        assertEquals(1, s.skus.size());
        assertEquals(3312, s.onHand, 0);
        assertEquals("viên", s.baseUnit);
        CircaClient.Location loc = s.skus.get(0).locations.get(0);
        assertEquals("WH-INBOUND", loc.code);
        assertEquals(2, loc.lots.size());
        assertEquals("PO00044608", loc.lots.get(1).doc);

        CircaClient.Stock empty = CircaClient.parseStock("{\"success\":true,\"data\":{\"list\":[],\"total\":0}}", 14);
        assertTrue(empty.skus.isEmpty());
        assertEquals(0, empty.onHand, 0);
    }

    @Test public void convertsStockToTheRfidUnit() throws Exception {
        String detailBody = "{\"success\":true,\"data\":{\"product_id\":1467,\"name\":\"Vita C Glucose Mekophar (Hộp/46 túi/24 viên)\","
                + "\"volumes\":\"Hộp 46 túi x 24 viên ngậm\",\"code\":\"MEK-VIT-001\",\"registration_number\":\"893100929424\","
                + "\"image_urls\":[\"https://cdn-gcs.thuocsi.vn/thuocsi-live/images/x\"],\"skus\":[],\"retail_units\":["
                + "{\"unit\":\"hộp\",\"base_unit\":false,\"convert_rate\":1104,\"is_rfid_unit\":true},"
                + "{\"unit\":\"túi\",\"base_unit\":false,\"convert_rate\":24,\"is_rfid_unit\":false},"
                + "{\"unit\":\"viên\",\"base_unit\":true,\"convert_rate\":1,\"is_rfid_unit\":false}]}}";
        CircaClient.Detail d = CircaClient.parseDetail(detailBody);
        assertEquals("Hộp 46 túi x 24 viên ngậm", d.volumes);
        assertEquals("hộp", d.rfidUnit().name);
        assertEquals(24, d.unit("TÚI").rate, 0);
        assertEquals(1, d.images.size());

        CircaClient.Stock s = new CircaClient.Stock();
        s.baseUnit = "viên";
        s.onHand = 3312;
        assertEquals("3.312 viên = 3 hộp", CircaClient.stockLine(s, d, "hộp"));
        assertEquals("3.312 viên = 138 túi", CircaClient.stockLine(s, d, "túi"));
        s.onHand = 1656;
        assertEquals("1.656 viên ≈ 1,5 hộp", CircaClient.stockLine(s, d, "hộp"));
        // no details yet / unit unknown: base unit only
        assertEquals("1.656 viên", CircaClient.stockLine(s, null, "hộp"));
        assertEquals("1.656 viên ≈ 1,5 hộp", CircaClient.stockLine(s, d, "thùng")); // falls back to Circa's RFID unit
        CircaClient.Stock none = new CircaClient.Stock(); // product not in this store: no SKU, no base unit
        assertEquals("0", CircaClient.stockLine(none, d, "hộp"));
        // a few loose units of a big box must not read as "≈ 0 hộp" (= out of stock)
        s.onHand = 30;
        assertEquals("30 viên (< 0,1 hộp)", CircaClient.stockLine(s, d, "hộp"));
        s.onHand = 24;
        assertEquals("24 viên = 1 túi", CircaClient.stockLine(s, d, "túi"));
        // stock counted in the RFID unit itself: nothing to convert
        s.baseUnit = "hộp";
        s.onHand = 5;
        assertEquals("5 hộp", CircaClient.stockLine(s, d, "hộp"));

        assertEquals("0", CircaClient.qty(0));
        assertEquals("1.234.567", CircaClient.qty(1234567));
        assertEquals("1.234,5", CircaClient.qty(1234.5));
        assertEquals("3", CircaClient.qty(2.96));
    }

    @Test public void explainsCircaErrors() {
        CircaClient.Reply<Object> auth = new CircaClient.Reply<Object>();
        CircaClient.describeError(401, "{\"success\":false,\"errors\":[{\"code\":\"invalid_token\","
                + "\"localized_message\":{\"text\":\"Token sai\"},\"message\":\"Token sai\",\"status\":\"UNAUTHENTICATED\"}]}", auth);
        assertFalse(auth.ok);
        assertEquals("invalid_token", auth.errorCode);
        assertTrue(auth.message.contains("Khóa API Circa sai"));

        CircaClient.Reply<Object> unknown = new CircaClient.Reply<Object>();
        CircaClient.describeError(500, "{\"success\":false,\"errors\":[{\"code\":\"UNKNOWN\","
                + "\"message\":\"Hệ thống gặp lỗi, vui lòng thử lại sau\",\"status\":\"UNKNOWN\"}]}", unknown);
        assertEquals("UNKNOWN", unknown.errorCode);
        assertTrue(unknown.message.contains("HTTP 500"));

        CircaClient.Reply<Object> html = new CircaClient.Reply<Object>();
        CircaClient.describeError(502, "<html>Bad gateway</html>", html);
        assertEquals("HTTP_502", html.errorCode);
    }

    @Test public void buildsTheStockQueryWithAnExactProductFilter() {
        String p = CircaClient.stockPath("store-1", 1467);
        assertTrue(p, p.startsWith("/v1/inventory/admin/stock/search?query="));
        // "product_id" is silently ignored by the API; "product_ids" filters exactly.
        assertTrue(p, p.contains("product_ids%3D1467"));
        assertTrue(p, p.contains("pos_id%3Dstore-1"));
        assertEquals("1764138802830C35", CircaClient.enc("1764138802830C35"));
        assertEquals("a%20b%2Fc", CircaClient.enc("a b/c"));
    }

    /** The key ends up in an HTTP header: a pasted newline or non-ASCII char would throw there. */
    @Test public void sanitizesTheCircaKeyAndSettingsQrFields() throws Exception {
        assertEquals("ck_u_abc", Settings.headerSafe("  ck_u_abc\r\n"));
        assertEquals("ck_u_abc", Settings.headerSafe("ck_u_​abc\t"));
        assertEquals("ck_u_bc", Settings.headerSafe("ck_u_ábc")); // non-ASCII dropped, not sent
        assertEquals("", Settings.headerSafe(null));
        assertEquals("Bearer x", Settings.headerSafe("Bearer x"));

        org.json.JSONObject o = new org.json.JSONObject("{\"a\":null,\"b\":\" v \",\"c\":5}");
        assertEquals("", Settings.jsonStr(o, "a"));
        assertEquals("v", Settings.jsonStr(o, "b"));
        assertEquals("5", Settings.jsonStr(o, "c"));
        assertEquals("", Settings.jsonStr(o, "missing"));
    }

    @Test public void namesTheServiceInNetworkErrors() {
        ApiClient.Response slow = new ApiClient.Response();
        ApiClient.describeFailure(new java.net.SocketTimeoutException("timeout"), "api.v2.circa.vn", "Circa", slow);
        assertEquals("TIMEOUT", slow.errorCode);
        assertTrue(slow.message, slow.message.startsWith("Circa không phản hồi kịp"));

        ApiClient.Response old = new ApiClient.Response();
        ApiClient.describeFailure(new java.net.SocketTimeoutException("timeout"), "h", old);
        assertTrue(old.message, old.message.startsWith("API không phản hồi kịp"));
    }

    @Test public void foldsVietnameseForStoreSearch() {
        assertEquals("circa kho bac ninh", StoreActivity.fold("CIRCA KHO BẮC NINH"));
        assertEquals("kho tong hub ly thuong kiet", StoreActivity.fold("KHO TỔNG HUB LÝ THƯỜNG KIỆT"));
        assertEquals("da nang", StoreActivity.fold("Đà Nẵng"));
    }

    /** What the PDA showed with Wi-Fi off: the DNS failure must read as "no internet". */
    @Test public void explainsNetworkFailures() {
        assertEquals("open-apis.buymed.com", ApiClient.hostOf("https://open-apis.buymed.com"));

        ApiClient.Response dns = new ApiClient.Response();
        ApiClient.describeFailure(new java.net.UnknownHostException(
                "Unable to resolve host \"open-apis.buymed.com\": No address associated with hostname"),
                "open-apis.buymed.com", dns);
        assertFalse(dns.ok);
        assertTrue(dns.networkError);
        assertEquals("NO_NETWORK", dns.errorCode);
        assertTrue(dns.message.contains("chưa có Internet"));
        assertTrue(dns.message.contains("open-apis.buymed.com"));

        ApiClient.Response slow = new ApiClient.Response();
        ApiClient.describeFailure(new java.net.SocketTimeoutException("timeout"), "h", slow);
        assertEquals("TIMEOUT", slow.errorCode);

        ApiClient.Response refused = new ApiClient.Response();
        ApiClient.describeFailure(new java.net.ConnectException("refused"), "h", refused);
        assertEquals("CONNECT", refused.errorCode);

        ApiClient.Response tls = new ApiClient.Response();
        ApiClient.describeFailure(new javax.net.ssl.SSLHandshakeException("cert"), "h", tls);
        assertEquals("SSL", tls.errorCode);

        // Failing while reading the body after an HTTP status is not a connectivity problem.
        ApiClient.Response body = new ApiClient.Response();
        body.http = 502;
        ApiClient.describeFailure(new java.io.IOException("reset"), "h", body);
        assertEquals("NETWORK", body.errorCode);
        assertFalse(body.networkError);
    }
}
