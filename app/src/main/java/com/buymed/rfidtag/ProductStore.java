package com.buymed.rfidtag;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Reads and writes the app's own data format: gzip'd TSV,
 * one line per product: product_id TAB type_tag TAB rfid_unit_name TAB product_name.
 */
public final class ProductStore {
    static final Charset UTF8 = Charset.forName("UTF-8");

    private ProductStore() {}

    public static Map<Long, Product> read(InputStream gz) throws IOException {
        HashMap<Long, Product> map = new HashMap<Long, Product>(100_000);
        HashMap<String, String> intern = new HashMap<String, String>();
        BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(gz, 1 << 16), UTF8), 1 << 16);
        try {
            String line;
            while ((line = r.readLine()) != null) {
                int t1 = line.indexOf('\t');
                if (t1 <= 0) continue;
                int t2 = line.indexOf('\t', t1 + 1);
                int t3 = t2 < 0 ? -1 : line.indexOf('\t', t2 + 1);
                long id;
                try {
                    id = Long.parseLong(line.substring(0, t1).trim());
                } catch (NumberFormatException e) {
                    continue; // header or junk line
                }
                String type = t2 < 0 ? line.substring(t1 + 1) : line.substring(t1 + 1, t2);
                String unit = t2 < 0 ? "" : (t3 < 0 ? line.substring(t2 + 1) : line.substring(t2 + 1, t3));
                String name = t3 < 0 ? "" : line.substring(t3 + 1);
                map.put(id, new Product(id, in(intern, type), in(intern, unit), name));
            }
        } finally {
            r.close();
        }
        return map;
    }

    public static void write(Collection<Product> products, OutputStream out) throws IOException {
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(out, 1 << 16), UTF8), 1 << 16);
        try {
            for (Product p : products) {
                w.write(Long.toString(p.id));
                w.write('\t');
                w.write(field(p.typeTag));
                w.write('\t');
                w.write(field(p.rfidUnit));
                w.write('\t');
                w.write(field(p.name));
                w.write('\n');
            }
        } finally {
            w.close();
        }
    }

    private static String field(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String in(Map<String, String> pool, String s) {
        s = s.trim();
        if (s.isEmpty()) return null;
        String hit = pool.get(s);
        if (hit != null) return hit;
        pool.put(s, s);
        return s;
    }
}
