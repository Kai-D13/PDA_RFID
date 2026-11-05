package com.buymed.rfidtag;

import android.content.Context;
import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

/**
 * Imports a data file picked on the device. The format is sniffed from the content, not the
 * name: XLSX (zip), gzip'd TSV (the app's own format, from tools/xlsx_to_tsv.py), JSON array of
 * objects, or plain CSV/TSV with a header row.
 */
final class DataImporter {
    private DataImporter() {}

    static TableImporter.Result importUri(Context ctx, Uri uri, TableImporter.Progress progress) throws Exception {
        File tmp = new File(ctx.getCacheDir(), "import.bin");
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("Không mở được file");
        copy(in, tmp);
        try {
            byte[] head = new byte[2];
            InputStream peek = new FileInputStream(tmp);
            int n = peek.read(head);
            peek.close();

            if (n == 2 && head[0] == 'P' && head[1] == 'K') {
                return TableImporter.readXlsx(tmp, new TableImporter.PullParserFactory() {
                    @Override public XmlPullParser newParser() { return Xml.newPullParser(); }
                }, progress);
            }
            InputStream data = new BufferedInputStream(new FileInputStream(tmp), 1 << 16);
            if (n == 2 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B) {
                data = new BufferedInputStream(new GZIPInputStream(data, 1 << 16), 1 << 16);
            }
            // Drop a UTF-8 byte-order mark, then peek at the first real character.
            data.mark(3);
            if (!(data.read() == 0xEF && data.read() == 0xBB && data.read() == 0xBF)) data.reset();
            data.mark(256);
            int first;
            do {
                first = data.read();
            } while (first == ' ' || first == '\n' || first == '\r' || first == '\t');
            data.reset();
            if (first == '[' || first == '{') return readJson(data, progress);
            return TableImporter.readDelimited(data, progress);
        } finally {
            tmp.delete();
        }
    }

    /** [{ "product_id": 1107, "type_tag": "temthuong", ... }, ...] or { "data": [ ... ] } */
    static TableImporter.Result readJson(InputStream in, TableImporter.Progress progress) throws IOException {
        TableImporter.Result r = new TableImporter.Result();
        JsonReader j = new JsonReader(new InputStreamReader(in, ProductStore.UTF8));
        try {
            if (j.peek() == JsonToken.BEGIN_OBJECT) {
                j.beginObject();
                boolean found = false;
                while (j.hasNext()) {
                    j.nextName();
                    if (!found && j.peek() == JsonToken.BEGIN_ARRAY) {
                        readArray(j, r, progress);
                        found = true;
                    } else {
                        j.skipValue();
                    }
                }
                j.endObject();
                if (!found) throw new IOException("JSON không có mảng sản phẩm");
            } else {
                readArray(j, r, progress);
            }
        } finally {
            j.close();
        }
        return r;
    }

    private static void readArray(JsonReader j, TableImporter.Result r, TableImporter.Progress progress) throws IOException {
        j.beginArray();
        int n = 0;
        while (j.hasNext()) {
            if (j.peek() != JsonToken.BEGIN_OBJECT) {
                j.skipValue();
                r.skipped++;
                continue;
            }
            String id = null, type = null, label = null, unit = null, name = null;
            j.beginObject();
            while (j.hasNext()) {
                String k = TableImporter.normKey(j.nextName());
                String v = value(j);
                if (k.equals("productid") || k.equals("id")) id = v;
                else if (k.equals("typetag") || k.equals("rfidtagtype") || k.equals("tagtype")) type = v;
                else if (k.equals("typetaglabel")) label = v;
                else if (k.equals("rfidunitname") || k.equals("rfidunit")) unit = v;
                else if (k.equals("productname") || k.equals("name")) name = v;
            }
            j.endObject();
            TableImporter.add(r, id, type, label, unit, name);
            if (progress != null && (++n % 5000) == 0) progress.onRows(n);
        }
        j.endArray();
    }

    private static String value(JsonReader j) throws IOException {
        JsonToken t = j.peek();
        if (t == JsonToken.NULL) {
            j.nextNull();
            return null;
        }
        if (t == JsonToken.STRING || t == JsonToken.NUMBER) return j.nextString();
        if (t == JsonToken.BOOLEAN) return String.valueOf(j.nextBoolean());
        j.skipValue();
        return null;
    }

    private static void copy(InputStream in, File dst) throws IOException {
        OutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }
}
