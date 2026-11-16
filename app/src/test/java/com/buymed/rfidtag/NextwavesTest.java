package com.buymed.rfidtag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Plain-JVM tests for the Nextwaves EPC count (parsing + the ledger algorithm of the NW brief). */
public class NextwavesTest {
    private static final String WH = NextwavesClient.WH_LUMINA;
    private static final String OTHER = NextwavesClient.WH_FLORITA;

    @Test public void matchesCircaStoresToNwWarehousesByName() {
        assertEquals(NextwavesClient.WH_LUMINA, NextwavesClient.warehouseFor("CIRCA LUMINA"));
        assertEquals(NextwavesClient.WH_FLORITA, NextwavesClient.warehouseFor("Circa Florita"));
        assertEquals("", NextwavesClient.warehouseFor("CIRCA KHO BẮC NINH"));
        assertEquals("", NextwavesClient.warehouseFor(null));
        assertEquals("Lumina", NextwavesClient.warehouseLabel(WH));
        assertEquals("Florita", NextwavesClient.warehouseLabel(OTHER.toUpperCase()));
        assertEquals("0a1b2c3d…", NextwavesClient.warehouseLabel("0a1b2c3d-0000-0000-0000-000000000000"));
        assertTrue(NextwavesClient.isUuid(WH));
        assertFalse(NextwavesClient.isUuid("Lumina"));
        assertFalse(NextwavesClient.isUuid(""));
    }

    @Test public void parsesProductAndSummary() throws Exception {
        NextwavesClient.Product p = NextwavesClient.parseProduct("{\"id\":\"45bd0fc9-c0cc-4ac5-8bee-d9b84468845e\","
                + "\"productIdentifier\":\"CIRCA-10871\",\"title\":\"Bao cao su durex\",\"baseUnitOfMeasure\":\"cái\"}");
        assertEquals("45bd0fc9-c0cc-4ac5-8bee-d9b84468845e", p.id);
        assertEquals("CIRCA-10871", p.identifier);
        assertEquals("cái", p.baseUnit);
        // Same object wrapped in {"data": …}
        NextwavesClient.Product w = NextwavesClient.parseProduct("{\"data\":{\"id\":\"x\",\"baseUnitOfMeasure\":\"viên\"}}");
        assertEquals("x", w.id);
        assertEquals("viên", w.baseUnit);

        NextwavesClient.Summary s = NextwavesClient.parseSummary(
                "{\"systemStock\":93,\"remoteStock\":0,\"rfidStock\":93,\"untaggedStock\":0,\"discrepancy\":0}");
        assertEquals(93, s.rfidStock, 1e-9);
        assertEquals(93, s.systemStock, 1e-9);
        NextwavesClient.Summary empty = NextwavesClient.parseSummary("{\"rfidStock\":null}");
        assertEquals(0, empty.rfidStock, 1e-9);
    }

    @Test public void countsTagsFromTheLedger() throws Exception {
        NextwavesClient.Ledger led = new NextwavesClient.Ledger();
        long next = NextwavesClient.parseLedgerPage("{\"data\":["
                // one receipt of 6 over two tags: 3 each
                + "{\"ledgerNumber\":1,\"warehouseId\":\"" + WH + "\",\"quantity\":6,\"epcs\":[\"E1\",\"E2\"]},"
                // E1 sold out
                + "{\"ledgerNumber\":2,\"warehouseId\":\"" + WH.toUpperCase() + "\",\"quantity\":-3,\"epcs\":[\"E1\"]},"
                // another warehouse: ignored
                + "{\"ledgerNumber\":3,\"warehouseId\":\"" + OTHER + "\",\"quantity\":10,\"epcs\":[\"E3\"]},"
                // one tag holding a whole box of 1104, then 2 sold from it: still 1 tag, 1102 left
                + "{\"ledgerNumber\":4,\"warehouseId\":\"" + WH + "\",\"quantity\":1104,\"epcs\":[\"E4\"]},"
                + "{\"ledgerNumber\":5,\"warehouseId\":\"" + WH + "\",\"quantity\":-2,\"epcs\":[\"E4\"]},"
                // rows without EPCs do not count
                + "{\"ledgerNumber\":6,\"warehouseId\":\"" + WH + "\",\"quantity\":50,\"epcs\":[]}"
                + "],\"nextAfter\":6}", WH, led);
        assertEquals(6, next);
        assertEquals(6, led.rows);
        // page 2 (cursor 6): EPCs as objects, then the empty page that ends paging
        next = NextwavesClient.parseLedgerPage("{\"data\":[{\"ledgerNumber\":7,\"warehouseId\":\"" + WH
                + "\",\"quantity\":2,\"epcs\":[{\"epc\":\"E5\"},{\"epc\":\"E6\"}]}],\"nextAfter\":7}", WH, led);
        assertEquals(7, next);
        assertEquals(-1, NextwavesClient.parseLedgerPage("{\"data\":[],\"nextAfter\":7}", WH, led));

        assertEquals(4, led.inStockCount());                 // E2, E4, E5, E6
        assertEquals(3 + 1102 + 1 + 1, led.inStockQuantity(), 1e-9);
        assertEquals(5, led.epcs().size());                  // E1..E6 without E3
        assertFalse(led.epcs().contains("E3"));
    }

