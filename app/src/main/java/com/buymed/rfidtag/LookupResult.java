package com.buymed.rfidtag;

/** What one scan resolved to, ready for the screen and the history list. */
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

    public static int statusForTag(String tagType) {
        if (tagType == null) return UNCONFIGURED;
        if ("temthuong".equalsIgnoreCase(tagType)) return TEM_THUONG;
        if ("temgap".equalsIgnoreCase(tagType)) return TEM_GAP;
        return OTHER_TAG;
    }

    /** Big label on the result card. */
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

    public int color() {
        switch (status) {
            case TEM_THUONG: return 0xFF1B7F3B;
            case TEM_GAP: return 0xFFD9730D;
            case OTHER_TAG: return 0xFF1A5E7A;
            case UNCONFIGURED: return 0xFF5F6B73;
            case CONFIG: return 0xFF1A5E7A;
            default: return 0xFFB3261E;
        }
    }

    public boolean isTag() {
        return status == TEM_THUONG || status == TEM_GAP || status == OTHER_TAG;
    }
}
