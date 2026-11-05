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
        for (boolean ns : new boolean[]{false, true}) {
            TableImporter.Result r = TableImporter.readXlsx(new File(path), kxml(ns), null);
            assertEquals(72225, r.products.size());
            assertEquals(42721, r.temThuong);
            assertEquals(18888, r.temGap);
            assertEquals(10616, r.unconfigured);
            Product p = r.products.get(1101363L);
            assertEquals("temthuong", p.typeTag);
            assertEquals("hộp", p.rfidUnit);
            assertEquals("Kẹo giải rượu Party Smart Himalaya (H/2v)", p.name);
            assertEquals("temgap", r.products.get(1109L).typeTag);
            assertFalse(r.products.containsKey(0L));
        }
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
}
