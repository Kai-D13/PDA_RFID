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

    private final SharedPreferences sp;

    public Settings(Context ctx) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public String baseUrl() {
        String s = sp.getString(K_BASE, DEFAULT_BASE_URL).trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? DEFAULT_BASE_URL : s;
    }

    public String token() { return sp.getString(K_TOKEN, "").trim(); }
    public long customerId() { return sp.getLong(K_CUSTOMER, DEFAULT_CUSTOMER_ID); }
    /** Call the check API when a QR code cannot be resolved locally. */
    public boolean online() { return sp.getBoolean(K_ONLINE, true); }
    /** Infer product from other codes minted in the same batch (same timestamp + machine). */
    public boolean batchInference() { return sp.getBoolean(K_BATCH, false); }
    public String scanAction() { return sp.getString(K_ACTION, "").trim(); }
    public String scanExtra() { return sp.getString(K_EXTRA, "").trim(); }
    public boolean sound() { return sp.getBoolean(K_SOUND, true); }
    public boolean vibrate() { return sp.getBoolean(K_VIBRATE, true); }

    public boolean canCallApi() { return online() && !token().isEmpty(); }

    /** Authorization header value; a bare token gets the "Basic " scheme the gateway expects. */
    public String authorizationHeader() {
        String t = token();
        if (t.isEmpty()) return t;
        if (t.indexOf(' ') > 0) return t; // already "Basic xxx" / "Bearer xxx"
        return "Basic " + t;
    }

    public void save(String baseUrl, String token, long customerId, boolean online, boolean batch,
                     String action, String extra, boolean sound, boolean vibrate) {
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
                .apply();
    }

    /**
     * Applies a settings QR: "RFIDCFG:" followed by JSON with any of
     * baseUrl, token, customerId, online, batchInference, scanAction, scanExtra.
     * Lets an admin configure many PDAs by scanning one code instead of typing a token.
     */
    public int applyConfig(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        SharedPreferences.Editor e = sp.edit();
        int n = 0;
        if (o.has("baseUrl")) {
            String base = o.getString("baseUrl").trim();
            if (!base.startsWith("https://")) throw new JSONException("baseUrl phải là https://");
            // A code that points the app at another server must bring its own token; otherwise
            // a stray QR could make the app send the stored Buymed token somewhere else.
            if (!o.has("token") && !base.equals(baseUrl())) e.putString(K_TOKEN, "");
            e.putString(K_BASE, base);
            n++;
        }
        if (o.has("token")) { e.putString(K_TOKEN, o.getString("token")); n++; }
        if (o.has("customerId")) { e.putLong(K_CUSTOMER, o.getLong("customerId")); n++; }
        if (o.has("online")) { e.putBoolean(K_ONLINE, o.getBoolean("online")); n++; }
        if (o.has("batchInference")) { e.putBoolean(K_BATCH, o.getBoolean("batchInference")); n++; }
        if (o.has("scanAction")) { e.putString(K_ACTION, o.getString("scanAction")); n++; }
        if (o.has("scanExtra")) { e.putString(K_EXTRA, o.getString("scanExtra")); n++; }
        e.apply();
        return n;
    }
}
