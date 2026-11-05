package com.buymed.rfidtag;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Calls POST /api/v1/circa/rfid-qr/check, only to find the product behind a QR code. */
public final class ApiClient {

    public static final class Response {
        public boolean ok;
        public int http;
        public String errorCode;
        public String message;
        public long productId = -1;
        public String productName;
        public String rfidTagType;
        public String rfidUnit;
        public String machineCode;
        public boolean isNotFound;
        public long elapsedMs;
    }

    private ApiClient() {}

    public static Response check(Settings s, String qr) {
        Response r = new Response();
        long start = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            JSONObject body = new JSONObject();
            body.put("qr", qr);
            body.put("customerId", s.customerId());
            byte[] payload = body.toString().getBytes(ProductStore.UTF8);

            c = (HttpURLConnection) new URL(s.baseUrl() + Settings.CHECK_PATH).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(12000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Authorization", s.authorizationHeader());
            c.setFixedLengthStreamingMode(payload.length);
            OutputStream out = c.getOutputStream();
            out.write(payload);
            out.close();

            r.http = c.getResponseCode();
            InputStream in = r.http >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);
            parse(text, r);
        } catch (IOException e) {
            r.ok = false;
            r.errorCode = "NETWORK";
            r.message = "Không gọi được API: " + e.getMessage();
        } catch (Exception e) {
            r.ok = false;
            r.errorCode = "BAD_RESPONSE";
            r.message = "Phản hồi API không hợp lệ: " + e.getMessage();
        } finally {
            if (c != null) c.disconnect();
            r.elapsedMs = System.currentTimeMillis() - start;
        }
        return r;
    }

    static void parse(String text, Response r) throws Exception {
        if (text.trim().isEmpty()) {
            r.ok = false;
            r.errorCode = "HTTP_" + r.http;
            r.message = "API trả về rỗng (HTTP " + r.http + ")";
            return;
        }
        JSONObject o = new JSONObject(text);
        String status = o.optString("status", "");
        r.errorCode = nullIfEmpty(o.optString("errorCode", ""));
        r.message = o.optString("message", "");
        if (!"OK".equals(status)) {
            r.ok = false;
            if (r.errorCode == null) r.errorCode = status.isEmpty() ? "HTTP_" + r.http : status;
            return;
        }
        JSONArray data = o.optJSONArray("data");
        JSONObject d = data != null && data.length() > 0 ? data.optJSONObject(0) : null;
        if (d == null || !d.has("productId")) {
            r.ok = false;
            r.errorCode = "NO_PRODUCT";
            r.message = "API không trả về productId";
            return;
        }
        r.ok = true;
        r.productId = d.getLong("productId");
        r.productName = nullIfEmpty(d.optString("productName", ""));
        r.rfidTagType = nullIfEmpty(d.optString("rfidTagType", ""));
        r.rfidUnit = nullIfEmpty(d.optString("rfidUnit", ""));
        r.machineCode = nullIfEmpty(d.optString("machineCode", ""));
        r.isNotFound = d.optBoolean("isNotFound", false);
    }

    private static String nullIfEmpty(String s) {
        return s == null || s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        } finally {
            in.close();
        }
        return new String(b.toByteArray(), ProductStore.UTF8);
    }
}
