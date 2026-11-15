package com.buymed.rfidtag;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Remembers what the API said, so the same carton is never looked up twice.
 *
 *  qr_cache:    QR code -> product id (exact, always safe), plus SKU / lot / expiry / unit when
 *               the code was resolved through Circa
 *  batch_cache: (mint timestamp, machine code) -> product id. Codes minted by one generate call
 *               share the 13-digit timestamp and the machine code, so once one code of a batch is
 *               known the rest can be resolved offline. Only used when the setting is on.
 */
public final class ScanCache extends SQLiteOpenHelper {

    /** A cached QR code. Fields other than productId may be null (Buymed lookups carry none). */
    public static final class Hit {
        public long productId;
        public String sku;
        public String lot;
        public String expiry;
        public String unit;
    }

    public ScanCache(Context ctx) {
        super(ctx, "scan_cache.db", null, 2);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE qr_cache (code TEXT PRIMARY KEY, product_id INTEGER NOT NULL, created INTEGER NOT NULL,"
                + " sku TEXT, lot TEXT, expiry TEXT, unit TEXT)");
        db.execSQL("CREATE TABLE batch_cache (ts TEXT NOT NULL, machine TEXT NOT NULL, product_id INTEGER NOT NULL,"
                + " created INTEGER NOT NULL, PRIMARY KEY (ts, machine))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1) {
            // v1 -> v2: keep the codes already looked up, just add the Circa columns.
            db.execSQL("ALTER TABLE qr_cache ADD COLUMN sku TEXT");
            db.execSQL("ALTER TABLE qr_cache ADD COLUMN lot TEXT");
            db.execSQL("ALTER TABLE qr_cache ADD COLUMN expiry TEXT");
            db.execSQL("ALTER TABLE qr_cache ADD COLUMN unit TEXT");
            return;
        }
        db.execSQL("DROP TABLE IF EXISTS qr_cache");
        db.execSQL("DROP TABLE IF EXISTS batch_cache");
        onCreate(db);
    }

    public Hit lookup(String code) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT product_id, sku, lot, expiry, unit FROM qr_cache WHERE code = ?", new String[]{code});
        try {
            if (!c.moveToFirst()) return null;
            Hit h = new Hit();
            h.productId = c.getLong(0);
            h.sku = c.getString(1);
            h.lot = c.getString(2);
            h.expiry = c.getString(3);
            h.unit = c.getString(4);
            return h;
        } finally {
            c.close();
        }
    }

    /** Caches what Circa said about a code. */
    public void rememberCirca(String code, CircaClient.QrInfo q) {
        ContentValues v = new ContentValues();
        v.put("code", code);
        v.put("product_id", q.productId);
        v.put("created", System.currentTimeMillis());
        v.put("sku", q.sku);
        v.put("lot", q.lot);
        v.put("expiry", q.expiry);
        v.put("unit", q.unit);
        getWritableDatabase().insertWithOnConflict("qr_cache", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Product of another code from the same mint batch, or -1 when unknown or ambiguous. */
    public long productIdForBatch(String code) {
        String ts = QrParser.batchTimestamp(code);
        String rest = QrParser.afterTimestamp(code);
        if (ts == null) return -1;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT machine, product_id FROM batch_cache WHERE ts = ?", new String[]{ts});
        long found = -1;
        try {
            while (c.moveToNext()) {
                String machine = c.getString(0);
                if (rest.length() > machine.length() && rest.startsWith(machine)) {
                    long pid = c.getLong(1);
                    if (found >= 0 && found != pid) return -1;
                    found = pid;
                }
            }
        } finally {
            c.close();
        }
        return found;
    }

    public void remember(String code, long productId, String machineCode) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        ContentValues v = new ContentValues();
        v.put("code", code);
        v.put("product_id", productId);
        v.put("created", now);
        db.insertWithOnConflict("qr_cache", null, v, SQLiteDatabase.CONFLICT_REPLACE);

        String ts = QrParser.batchTimestamp(code);
        String rest = QrParser.afterTimestamp(code);
        if (ts != null && machineCode != null && !machineCode.isEmpty()
                && rest.length() > machineCode.length() && rest.startsWith(machineCode)) {
            ContentValues b = new ContentValues();
            b.put("ts", ts);
            b.put("machine", machineCode);
            b.put("product_id", productId);
            b.put("created", now);
            db.insertWithOnConflict("batch_cache", null, b, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    public long countCodes() {
        return DatabaseUtils.queryNumEntries(getReadableDatabase(), "qr_cache");
    }

    public void clear() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("qr_cache", null, null);
        db.delete("batch_cache", null, null);
    }
}
