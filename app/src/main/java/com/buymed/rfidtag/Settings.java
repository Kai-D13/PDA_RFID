package com.buymed.rfidtag;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/** All user settings, in one SharedPreferences file. */
public final class Settings {
    public static final String DEFAULT_BASE_URL = "https://open-apis.buymed.com";
    public static final long DEFAULT_CUSTOMER_ID = 32145;
    public static final String CHECK_PATH = "/api/v1/circa/rfid-qr/check";

    private static final String K_BASE = "base_url";
    private static final String K_TOKEN = "token";
    private static final String K_CUSTOMER = "customer_id";
    private static final String K_ONLINE = "online";
    private static final String K_BATCH = "batch_inference";
    private static final String K_ACTION = "scan_action";
    private static final String K_EXTRA = "scan_extra";
    private static final String K_SOUND = "sound";
    private static final String K_VIBRATE = "vibrate";
    private static final String K_CIRCA_KEY = "circa_key";
    private static final String K_STORE_ID = "store_id";
    private static final String K_STORE_CODE = "store_code";
    private static final String K_STORE_NAME = "store_name";
    private static final String K_CLIENT_ID = "circa_client_id";

    private final SharedPreferences sp;

    public Settings(Context ctx) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public String baseUrl() {
        String s = sp.getString(K_BASE, DEFAULT_BASE_URL).trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? DEFAULT_BASE_URL : s;
    }

    /** Buymed check API token (fallback lookup). */
    public String token() { return sp.getString(K_TOKEN, "").trim(); }
    public long customerId() { return sp.getLong(K_CUSTOMER, DEFAULT_CUSTOMER_ID); }
    /** Call online APIs when a QR code cannot be resolved locally, and for stock. */
    public boolean online() { return sp.getBoolean(K_ONLINE, true); }
    /** Infer product from other codes minted in the same batch (same timestamp + machine). */
    public boolean batchInference() { return sp.getBoolean(K_BATCH, false); }
    public String scanAction() { return sp.getString(K_ACTION, "").trim(); }
    public String scanExtra() { return sp.getString(K_EXTRA, "").trim(); }
    public boolean sound() { return sp.getBoolean(K_SOUND, true); }
    public boolean vibrate() { return sp.getBoolean(K_VIBRATE, true); }

    /** Circa POS API key ("ck_u_..."), sent as a Bearer token to api.v2.circa.vn only. */
    public String circaKey() { return headerSafe(sp.getString(K_CIRCA_KEY, "")); }
    public String storeId() { return headerSafe(sp.getString(K_STORE_ID, "")); }
    public String storeCode() { return sp.getString(K_STORE_CODE, "").trim(); }
    public String storeName() { return sp.getString(K_STORE_NAME, "").trim(); }
    public long circaClientId() { return sp.getLong(K_CLIENT_ID, 1); }

    public boolean hasCircaKey() { return !circaKey().isEmpty(); }
    public boolean hasStore() { return !storeId().isEmpty(); }
    /** Circa lookups (QR, stock, product detail) need a key and a chosen store. */
    public boolean canCallCirca() { return online() && hasCircaKey() && hasStore(); }
    public boolean canCallBuymed() { return online() && !token().isEmpty(); }
    public boolean canCallApi() { return canCallCirca() || canCallBuymed(); }

    /** "POS0084 · CIRCA KHO BẮC NINH", or "" when no store is chosen. */
    public String storeLabel() {
        String name = storeName().isEmpty() ? storeId() : storeName();
        if (name.isEmpty()) return "";
        return storeCode().isEmpty() ? name : storeCode() + " · " + name;
    }

    /** Authorization header value; a bare token gets the "Basic " scheme the gateway expects. */
    public String authorizationHeader() {
        String t = token();
        if (t.isEmpty()) return t;
        if (t.indexOf(' ') > 0) return t; // already "Basic xxx" / "Bearer xxx"
        return "Basic " + t;
    }