    @Test public void ledgerEdgeCases() throws Exception {
        NextwavesClient.Ledger led = new NextwavesClient.Ledger();
        // Without afterLedgerNumber NW answers a bare array: read it, but there is no cursor.
        assertEquals(-1, NextwavesClient.parseLedgerPage("[{\"warehouseId\":\"" + WH
                + "\",\"quantity\":1,\"epcs\":[\"A\",\"B\",\"C\"]}]", WH, led));
        // 1/3 each, rounded to 3 decimals, still > 0
        assertEquals(3, led.inStockCount());
        assertEquals(0.999, led.inStockQuantity(), 1e-9);
        // A balance that only rounds to 0 is not in stock.
        NextwavesClient.Ledger tiny = new NextwavesClient.Ledger();
        NextwavesClient.parseLedgerPage("{\"data\":[{\"warehouseId\":\"" + WH + "\",\"quantity\":1,\"epcs\":[\"T\"]},"
                + "{\"warehouseId\":\"" + WH + "\",\"quantity\":-0.9999,\"epcs\":[\"T\"]}]}", WH, tiny);
        assertEquals(0, tiny.inStockCount());
        assertTrue(NextwavesClient.ledgerPath("p-1", WH, 0).endsWith("&limit=1000&afterLedgerNumber=0"));
    }

    @Test public void readsSingleEpcAnswers() throws Exception {
        double[] a = NextwavesClient.parseEpc("{\"status\":\"active\",\"warehouseId\":\"" + WH
                + "\",\"circaProductId\":\"1467\",\"unit\":\"hộp\",\"baseQuantity\":1104}", WH);
        assertEquals(1, a[0], 0);
        assertEquals(1104, a[1], 1e-9);
        assertEquals(0, NextwavesClient.parseEpc("{\"status\":\"sold\",\"warehouseId\":\"" + WH + "\"}", WH)[0], 0);
        assertEquals(0, NextwavesClient.parseEpc("{\"status\":\"active\",\"warehouseId\":\"" + OTHER + "\"}", WH)[0], 0);
    }

    @Test public void formatsTheTagLine() {
        NextwavesClient.EpcStock s = new NextwavesClient.EpcStock();
        s.found = true;
        s.epcCount = 31;
        s.epcQuantity = 93;
        s.rfidStock = 93;
        s.baseUnit = "cái";
        s.consistent = true;
        assertEquals("31 thẻ · 93 cái", NextwavesClient.line(s));
        s.consistent = false;
        assertEquals("31 thẻ · 93 cái (chưa khớp NW)", NextwavesClient.line(s));
        NextwavesClient.EpcStock zero = new NextwavesClient.EpcStock();
        zero.found = true;
        zero.consistent = true;
        assertEquals("0 thẻ", NextwavesClient.line(zero));
        assertEquals("0 thẻ (sản phẩm chưa có trên NW)", NextwavesClient.line(new NextwavesClient.EpcStock()));
        assertNull(NextwavesClient.line(null));
    }

    @Test public void builtInKeyIsOptional() {
        // CI and fresh clones have no (gitignored) BuiltinKeys class: no key, no crash.
        assertEquals("", Settings.builtinKey("com.buymed.rfidtag.NoSuchKeysClass"));
        assertEquals("", Settings.builtinKey("java.lang.String")); // class without NW_KEY
    }

    @Test public void explainsHttpErrors() {
        CircaClient.Reply<Void> r = new CircaClient.Reply<Void>();
        NextwavesClient.describeError(401, "{\"code\":\"UNAUTHORIZED\",\"message\":\"Invalid API key\"}", r);
        assertFalse(r.ok);
        assertEquals("UNAUTHORIZED", r.errorCode);
        assertTrue(r.message.contains("Khóa Nextwaves sai"));
        NextwavesClient.describeError(500, "{\"error\":{\"code\":\"X\",\"message\":\"boom\"}}", r);
        assertEquals("X", r.errorCode);
        assertEquals("Nextwaves lỗi HTTP 500 — boom", r.message);
        NextwavesClient.describeError(502, "<html>bad gateway</html>", r);
        assertEquals("HTTP_502", r.errorCode);
        NextwavesClient.describeError(429, "", r);
        assertTrue(r.message.contains("giới hạn"));
    }
}
