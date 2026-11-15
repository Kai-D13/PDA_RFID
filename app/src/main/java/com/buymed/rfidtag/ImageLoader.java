package com.buymed.rfidtag;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads product images (public CDN, https only) for the detail popup, downsampled to the
 * view size and kept in a small memory cache. No disk cache: images are only shown on demand.
 */
final class ImageLoader {
    interface Callback {
        /** On the UI thread; {@code bitmap} is null when loading failed. */
        void onImage(String url, Bitmap bitmap, String error);
    }

    private static final int MAX_BYTES = 8 * 1024 * 1024;
    /** 1/8 of the heap, at most 8 MB: a ~720 px ARGB image is ~2 MB, so a few fit even on old PDAs. */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(
            (int) Math.min(8L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private ImageLoader() {}

    static Bitmap cached(String url) {
        return CACHE.get(url);
    }

    static void load(final String url, final int targetPx, final Callback cb) {
        Bitmap hit = CACHE.get(url);
        if (hit != null) {
            cb.onImage(url, hit, null);
            return;
        }
        EXEC.execute(new Runnable() {
            @Override public void run() {
                Bitmap bmp = null;
                String err = null;
                try {
                    bmp = fetch(url, targetPx);
                    if (bmp == null) err = "Không đọc được ảnh";
                    else CACHE.put(url, bmp);
                } catch (IOException e) {
                    err = "Không tải được ảnh (" + e.getClass().getSimpleName() + ")";
                } catch (RuntimeException e) {
                    err = "Không tải được ảnh (" + e.getClass().getSimpleName() + ")";
                } catch (OutOfMemoryError e) {
                    err = "Ảnh quá lớn";
                }
                final Bitmap b = bmp;
                final String m = err;
                UI.post(new Runnable() { @Override public void run() { cb.onImage(url, b, m); } });
            }
        });
    }

    private static Bitmap fetch(String url, int targetPx) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("not https");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(15000);
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            byte[] data = readAll(c.getInputStream());
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= targetPx) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample; // keep ARGB_8888: product PNGs often have transparent backgrounds
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, d);
            if (bmp == null) return null;
            // inSampleSize only halves, so the result can be up to 2x the target: scale the rest.
            int longest = Math.max(bmp.getWidth(), bmp.getHeight());
            if (longest > targetPx) {
                float k = (float) targetPx / longest;
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * k)),
                        Math.max(1, Math.round(bmp.getHeight() * k)), true);
                if (scaled != bmp) bmp.recycle();
                bmp = scaled;
            }
            return bmp;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > MAX_BYTES) throw new IOException("image too large");
            }
        } finally {
            in.close();
        }
        return b.toByteArray();
    }
}