    /**
     * Keeps only printable ASCII. A key pasted from chat often carries U+00A0 / U+200B / IME
     * diacritics that trim() leaves in, and Android's HTTP stack throws on such header values.
     */
    static String headerSafe(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c <= 0x7E) b.append(c);
        }
        return b.toString().trim();
    }

    /** String field of a settings QR; JSON null or missing -> "" (Android's optString gives "null"). */
    static String jsonStr(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key, "").trim();
    }

    /** Circa Authorization header; a bare key gets the "Bearer " scheme. */
    public String circaAuthorizationHeader() {
        String k = circaKey();
        if (k.isEmpty()) return k;
        if (k.indexOf(' ') > 0) return k;
        return "Bearer " + k;
    }

    public void save(String baseUrl, String token, long customerId, boolean online, boolean batch,
                     String action, String extra, boolean sound, boolean vibrate, String circaKey) {
        sp.edit()
                .putString(K_BASE, baseUrl)
                .putString(K_TOKEN, token)
                .putLong(K_CUSTOMER, customerId)
                .putBoolean(K_ONLINE, online)
                .putBoolean(K_BATCH, batch)
                .putString(K_ACTION, action)
                .putString(K_EXTRA, extra)
                .putBoolean(K_SOUND, sound)
                .putBoolean(K_VIBRATE, vibrate)
                .putString(K_CIRCA_KEY, headerSafe(circaKey))
                .apply();
    }

    public void saveStore(String id, String code, String name, long clientId) {
        sp.edit()
                .putString(K_STORE_ID, id == null ? "" : id)
                .putString(K_STORE_CODE, code == null ? "" : code)
                .putString(K_STORE_NAME, name == null ? "" : name)
                .putLong(K_CLIENT_ID, clientId > 0 ? clientId : 1)
                .apply();
    }

    /**
     * Applies a settings QR: "RFIDCFG:" followed by JSON with any of
     * baseUrl, token, customerId, online, batchInference, scanAction, scanExtra,
     * circaKey, storeId, storeCode, storeName.
     * Lets an admin configure many PDAs by scanning one code instead of typing a key.
     */
    public int applyConfig(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        SharedPreferences.Editor e = sp.edit();
        int n = 0;
        if (o.has("baseUrl")) {
            String base = jsonStr(o, "baseUrl");
            if (!base.startsWith("https://")) throw new JSONException("baseUrl phải là https://");
            // A code that points the app at another server must bring its own token; otherwise
            // a stray QR could make the app send the stored Buymed token somewhere else.
            if (!o.has("token") && !base.equals(baseUrl())) e.putString(K_TOKEN, "");
            e.putString(K_BASE, base);
            n++;
        }
        if (o.has("token")) { e.putString(K_TOKEN, headerSafe(jsonStr(o, "token"))); n++; }
        if (o.has("customerId")) { e.putLong(K_CUSTOMER, o.getLong("customerId")); n++; }
        if (o.has("online")) { e.putBoolean(K_ONLINE, o.getBoolean("online")); n++; }
        if (o.has("batchInference")) { e.putBoolean(K_BATCH, o.getBoolean("batchInference")); n++; }
        if (o.has("scanAction")) { e.putString(K_ACTION, jsonStr(o, "scanAction")); n++; }
        if (o.has("scanExtra")) { e.putString(K_EXTRA, jsonStr(o, "scanExtra")); n++; }
        // The Circa base URL is fixed in the app, so a settings QR can only change the key, not
        // where it is sent.
        if (o.has("circaKey")) { e.putString(K_CIRCA_KEY, headerSafe(jsonStr(o, "circaKey"))); n++; }
        if (o.has("storeId")) {
            e.putString(K_STORE_ID, headerSafe(jsonStr(o, "storeId")));
            e.putString(K_STORE_CODE, jsonStr(o, "storeCode"));
            e.putString(K_STORE_NAME, jsonStr(o, "storeName"));
            // Never keep the client of a previously chosen store with a new store.
            e.putLong(K_CLIENT_ID, o.isNull("clientId") ? 1 : Math.max(1, o.optLong("clientId", 1)));
            n++;
        }
        e.apply();
        return n;
    }
}
