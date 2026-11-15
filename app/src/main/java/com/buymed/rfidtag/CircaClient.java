package com.buymed.rfidtag;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Read-only calls to the Circa POS API (api.v2.circa.vn) with a personal/service API key:
 *  - GET /v1/authz/me                              stores the key can see
 *  - GET /v1/pos/{store}/qr/{code}                 QR code -> product, SKU, lot, expiry
 *  - GET /v1/inventory/admin/stock/search          stock of one product in one store
 *  - GET /v1/pos/{store}/product/{id}              name, pack size, images, unit conversions
 * Parsing is split from transport so it can be unit-tested on the JVM.
 */
public final class CircaClient {
    public static final String BASE_URL = "https://api.v2.circa.vn";

    private CircaClient() {}

    // ------------------------------------------------------------------ models

    /** What a call needs from the settings; separate so the HTTP code also runs on a plain JVM. */
    public static final class Auth {
        final String header;
        final String storeId;
        final long clientId;
        final String storeName;

        public Auth(String header, String storeId, long clientId, String storeName) {
            this.header = header == null ? "" : header;
            this.storeId = storeId == null ? "" : storeId;
            this.clientId = clientId > 0 ? clientId : 1;
            this.storeName = storeName == null ? "" : storeName;
        }

        public static Auth of(Settings s) {
            return new Auth(s.circaAuthorizationHeader(), s.storeId(), s.circaClientId(), s.storeName());
        }
    }

    /** Outcome of one call; {@code value} is set only when {@code ok}. */
    public static final class Reply<T> {
        public boolean ok;
        public int http;
        public String errorCode;
        public String message;
        public boolean networkError;
        public long elapsedMs;
        public T value;
    }

    public static final class Store {
        public String id;
        public String code;
        public String name;
        public long clientId;

        /** Label used in lists and the header. */
        public String label() {
            return code == null || code.isEmpty() ? name : name + " (" + code + ")";
        }
    }

    public static final class QrInfo {
        public long productId = -1;
        public String productName;
        public String sku;
        public String lot;
        public String expiry;
        public String unit;
        public final ArrayList<String> images = new ArrayList<String>();
    }

    public static final class Unit {
        public String name;
        public double rate;
        public boolean base;
        public boolean rfid;
    }

    public static final class Detail {
        public long productId = -1;
        public String name;
        public String volumes;
        public String registration;
        public final ArrayList<String> images = new ArrayList<String>();
        public final ArrayList<Unit> units = new ArrayList<Unit>();

        /** Unit flagged for RFID on Circa, or null. */
        public Unit rfidUnit() {
            for (Unit u : units) if (u.rfid) return u;
            return null;
        }

        /** Unit by name (case-insensitive), or null. */
        public Unit unit(String name) {
            if (name == null) return null;
            for (Unit u : units) if (u.name != null && u.name.equalsIgnoreCase(name.trim())) return u;
            return null;
        }
    }

    public static final class Lot {
        public String lot;
        public String doc;
        public double onHand;
    }

    public static final class Location {
        public String code;
        public String name;
        public double onHand;
        public final ArrayList<Lot> lots = new ArrayList<Lot>();
    }

    public static final class SkuStock {
        public String sku;
        public double onHand;
        public final ArrayList<Location> locations = new ArrayList<Location>();
    }

    /** Stock of one product in one store, summed over its SKUs (one per seller). */
    public static final class Stock {
        public long productId = -1;
        public String baseUnit;
        public double onHand;
        public double available;
        public final ArrayList<SkuStock> skus = new ArrayList<SkuStock>();
        public long fetchedAt;
        /** Store the numbers belong to; stock from another store must never be shown as current. */
        public String storeId = "";
        public String storeName;
    }

    // ------------------------------------------------------------------ calls

    public static Reply<List<Store>> stores(Auth s) {
        Reply<List<Store>> r = new Reply<List<Store>>();
        String text = get(s, "/v1/authz/me", r);
        if (text == null) return r;
        try {
            r.value = parseStores(text);
            r.ok = true;
        } catch (Exception e) {
            bad(r, e);
        }
        return r;
    }

