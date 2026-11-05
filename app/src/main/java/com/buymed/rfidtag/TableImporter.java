package com.buymed.rfidtag;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Imports product rows from CSV / TSV / XLSX. The first row must be a header; columns are
 * found by name (product_id, type_tag, type_tag_label, rfid_unit_name, product_name and a few
 * aliases), so extra or reordered columns are fine.
 *
 * Kept free of Android classes except the XmlPullParser interface, so it can be tested on a JVM.
 */
public final class TableImporter {

    public interface PullParserFactory {
        XmlPullParser newParser() throws Exception;
    }

    public interface Progress {
        void onRows(int rows);
    }

    /** Result of an import: products keyed by id, plus counts for the confirmation dialog. */
    public static final class Result {
        public final LinkedHashMap<Long, Product> products = new LinkedHashMap<Long, Product>();
        public int skipped;
        public int temThuong;
        public int temGap;
        public int unconfigured;
    }

    private TableImporter() {}

    // ---------------------------------------------------------------- header mapping

    static String normKey(String h) {
        if (h == null) return "";
        StringBuilder b = new StringBuilder();
        String s = h.toLowerCase();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) b.append(c);
        }
        return b.toString();
    }

    /** Maps a header row to column indexes. */
    public static final class Columns {
        int id = -1, type = -1, label = -1, unit = -1, name = -1;

        static Columns of(List<String> header) {
            Columns c = new Columns();
            for (int i = 0; i < header.size(); i++) {
                String k = normKey(header.get(i));
                if (c.id < 0 && (k.equals("productid") || k.equals("id"))) c.id = i;
                else if (c.type < 0 && (k.equals("typetag") || k.equals("rfidtagtype") || k.equals("tagtype"))) c.type = i;
                else if (c.label < 0 && k.equals("typetaglabel")) c.label = i;
                else if (c.unit < 0 && (k.equals("rfidunitname") || k.equals("rfidunit") || k.equals("unit"))) c.unit = i;
                else if (c.name < 0 && (k.equals("productname") || k.equals("name"))) c.name = i;
            }
            return c;
        }
    }

    static String typeFromLabel(String label) {
        if (label == null) return null;
        String l = label.trim().toLowerCase();
        if (l.equals("tem thường") || l.equals("tem thuong")) return "temthuong";
        if (l.equals("tem gập") || l.equals("tem gap")) return "temgap";
        return null;
    }

    static Long parseId(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            try {
                double d = Double.parseDouble(s);
                if (d > 0 && d == Math.rint(d)) return (long) d;
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** Builds a product from raw field values and adds it; shared by every input format. */
    static void add(Result r, String id, String type, String label, String unit, String name) {
        Long pid = parseId(id);
        if (pid == null || r.products.containsKey(pid)) {
            r.skipped++;
            return;
        }
        String t = Product.emptyToNull(type);
        if (t != null) t = t.toLowerCase();
        if (t == null) t = typeFromLabel(label);
        Product p = new Product(pid, t, collapse(unit), collapse(name));
        r.products.put(pid, p);
        if ("temthuong".equals(p.typeTag)) r.temThuong++;
        else if ("temgap".equals(p.typeTag)) r.temGap++;
        else r.unconfigured++;
    }

    static String collapse(String s) {
        if (s == null) return null;
        StringBuilder b = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                space = b.length() > 0;
            } else {
                if (space) b.append(' ');
                space = false;
                b.append(c);
            }
        }
        return b.toString();
    }

    private static String cell(List<String> row, int i) {
        return i >= 0 && i < row.size() ? row.get(i) : null;
    }

    private static void addRow(Result r, Columns c, List<String> row) {
        boolean blank = true;
        for (String v : row) if (v != null && !v.trim().isEmpty()) { blank = false; break; }
        if (blank) return;
        add(r, cell(row, c.id), cell(row, c.type), cell(row, c.label), cell(row, c.unit), cell(row, c.name));
    }

    // ---------------------------------------------------------------- CSV / TSV

    public static Result readDelimited(InputStream in, Progress progress) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, ProductStore.UTF8), 1 << 16);
        try {
            String first = br.readLine();
            if (first == null) throw new IOException("File rỗng");
            if (first.length() > 0 && first.charAt(0) == '﻿') first = first.substring(1);
            char delim = pickDelimiter(first);
            List<String> header = splitLine(first, delim, br);
            Columns c = Columns.of(header);
            Result r = new Result();
            if (c.id < 0) {
                // No header: assume the app's own column order (id, type, unit, name).
                c.id = 0; c.type = 1; c.unit = 2; c.name = 3;
                addRow(r, c, header);
            }
            String line;
            int n = 0;
            while ((line = br.readLine()) != null) {
                addRow(r, c, splitLine(line, delim, br));
                if (progress != null && (++n % 5000) == 0) progress.onRows(n);
            }
            return r;
        } finally {
            br.close();
        }
    }

    static char pickDelimiter(String header) {
        int tabs = count(header, '\t'), commas = count(header, ','), semis = count(header, ';');
        if (tabs >= commas && tabs >= semis && tabs > 0) return '\t';
        if (semis > commas) return ';';
        return ',';
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    /** RFC 4180 split; a quoted field may run over several physical lines. */
    static List<String> splitLine(String line, char delim, BufferedReader more) throws IOException {
        ArrayList<String> out = new ArrayList<String>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        while (true) {
            if (i >= line.length()) {
                if (quoted && more != null) {
                    String next = more.readLine();
                    if (next != null) {
                        f.append('\n');
                        line = next;
                        i = 0;
                        continue;
                    }
                }
                break;
            }
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        f.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    f.append(ch);
                }
            } else if (ch == '"' && f.length() == 0) {
                quoted = true;
            } else if (ch == delim) {
                out.add(f.toString());
                f.setLength(0);
            } else {
                f.append(ch);
            }
            i++;
        }
        out.add(f.toString());
        return out;
    }

    // ---------------------------------------------------------------- XLSX

    public static Result readXlsx(File file, PullParserFactory factory, Progress progress) throws Exception {
        ZipFile zip = new ZipFile(file);
        try {
            List<String> shared = readSharedStrings(zip, factory);
            String sheetPath = firstSheetPath(zip, factory);
            ZipEntry sheet = zip.getEntry(sheetPath);
            if (sheet == null) throw new IOException("Không tìm thấy sheet trong file Excel");

            final Result r = new Result();
            XmlPullParser x = factory.newParser();
            InputStream in = zip.getInputStream(sheet);
            try {
                x.setInput(in, "UTF-8");
                Columns cols = null;
                ArrayList<String> row = new ArrayList<String>();
                String cellType = null;
                int cellCol = -1;
                StringBuilder value = new StringBuilder();
                boolean inValue = false;
                int rows = 0;
                for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                    if (ev == XmlPullParser.START_TAG) {
                        String n = local(x.getName());
                        if (n.equals("row")) {
                            row.clear();
                        } else if (n.equals("c")) {
                            cellType = x.getAttributeValue(null, "t");
                            cellCol = columnIndex(x.getAttributeValue(null, "r"), row.size());
                            value.setLength(0);
                        } else if (n.equals("v") || n.equals("t")) {
                            inValue = true;
                        }
                    } else if (ev == XmlPullParser.TEXT) {
                        if (inValue) value.append(x.getText());
                    } else if (ev == XmlPullParser.END_TAG) {
                        String n = local(x.getName());
                        if (n.equals("v") || n.equals("t")) {
                            inValue = false;
                        } else if (n.equals("c")) {
                            String v = value.toString();
                            if ("s".equals(cellType)) {
                                Long idx = parseId(v);
                                v = idx != null && idx >= 0 && idx < shared.size() ? shared.get(idx.intValue()) : "";
                            } else if ("b".equals(cellType)) {
                                v = "1".equals(v) ? "TRUE" : "FALSE";
                            }
                            while (row.size() < cellCol) row.add(null);
                            if (row.size() == cellCol) row.add(v); else row.set(cellCol, v);
                        } else if (n.equals("row")) {
                            if (cols == null) {
                                cols = Columns.of(row);
                                if (cols.id < 0) throw new IOException("Dòng đầu của sheet phải có cột product_id");
                            } else {
                                addRow(r, cols, row);
                                if (progress != null && (++rows % 5000) == 0) progress.onRows(rows);
                            }
                        }
                    }
                }
            } finally {
                in.close();
            }
            return r;
        } finally {
            zip.close();
        }
    }

    private static String local(String name) {
        int c = name.indexOf(':');
        return c >= 0 ? name.substring(c + 1) : name;
    }

    /** "C12" -> 2. Falls back to the next position when the attribute is missing. */
    static int columnIndex(String ref, int fallback) {
        if (ref == null || ref.isEmpty()) return fallback;
        int col = 0, i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        return i == 0 ? fallback : col - 1;
    }

    private static List<String> readSharedStrings(ZipFile zip, PullParserFactory factory) throws Exception {
        ArrayList<String> out = new ArrayList<String>();
        ZipEntry e = zip.getEntry("xl/sharedStrings.xml");
        if (e == null) return out;
        XmlPullParser x = factory.newParser();
        InputStream in = zip.getInputStream(e);
        try {
            x.setInput(in, "UTF-8");
            StringBuilder cur = null;
            boolean inT = false, inRph = false;
            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    String n = local(x.getName());
                    if (n.equals("si")) cur = new StringBuilder();
                    else if (n.equals("rPh")) inRph = true;
                    else if (n.equals("t") && !inRph) inT = true;
                } else if (ev == XmlPullParser.TEXT) {
                    if (inT && cur != null) cur.append(x.getText());
                } else if (ev == XmlPullParser.END_TAG) {
                    String n = local(x.getName());
                    if (n.equals("t")) inT = false;
                    else if (n.equals("rPh")) inRph = false;
                    else if (n.equals("si")) {
                        out.add(cur == null ? "" : cur.toString());
                        cur = null;
                    }
                }
            }
        } finally {
            in.close();
        }
        return out;
    }

    private static String firstSheetPath(ZipFile zip, PullParserFactory factory) throws Exception {
        String fallback = "xl/worksheets/sheet1.xml";
        ZipEntry wb = zip.getEntry("xl/workbook.xml");
        ZipEntry rels = zip.getEntry("xl/_rels/workbook.xml.rels");
        if (wb == null || rels == null) return fallback;

        String rid = null;
        XmlPullParser x = factory.newParser();
        InputStream in = zip.getInputStream(wb);
        try {
            x.setInput(in, "UTF-8");
            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT && rid == null; ev = x.next()) {
                if (ev == XmlPullParser.START_TAG && local(x.getName()).equals("sheet")) {
                    for (int i = 0; i < x.getAttributeCount(); i++) {
                        if (local(x.getAttributeName(i)).equals("id")) rid = x.getAttributeValue(i);
                    }
                }
            }
        } finally {
            in.close();
        }
        if (rid == null) return fallback;

        Map<String, String> targets = new HashMap<String, String>();
        x = factory.newParser();
        in = zip.getInputStream(rels);
        try {
            x.setInput(in, "UTF-8");
            for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                if (ev == XmlPullParser.START_TAG && local(x.getName()).equals("Relationship")) {
                    targets.put(x.getAttributeValue(null, "Id"), x.getAttributeValue(null, "Target"));
                }
            }
        } finally {
            in.close();
        }
        String t = targets.get(rid);
        if (t == null) return fallback;
        if (t.startsWith("/")) return t.substring(1);
        return "xl/" + t;
    }
}
