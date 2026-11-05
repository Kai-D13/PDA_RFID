package com.buymed.rfidtag;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns whatever the scanner produced into either a product id or a Buymed QR code.
 *
 * Accepted input:
 *  - a bare product id (1-10 digits), e.g. typed by hand: "1101363"
 *  - a URL or text carrying p=/pid=/productId=/product_id=, or a JSON {"productId": ...},
 *    e.g. "thuocsi.vn/qr/1787548411337B731?p=1101363" (the API ignores the query string)
 *  - a Buymed QR URL or bare code: "thuocsi.vn/qr/1787548411337B731",
 *    "https://qr.v2.circa.vn/item/1757390400000MC011", "1757390400000MC011"
 *    (same rule as the API: everything after the last "/", query and fragment stripped)
 *  - "RFIDCFG:{json}" — a settings QR, see Settings.applyConfig
 */
public final class QrParser {
    public static final int EMPTY = 0;
    public static final int PRODUCT_ID = 1;
    public static final int QR_CODE = 2;
    public static final int CONFIG = 3;

    public static final String CONFIG_PREFIX = "RFIDCFG:";

    public static final class Parsed {
        public int kind;
        public long productId = -1;
        public String code;
        public String text;
    }

    private static final Pattern DIGITS = Pattern.compile("\\d{1,10}");
    private static final Pattern PID_PARAM =
            Pattern.compile("(?i)(?:^|[?&#;/])(?:product_?id|pid|p)=(\\d{1,12})");
    private static final Pattern PID_JSON =
            Pattern.compile("(?i)\"product_?id\"\\s*:\\s*\"?(\\d{1,12})");
    /** 13-digit epoch-millis mint time, then machine code + sequence. */
    private static final Pattern BUYMED_CODE = Pattern.compile("(\\d{13})([A-Za-z0-9]+)");
    /** AIM symbology identifier some scanners prepend, e.g. "]Q1". */
    private static final Pattern AIM_PREFIX = Pattern.compile("^\\][A-Za-z][0-9A-Za-z]");

    private QrParser() {}

    public static Parsed parse(String raw) {
        Parsed p = new Parsed();
        String s = clean(raw);
        p.text = s;
        if (s.isEmpty()) {
            p.kind = EMPTY;
            return p;
        }
        if (s.regionMatches(true, 0, CONFIG_PREFIX, 0, CONFIG_PREFIX.length())) {
            p.kind = CONFIG;
            p.code = s.substring(CONFIG_PREFIX.length());
            return p;
        }
        if (DIGITS.matcher(s).matches()) {
            p.kind = PRODUCT_ID;
            p.productId = Long.parseLong(s);
            return p;
        }
        Matcher m = PID_PARAM.matcher(s);
        if (!m.find()) {
            m = PID_JSON.matcher(s);
            if (!m.find()) m = null;
        }
        if (m != null) {
            p.kind = PRODUCT_ID;
            p.productId = Long.parseLong(m.group(1));
            return p;
        }

        String c = s;
        int cut = indexOfAny(c, '?', '#');
        if (cut >= 0) c = c.substring(0, cut);
        while (c.endsWith("/")) c = c.substring(0, c.length() - 1);
        int slash = c.lastIndexOf('/');
        if (slash >= 0) c = c.substring(slash + 1);
        c = c.trim();
        if (c.isEmpty()) {
            p.kind = EMPTY;
            return p;
        }
        p.kind = QR_CODE;
        p.code = c;
        return p;
    }

    /** First 13 digits of a Buymed code (the mint timestamp), or null if it does not look like one. */
    public static String batchTimestamp(String code) {
        if (code == null) return null;
        Matcher m = BUYMED_CODE.matcher(code);
        return m.matches() ? m.group(1) : null;
    }

    /** Part of a Buymed code after the timestamp: machine code + sequence number. */
    public static String afterTimestamp(String code) {
        if (code == null) return null;
        Matcher m = BUYMED_CODE.matcher(code);
        return m.matches() ? m.group(2) : null;
    }

    static String clean(String raw) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch < 0x20 || ch == 0x7F || ch == '﻿' || ch == '​') continue;
            b.append(ch);
        }
        String s = b.toString().trim();
        Matcher aim = AIM_PREFIX.matcher(s);
        if (aim.find()) s = s.substring(aim.end()).trim();
        return s;
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }
}
