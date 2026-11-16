package com.buymed.rfidtag;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Read-only calls to the Nextwaves (NW) public API, answering "how many RFID tags (EPCs) of this
 * product are in stock in this NW warehouse". NW has no public endpoint returning that count
 * (/tags ignores its product filter; the per-product tag endpoints only accept user tokens), so
 * it is counted as in the NW brief of 16/11/2026:
 *  1. GET /products/by-external/{circaProductId}               Circa id -> NW product UUID
 *  2. GET /stock/summary?productId=&warehouseId=                rfidStock = base units on tags
 *  3. GET /stock/ledger?productId=&warehouseId=&limit=1000&afterLedgerNumber=N   (paged)
 *     each row's quantity split evenly over its EPCs; an EPC with balance > 0 is in stock
 *  4. GET /integrations/circa/epcs/{epc}  per EPC, only when 3 does not add up to 2
 * Parsing is split from transport so it can be unit-tested on the JVM.
 */
public final class NextwavesClient {
    public static final String BASE_URL = "https://api-circa.nextwaves.app/api/v1/public";
    /** NW warehouses known so far; a Circa store is matched to one by name (see warehouseFor). */
    static final String WH_LUMINA = "3f7bd541-7370-4ce0-9048-231cf061e91a";
    static final String WH_FLORITA = "eb5e2b4f-6f92-413b-90ef-6be1730b94c0";
    /** Step 4 costs one call per EPC ever seen in the ledger; above this it is skipped on a PDA. */
    static final int MAX_VERIFY = 200;
    /** 1000 ledger rows per page; a cap so a server that never ends paging cannot loop forever. */
    static final int MAX_LEDGER_PAGES = 50;
    /** NW answers 429 when called in bursts: back off 1 s, 2 s, 3 s… (brief: at most 6 tries). */
    private static final int MAX_ATTEMPTS = 6;
    private static final double EPS = 0.01;

    private NextwavesClient() {}

    // ------------------------------------------------------------------ models

    public static final class Product {
        public String id;
        public String identifier;
        public String title;
        public String baseUnit;
    }

    public static final class Summary {
        public double systemStock;
        public double rfidStock;
        public double untaggedStock;
    }

    /** Balance per EPC from the ledger of one product in one warehouse. */
    public static final class Ledger {
        final LinkedHashMap<String, Double> balance = new LinkedHashMap<String, Double>();
        int rows;
        boolean truncated;

        /** EPCs whose balance, rounded to 3 decimals, is above 0. */
        int inStockCount() {
            int n = 0;
            for (Double v : balance.values()) if (round3(v) > 0) n++;
            return n;
        }

        double inStockQuantity() {
            double q = 0;
            for (Double v : balance.values()) {
                double r = round3(v);
                if (r > 0) q += r;
            }
            return round3(q);
        }

        /** Every EPC the ledger mentions (candidates for step 4). */
        List<String> epcs() { return new ArrayList<String>(balance.keySet()); }
    }

    /** Tag count of one product in one NW warehouse. */
    public static final class EpcStock {
        /** false: the product does not exist on NW (HTTP 404 in step 1); counts are 0. */
        public boolean found;
        public String warehouseId = "";
        public String baseUnit;
        public int epcCount;
        public double epcQuantity;
        public double rfidStock;
        public double systemStock;
        public double untaggedStock;
        /** epcQuantity matches NW's own rfidStock. */
        public boolean consistent;
        /** Counted by asking NW about each EPC (step 4), not from the ledger alone. */
        public boolean verified;
        public long fetchedAt;
    }

    // ------------------------------------------------------------------ warehouses

    /** NW warehouse for a Circa store, matched by name ("CIRCA LUMINA" -> Lumina); "" if unknown. */
    static String warehouseFor(String circaStoreName) {
        String n = fold(circaStoreName);
        if (n.contains("lumina")) return WH_LUMINA;
        if (n.contains("florita")) return WH_FLORITA;
        return "";
    }

    /** "Lumina", "Florita", or the first block of an unknown UUID. */
    static String warehouseLabel(String id) {
        if (WH_LUMINA.equalsIgnoreCase(id)) return "Lumina";
        if (WH_FLORITA.equalsIgnoreCase(id)) return "Florita";
        if (id == null || id.isEmpty()) return "";
        int dash = id.indexOf('-');
        return dash > 0 ? id.substring(0, dash) + "…" : id;
    }

