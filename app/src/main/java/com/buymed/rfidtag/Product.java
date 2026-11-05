package com.buymed.rfidtag;

/** One row of the local RFID config: what tag a product takes and which unit it goes on. */
public final class Product {
    public final long id;
    /** "temthuong", "temgap", or null when the product is not configured. */
    public final String typeTag;
    public final String rfidUnit;
    public final String name;

    public Product(long id, String typeTag, String rfidUnit, String name) {
        this.id = id;
        this.typeTag = emptyToNull(typeTag);
        this.rfidUnit = emptyToNull(rfidUnit);
        this.name = name == null ? "" : name;
    }

    static String emptyToNull(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }
}