    public static Reply<QrInfo> qr(Auth s, String code) {
        Reply<QrInfo> r = new Reply<QrInfo>();
        String text = get(s, "/v1/pos/" + enc(s.storeId) + "/qr/" + enc(code), r);
        if (text == null) {
            // Circa answers unknown codes with a generic HTTP 500, so say both possibilities.
            if (!r.networkError && r.http >= 500) {
                r.message = "Circa không tra được mã này (mã chưa có trên Circa, hoặc hệ thống Circa đang lỗi).";
            }
            return r;
        }
        try {
            r.value = parseQr(text);
            r.ok = r.value.productId >= 0;
            if (!r.ok) {
                r.errorCode = "NO_PRODUCT";
                r.message = "Circa không trả về product_id cho mã này.";
            }
        } catch (Exception e) {
            bad(r, e);
        }
        return r;
    }

    public static Reply<Stock> stock(Auth s, long productId) {
        Reply<Stock> r = new Reply<Stock>();
        String text = get(s, stockPath(s.storeId, productId), r);
        if (text == null) return r;
        try {
            r.value = parseStock(text, productId);
            r.value.fetchedAt = System.currentTimeMillis();
            r.value.storeId = s.storeId;
            r.value.storeName = s.storeName;
            r.ok = true;
        } catch (Exception e) {
            bad(r, e);
        }
        return r;
    }

    public static Reply<Detail> detail(Auth s, long productId) {
        Reply<Detail> r = new Reply<Detail>();
        String text = get(s, "/v1/pos/" + enc(s.storeId) + "/product/" + productId, r);
        if (text == null) return r;
        try {
            r.value = parseDetail(text);
            r.ok = true;
        } catch (Exception e) {
            bad(r, e);
        }
        return r;
    }

    /** Stock search filtered by exact product id (the API ignores a "product_id" filter; "product_ids" works). */
    static String stockPath(String storeId, long productId) {
        String query = "pos_id=" + storeId + ",product_ids=" + productId + ",include_negative_qty=true,";
        return "/v1/inventory/admin/stock/search?query=" + enc(query) + "&limit=50&page=1";
    }

    // ------------------------------------------------------------------ transport

    /** GET and return the body on HTTP 2xx with success=true; otherwise fill the error fields and return null. */
    private static String get(Auth s, String path, Reply<?> r) {
        long start = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(12000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Authorization", s.header);
            c.setRequestProperty("X-Circa-App", "pos");
            c.setRequestProperty("X-Client-Id", String.valueOf(s.clientId));
            if (!s.storeId.isEmpty()) c.setRequestProperty("X-Store-Id", s.storeId);
            r.http = c.getResponseCode();
            InputStream in = r.http >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : ApiClient.readAll(in);
            if (r.http >= 200 && r.http < 300 && isSuccess(text)) return text;
            describeError(r.http, text, r);
            return null;
        } catch (IOException e) {
            ApiClient.Response tmp = new ApiClient.Response();
            tmp.http = r.http;
            ApiClient.describeFailure(e, ApiClient.hostOf(BASE_URL), "Circa", tmp);
            r.ok = false;
            r.errorCode = tmp.errorCode;
            r.message = tmp.message;
            r.networkError = tmp.networkError;
            return null;
        } catch (RuntimeException e) {
            // Android's HTTP stack throws IllegalArgumentException for a header value it rejects
            // (e.g. a key with characters Settings.headerSafe did not catch); never crash on it.
            r.ok = false;
            r.errorCode = "BAD_REQUEST";
            r.message = e instanceof IllegalArgumentException
                    ? "Khóa API Circa hoặc mã kho có ký tự không hợp lệ. Nhập lại khóa trong Cài đặt."
                    : "Không gọi được Circa: " + e;
            return null;
        } finally {
            if (c != null) c.disconnect();
            r.elapsedMs = System.currentTimeMillis() - start;
        }
    }

