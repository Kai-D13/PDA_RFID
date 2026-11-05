package com.buymed.rfidtag;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Local product -> tag-type table, held in memory. Uses the file imported on the device when
 * there is one, otherwise the copy bundled in the APK (assets/products.dat).
 */
public final class ProductRepository {
    /** gzip TSV; not named .gz because the build tools would silently unpack and rename it. */
    static final String ASSET = "products.dat";
    static final String IMPORTED = "products.tsv.gz";

    private volatile Map<Long, Product> map = Collections.emptyMap();
    private volatile String sourceLabel = "";
    private volatile String loadError;
    private volatile CountDownLatch ready = new CountDownLatch(1);

    public void loadAsync(final Context ctx) {
        new Thread(new Runnable() {
            @Override public void run() { load(ctx.getApplicationContext()); }
        }, "load-products").start();
    }

    synchronized void load(Context ctx) {
        CountDownLatch latch = ready;
        try {
            File f = importedFile(ctx);
            if (f.exists()) {
                InputStream in = new FileInputStream(f);
                map = ProductStore.read(in);
                sourceLabel = "Dữ liệu nhập " + DateFmt.dateTime(f.lastModified());
            } else {
                map = ProductStore.read(ctx.getAssets().open(ASSET));
                sourceLabel = "Dữ liệu gốc " + ctx.getString(R.string.builtin_data_date);
            }
            loadError = null;
        } catch (IOException e) {
            loadError = "Không đọc được dữ liệu: " + e.getMessage();
        } finally {
            latch.countDown();
        }
    }

    public boolean awaitReady(long ms) {
        try {
            return ready.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public boolean isReady() { return ready.getCount() == 0; }
    public Product get(long id) { return map.get(id); }
    public int size() { return map.size(); }
    public String sourceLabel() { return sourceLabel; }
    public String loadError() { return loadError; }

    /** Saves a freshly imported table to internal storage and swaps it in. */
    public synchronized void replace(Context ctx, Map<Long, Product> products) throws IOException {
        File target = importedFile(ctx);
        File tmp = new File(target.getPath() + ".tmp");
        Collection<Product> values = products.values();
        FileOutputStream out = new FileOutputStream(tmp);
        ProductStore.write(values, out);
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("Không ghi được file dữ liệu");
        }
        map = products;
        sourceLabel = "Dữ liệu nhập " + DateFmt.dateTime(target.lastModified());
        loadError = null;
    }

    /** Drops the imported file and goes back to the table bundled in the APK. */
    public synchronized void resetToBuiltIn(Context ctx) {
        importedFile(ctx).delete();
        ready = new CountDownLatch(1);
        load(ctx);
    }

    public boolean hasImported(Context ctx) { return importedFile(ctx).exists(); }

    private static File importedFile(Context ctx) {
        return new File(ctx.getFilesDir(), IMPORTED);
    }
}
