package com.buymed.rfidtag;

import java.util.ArrayList;

/** What one scan resolved to, ready for the screen, the history list and the detail popup. */
public final class LookupResult {
    public static final int TEM_THUONG = 1;
    public static final int TEM_GAP = 2;
    public static final int OTHER_TAG = 3;
    public static final int UNCONFIGURED = 4;
    public static final int NOT_IN_DATA = 5;
    public static final int ERROR = 6;
    public static final int CONFIG = 7;

    public int status;
    public String tagType;
    public long productId = -1;
    public String productName;
    public String rfidUnit;
    public String input;
    public String code;
    public String source;
    public String warning;
    public String error;
    public long elapsedMs;
    public final long time = System.currentTimeMillis();

    // From Circa's QR lookup (or the QR cache).
    public String sku;
    public String lot;
    public String expiry;
    public String qrUnit;
    public final ArrayList<String> images = new ArrayList<String>();
    /** Store selected when the code was scanned (for display; stock carries its own store). */
    public String storeLabel;

    // Filled in after the result is shown; written on the UI thread only.
    public CircaClient.Stock stock;
    public CircaClient.Detail detail;
    public String stockError;
    public boolean stockLoading;
    /** Nextwaves: RFID tags (EPCs) of the product in the NW warehouse of the current store. */
    public NextwavesClient.EpcStock nw;
    public String nwError;
    /** nwError is "not configured" (no key / no NW warehouse), not a failure. */
    public boolean nwSetup;
    public boolean nwLoading;

    public static int statusForTag(String tagType) {
        if (tagType == null) return UNCONFIGURED;
        if ("temthuong".equalsIgnoreCase(tagType)) return TEM_THUONG;
        if ("temgap".equalsIgnoreCase(tagType)) return TEM_GAP;
        return OTHER_TAG;
    }

    /** Label on the result card. */
    public String headline() {
        switch (status) {
            case TEM_THUONG: return "TEM THƯỜNG";
            case TEM_GAP: return "TEM GẬP";
            case OTHER_TAG: return tagType.toUpperCase();
            case UNCONFIGURED: return "CHƯA CẤU HÌNH";
            case NOT_IN_DATA: return "KHÔNG CÓ DỮ LIỆU";
            case CONFIG: return "ĐÃ CẬP NHẬT CÀI ĐẶT";
            default: return "LỖI";
        }
    }

    /** Short label for the history chip. */
    public String shortTag() {
        switch (status) {
            case TEM_THUONG: return "THƯỜNG";
            case TEM_GAP: return "GẬP";
            case OTHER_TAG: return tagType.toUpperCase();
            case UNCONFIGURED: return "CHƯA CẤU HÌNH";
            case NOT_IN_DATA: return "KHÔNG CÓ";
            case CONFIG: return "CÀI ĐẶT";
            default: return "LỖI";
        }
    }

    public int color() {
        switch (status) {
            case TEM_THUONG: return 0xFF23843A;
            case TEM_GAP: return 0xFFD9730D;
            case OTHER_TAG: return 0xFF1F5FA8;
            case UNCONFIGURED: return 0xFF5F6B73;
            case CONFIG: return 0xFF1F5FA8;
            default: return 0xFFB3261E;
        }
    }

    public boolean isTag() {
        return status == TEM_THUONG || status == TEM_GAP || status == OTHER_TAG;
    }

    /** A product was identified, so stock / images can be fetched for it. */
    public boolean hasProduct() {
        return productId >= 0 && status != ERROR && status != CONFIG;
    }

    /** "3.312 viên = 3 hộp", "đang tải…", the error, or null when there is nothing to say. */
    public String stockSummary() {
        if (stock != null) {
            if (stock.skus.isEmpty()) return "0 (chưa có trong kho)";
            return CircaClient.stockLine(stock, detail, rfidUnit);
        }
        if (stockLoading) return "đang tải…";
        return stockError;
    }

    /** "31 thẻ · 93 cái", "đang đếm…", the error / setup hint, or null. */
    public String nwSummary() {
        if (nw != null) return NextwavesClient.line(nw);
        if (nwLoading) return "đang đếm…";
        return nwError;
    }

    /** Best known product name: local data, then Circa. */
    public String displayName() {
        if (productName != null && !productName.isEmpty()) return productName;
        if (detail != null && detail.name != null) return detail.name;
        return null;
    }
}