    static boolean isUuid(String s) {
        return s != null && s.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    private static String fold(String s) {
        if (s == null) return "";
        String d = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return d.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ calls

    /** Step 1. On HTTP 404 the reply is ok with a null value: the product is not on NW. */
    public static CircaClient.Reply<Product> product(String header, long circaProductId) {
        CircaClient.Reply<Product> r = new CircaClient.Reply<Product>();
        // The bare Circa id: "CIRCA-1467" answers 404.
        String text = get(header, "/products/by-external/" + circaProductId, r);
        if (text == null) {
            if (r.http == 404) {
                r.ok = true;
                r.errorCode = null;
                r.message = null;
            }
            return r;
        }
        try {
            r.value = parseProduct(text);
            r.ok = r.value.id != null;
            if (!r.ok) bad(r, new IllegalStateException("thiếu id sản phẩm"));
        } catch (Exception e) {
            bad(r, e);
        }
        return r;
    }

    /** Steps 2-4 for a product found in step 1. Blocking; several calls. */
    public static CircaClient.Reply<EpcStock> epcStock(String header, Product p, String warehouseId) {
        long start = System.currentTimeMillis();
        CircaClient.Reply<EpcStock> r = new CircaClient.Reply<EpcStock>();
        String wh = warehouseId.toLowerCase(Locale.ROOT);
        try {
            String text = get(header, "/stock/summary?productId=" + CircaClient.enc(p.id)
                    + "&warehouseId=" + CircaClient.enc(wh), r);
            if (text == null) return r;
            Summary sum;
            try {
                sum = parseSummary(text);
            } catch (Exception e) {
                bad(r, e);
                return r;
            }

            Ledger led = new Ledger();
            long after = 0;
            for (int page = 0; ; page++) {
                if (page >= MAX_LEDGER_PAGES) {
                    led.truncated = true;
                    break;
                }
                text = get(header, ledgerPath(p.id, wh, after), r);
                if (text == null) return r;
                long next;
                try {
                    next = parseLedgerPage(text, wh, led);
                } catch (Exception e) {
                    bad(r, e);
                    return r;
                }
                if (next <= after) break;
                after = next;
            }

            EpcStock s = new EpcStock();
            s.found = true;
            s.warehouseId = wh;
            s.baseUnit = p.baseUnit;
            s.rfidStock = sum.rfidStock;
            s.systemStock = sum.systemStock;
            s.untaggedStock = sum.untaggedStock;
            s.epcCount = led.inStockCount();
            s.epcQuantity = led.inStockQuantity();
            s.consistent = Math.abs(s.epcQuantity - s.rfidStock) <= EPS;
            if (!s.consistent && led.balance.size() <= MAX_VERIFY) {
                double[] v = verify(header, led.epcs(), wh);
                if (v != null) {
                    s.epcCount = (int) v[0];
                    s.epcQuantity = round3(v[1]);
                    s.verified = true;
                    s.consistent = Math.abs(s.epcQuantity - s.rfidStock) <= EPS;
                }
            }
            s.fetchedAt = System.currentTimeMillis();
            r.value = s;
            r.ok = true;
            r.errorCode = null;
            r.message = null;
            return r;
        } finally {
            r.elapsedMs = System.currentTimeMillis() - start;
        }
    }

    static String ledgerPath(String productId, String wh, long after) {
        // afterLedgerNumber must always be sent: without it NW returns the latest 200 rows as a
        // bare array instead of paging from the start.
        return "/stock/ledger?productId=" + CircaClient.enc(productId) + "&warehouseId=" + CircaClient.enc(wh)
                + "&limit=1000&afterLedgerNumber=" + after;
    }

    /**
     * Step 4: {active tag count, base quantity on them}, asking NW about each EPC, 4 at a time.
     * null when any call fails, so the caller keeps the ledger count instead of a partial one.
     */
    private static double[] verify(final String header, List<String> epcs, final String wh) {
        if (epcs.isEmpty()) return new double[] {0, 0};
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, epcs.size()));
        try {
            List<Future<double[]>> parts = new ArrayList<Future<double[]>>();
            for (final String epc : epcs) {
                parts.add(pool.submit(new Callable<double[]>() {
                    @Override public double[] call() {
                        CircaClient.Reply<Void> rr = new CircaClient.Reply<Void>();
                        String text = get(header, "/integrations/circa/epcs/" + CircaClient.enc(epc), rr);
                        if (text == null) return rr.http == 404 ? new double[] {0, 0} : null;
                        try {
                            return parseEpc(text, wh);
                        } catch (Exception e) {
                            return null;
                        }
                    }
                }));
            }
            double count = 0, qty = 0;
            for (Future<double[]> f : parts) {
                double[] v = f.get();
                if (v == null) return null;
                count += v[0];
                qty += v[1];
            }
            return new double[] {count, qty};
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ transport

    /** GET with back-off on 429; the body on HTTP 2xx, otherwise null with the error fields filled. */
    private static String get(String header, String path, CircaClient.Reply<?> r) {
        try {
            for (int attempt = 1; ; attempt++) {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("Accept", "application/json");
                    c.setRequestProperty("Authorization", header);
                    r.http = c.getResponseCode();
                    InputStream in = r.http >= 400 ? c.getErrorStream() : c.getInputStream();
                    String text = in == null ? "" : ApiClient.readAll(in);
                    if (r.http == 429 && attempt < MAX_ATTEMPTS) {
                        Thread.sleep(1000L * attempt);
                        continue;
                    }
                    if (r.http >= 200 && r.http < 300) return text;
                    describeError(r.http, text, r);
                    return null;
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        } catch (IOException e) {
            ApiClient.Response tmp = new ApiClient.Response();
            tmp.http = r.http;
            ApiClient.describeFailure(e, ApiClient.hostOf(BASE_URL), "Nextwaves", tmp);
            r.ok = false;
            r.errorCode = tmp.errorCode;
            r.message = tmp.message;
            r.networkError = tmp.networkError;
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r.ok = false;
            r.errorCode = "CANCELLED";
            r.message = "Đã hủy";
            return null;
        } catch (RuntimeException e) {
            // Android's HTTP stack throws IllegalArgumentException for a header value it rejects.
            r.ok = false;
            r.errorCode = "BAD_REQUEST";
            r.message = e instanceof IllegalArgumentException
                    ? "Khóa Nextwaves có ký tự không hợp lệ. Nhập lại khóa trong Cài đặt."
                    : "Không gọi được Nextwaves: " + e;
            return null;
        }
    }

    static void describeError(int http, String text, CircaClient.Reply<?> r) {
        r.ok = false;
        String code = null, msg = null;
        try {
            JSONObject o = new JSONObject(text);
            code = str(o, "code");
            msg = str(o, "message");
            JSONObject err = o.optJSONObject("error");
            if (err != null) {
                if (code == null) code = str(err, "code");
                if (msg == null) msg = str(err, "message");
            } else if (msg == null) {
                msg = str(o, "error");
            }
        } catch (Exception ignored) {
            // non-JSON body (proxy page etc.)
        }
        r.errorCode = code != null ? code : "HTTP_" + http;
        if (http == 401) {
            r.message = "Khóa Nextwaves sai hoặc đã hết hạn. Quét QR cài đặt mới hoặc sửa trong Cài đặt.";
        } else if (http == 403) {
            r.message = "Khóa Nextwaves không có quyền với kho này.";
        } else if (http == 429) {
            r.message = "Nextwaves đang giới hạn số lần gọi. Bấm LÀM MỚI sau ít giây.";
        } else {
            r.message = "Nextwaves lỗi " + (http > 0 ? "HTTP " + http : "") + (msg != null ? " — " + msg : "");
        }
    }

    private static void bad(CircaClient.Reply<?> r, Exception e) {
        r.ok = false;
        r.value = null;
        r.errorCode = "BAD_RESPONSE";
        r.message = "Phản hồi Nextwaves không hợp lệ: " + e.getMessage();
    }

    // ------------------------------------------------------------------ parsing

    /** The object itself, or its "data" member when NW wraps it. */
    private static JSONObject body(String text) throws Exception {
        JSONObject o = new JSONObject(text);
        JSONObject d = o.optJSONObject("data");
        return d != null && !o.has("id") && !o.has("rfidStock") && !o.has("status") ? d : o;
    }

    static Product parseProduct(String text) throws Exception {
        JSONObject o = body(text);
        Product p = new Product();
        p.id = str(o, "id");
        p.identifier = str(o, "productIdentifier");
        p.title = str(o, "title");
        p.baseUnit = str(o, "baseUnitOfMeasure");
        return p;
    }

    static Summary parseSummary(String text) throws Exception {
        JSONObject o = body(text);
        Summary s = new Summary();
        s.systemStock = o.optDouble("systemStock", 0);
        s.rfidStock = o.optDouble("rfidStock", 0);
        s.untaggedStock = o.optDouble("untaggedStock", 0);
        if (Double.isNaN(s.systemStock)) s.systemStock = 0;
        if (Double.isNaN(s.rfidStock)) s.rfidStock = 0;
        if (Double.isNaN(s.untaggedStock)) s.untaggedStock = 0;
        return s;
    }

    /**
     * Adds one ledger page to {@code led}: each row of warehouse {@code wh} splits its quantity
     * (positive in, negative out) evenly over its EPCs. Returns the cursor for the next page, or
     * -1 when there is none (empty page, or a bare array without a cursor).
     */
    static long parseLedgerPage(String text, String wh, Ledger led) throws Exception {
        String t = text.trim();
        JSONArray rows;
        long next = -1;
        if (t.startsWith("[")) {
            rows = new JSONArray(t);
        } else {
            JSONObject o = new JSONObject(t);
            rows = o.optJSONArray("data");
            if (!o.isNull("nextAfter")) next = o.optLong("nextAfter", -1);
        }
        if (rows == null || rows.length() == 0) return -1;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            led.rows++;
            if (!wh.equalsIgnoreCase(row.optString("warehouseId", ""))) continue;
            JSONArray epcs = row.optJSONArray("epcs");
            if (epcs == null || epcs.length() == 0) continue;
            double per = row.optDouble("quantity", 0) / epcs.length();
            if (Double.isNaN(per)) continue;
            for (int k = 0; k < epcs.length(); k++) {
                Object e = epcs.opt(k);
                String epc = e instanceof JSONObject ? str((JSONObject) e, "epc") : e == null ? null : e.toString().trim();
                if (epc == null || epc.isEmpty() || "null".equals(epc)) continue;
                Double prev = led.balance.get(epc);
                led.balance.put(epc, (prev == null ? 0 : prev) + per);
            }
        }
        return next;
    }

    /** Step 4 answer: {1, baseQuantity} for an active tag in {@code wh}, else {0, 0}. */
    static double[] parseEpc(String text, String wh) throws Exception {
        JSONObject o = body(text);
        boolean active = "active".equalsIgnoreCase(o.optString("status", ""))
                && wh.equalsIgnoreCase(o.optString("warehouseId", ""));
        if (!active) return new double[] {0, 0};
        double q = o.optDouble("baseQuantity", 0);
        return new double[] {1, Double.isNaN(q) ? 0 : q};
    }

    static double round3(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    private static String str(JSONObject o, String key) {
        if (o.isNull(key)) return null;
        Object v = o.opt(key);
        if (v instanceof JSONObject || v instanceof JSONArray) return null;
        String s = o.optString(key, "").trim();
        return s.isEmpty() ? null : s;
    }

    // ------------------------------------------------------------------ formatting

    /** "31 thẻ · 93 cái", "0 thẻ (chưa có trên NW)". */
    public static String line(EpcStock s) {
        if (s == null) return null;
        if (!s.found) return "0 thẻ (sản phẩm chưa có trên NW)";
        StringBuilder b = new StringBuilder().append(s.epcCount).append(" thẻ");
        if (s.epcQuantity > 0) {
            b.append(" · ").append(CircaClient.qty(s.epcQuantity));
            if (s.baseUnit != null) b.append(' ').append(s.baseUnit);
        }
        if (!s.consistent) b.append(" (chưa khớp NW)");
        return b.toString();
    }
}