    private static boolean isSuccess(String text) {
        try {
            return new JSONObject(text).optBoolean("success", false);
        } catch (Exception e) {
            return false;
        }
    }

    /** Turns an HTTP error / success=false body into a message a warehouse user can act on. */
    static void describeError(int http, String text, Reply<?> r) {
        r.ok = false;
        String code = null, msg = null;
        try {
            JSONObject o = new JSONObject(text);
            JSONArray errs = o.optJSONArray("errors");
            JSONObject e = errs != null && errs.length() > 0 ? errs.optJSONObject(0) : null;
            if (e != null) {
                code = str(e, "code");
                msg = str(e, "message");
            }
        } catch (Exception ignored) {
            // non-JSON body (proxy page etc.)
        }
        r.errorCode = code != null ? code : "HTTP_" + http;
        if (http == 401 || "invalid_token".equalsIgnoreCase(code)) {
            r.message = "Khóa API Circa sai hoặc đã hết hạn. Quét QR cài đặt mới hoặc sửa trong Cài đặt.";
        } else if (http == 403) {
            r.message = "Khóa API Circa không có quyền với kho này. Chọn kho khác hoặc xin cấp quyền.";
        } else if (http == 429) {
            r.message = "Circa đang giới hạn số lần gọi. Đợi vài giây rồi quét lại.";
        } else {
            r.message = "Circa lỗi " + (http > 0 ? "HTTP " + http : "") + (msg != null ? " — " + msg : "");
        }
    }

    private static void bad(Reply<?> r, Exception e) {
        r.ok = false;
        r.value = null;
        r.errorCode = "BAD_RESPONSE";
        r.message = "Phản hồi Circa không hợp lệ: " + e.getMessage();
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ parsing

    static List<Store> parseStores(String text) throws Exception {
        JSONObject data = new JSONObject(text).getJSONObject("data");
        ArrayList<Store> out = new ArrayList<Store>();
        JSONArray clients = data.optJSONArray("clients");
        for (int i = 0; clients != null && i < clients.length(); i++) {
            JSONObject c = clients.getJSONObject(i);
            long clientId = c.optLong("client_id", 1);
            JSONArray stores = c.optJSONArray("stores");
            for (int j = 0; stores != null && j < stores.length(); j++) {
                JSONObject o = stores.getJSONObject(j);
                Store st = new Store();
                st.id = str(o, "id");
                st.code = str(o, "code");
                st.name = str(o, "name");
                if (st.name == null) st.name = st.code != null ? st.code : st.id;
                st.clientId = clientId;
                if (st.id != null) out.add(st);
            }
        }
        final Collator coll = Collator.getInstance(new Locale("vi"));
        Collections.sort(out, new Comparator<Store>() {
            @Override public int compare(Store a, Store b) { return coll.compare(a.name, b.name); }
        });
        return out;
    }

    static QrInfo parseQr(String text) throws Exception {
        JSONObject d = new JSONObject(text).getJSONObject("data");
        QrInfo q = new QrInfo();
        q.productId = d.optLong("product_id", -1);
        if (q.productId == 0) q.productId = -1;
        q.productName = str(d, "product_name");
        q.sku = str(d, "sku");
        q.lot = str(d, "lot");
        q.expiry = str(d, "expired_date");
        q.unit = str(d, "unit_name");
        strings(d.optJSONArray("image_urls"), q.images);
        return q;
    }

    static Detail parseDetail(String text) throws Exception {
        JSONObject d = new JSONObject(text).getJSONObject("data");
        Detail p = new Detail();
        p.productId = d.optLong("product_id", -1);
        p.name = str(d, "name");
        p.volumes = str(d, "volumes");
        p.registration = str(d, "registration_number");
        strings(d.optJSONArray("image_urls"), p.images);
        JSONArray units = d.optJSONArray("retail_units");
        for (int i = 0; units != null && i < units.length(); i++) {
            JSONObject u = units.optJSONObject(i);
            if (u == null) continue;
            Unit x = new Unit();
            x.name = str(u, "unit");
            x.rate = u.optDouble("convert_rate", 0);
            x.base = u.optBoolean("base_unit", false);
            x.rfid = u.optBoolean("is_rfid_unit", false);
            if (x.name != null) p.units.add(x);
        }
        return p;
    }

    static Stock parseStock(String text, long productId) throws Exception {
        JSONObject data = new JSONObject(text).getJSONObject("data");
        Stock s = new Stock();
        s.productId = productId;
        JSONArray list = data.optJSONArray("list");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            // Belt and braces: never sum another product's stock if the filter is ignored.
            if (o.optLong("product_id", -1) != productId) continue;
            SkuStock k = new SkuStock();
            k.sku = str(o, "sku");
            k.onHand = o.optDouble("on_hand_qty", 0);
            if (s.baseUnit == null) s.baseUnit = str(o, "base_unit");
            JSONArray items = o.optJSONArray("items");
            for (int j = 0; items != null && j < items.length(); j++) {
                JSONObject it = items.getJSONObject(j);
                Location loc = new Location();
                loc.code = str(it, "location_code");
                loc.name = str(it, "location_name");
                loc.onHand = it.optDouble("on_hand_qty", 0);
                JSONArray det = it.optJSONArray("details");
                for (int m = 0; det != null && m < det.length(); m++) {
                    JSONObject l = det.getJSONObject(m);
                    Lot lot = new Lot();
                    lot.lot = str(l, "lot_date");
                    lot.doc = str(l, "doc_code");
                    lot.onHand = l.optDouble("on_hand_qty", 0);
                    loc.lots.add(lot);
                }
                k.locations.add(loc);
            }
            s.onHand += k.onHand;
            s.available += o.optDouble("available_qty", 0);
            s.skus.add(k);
        }
        return s;
    }

    private static void strings(JSONArray a, List<String> out) {
        for (int i = 0; a != null && i < a.length(); i++) {
            String v = a.optString(i, "");
            if (v.startsWith("https://")) out.add(v);
        }
    }

    private static String str(JSONObject o, String key) {
        if (o.isNull(key)) return null;
        String v = o.optString(key, "").trim();
        return v.isEmpty() ? null : v;
    }

    // ------------------------------------------------------------------ formatting

    /** "3.312" / "2,5" — Vietnamese grouping, at most one decimal. */
    public static String qty(double v) {
        double r = Math.rint(v * 10) / 10;
        if (Math.abs(r - Math.rint(r)) < 1e-9) {
            return String.format(Locale.US, "%,d", (long) Math.rint(r)).replace(',', '.');
        }
        String s = String.format(Locale.US, "%,.1f", r);
        return s.replace(',', ' ').replace('.', ',').replace(' ', '.');
    }

    /**
     * "3.312 viên = 3 hộp" — stock in the base unit, plus the RFID tag unit when the
     * conversion is known and differs from the base unit.
     */
    public static String stockLine(Stock s, Detail d, String rfidUnitName) {
        if (s == null) return null;
        String base = s.baseUnit != null ? s.baseUnit : "";
        StringBuilder b = new StringBuilder(qty(s.onHand));
        if (!base.isEmpty()) b.append(' ').append(base);
        Unit u = d == null ? null : d.unit(rfidUnitName);
        if (u == null && d != null) u = d.rfidUnit();
        if (u != null && u.rate > 1 && s.onHand != 0 && !u.name.equalsIgnoreCase(base)) {
            double n = s.onHand / u.rate;
            if ("0".equals(qty(Math.abs(n)))) {
                // e.g. 30 viên of a 1104-viên box: "≈ 0 hộp" would read as out of stock
                b.append(" (< 0,1 ").append(u.name).append(')');
            } else {
                b.append(Math.abs(n - Math.rint(n)) < 1e-9 ? " = " : " ≈ ").append(qty(n)).append(' ').append(u.name);
            }
        }
        return b.toString();
    }
}
