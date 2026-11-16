package com.buymed.rfidtag;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Popup with everything known about one scan: product image(s), name, pack size, IDs, QR / lot /
 * expiry, where the answer came from, and live stock per location and lot in the selected store.
 */
final class ScanDetailDialog {
    interface Host {
        /** Fetch stock + details for {@code r} and call {@link #render()} when they arrive. */
        void loadExtra(LookupResult r, boolean force);

        /** The popup was closed (button, Back, or a new scan). */
        void popupClosed(LookupResult r);
    }

    private static final int MAX_STOCK_ROWS = 80;

    private final Activity act;
    private final LookupResult r;
    private final Host host;
    private final Dialog dialog;
    private final View head;
    private final TextView tag, unit, name, volumes, warn, stockTotal, stockSub, nwTotal, nwSub, linesLabel,
            imageMsg, imageCount;
    private final ImageView image;
    private final LinearLayout info, stockLines;
    private int imageIndex;
    private String shownUrl;
    /** URL being downloaded, so repeated render() calls do not start the same download again. */
    private String loadingUrl;

    ScanDetailDialog(Activity act, LookupResult r, Host host) {
        this.act = act;
        this.r = r;
        this.host = host;
        dialog = new Dialog(act, R.style.PopupTheme);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_scan);
        head = dialog.findViewById(R.id.d_head);
        tag = (TextView) dialog.findViewById(R.id.d_tag);
        unit = (TextView) dialog.findViewById(R.id.d_unit);
        name = (TextView) dialog.findViewById(R.id.d_name);
        volumes = (TextView) dialog.findViewById(R.id.d_volumes);
        warn = (TextView) dialog.findViewById(R.id.d_warn);
        stockTotal = (TextView) dialog.findViewById(R.id.d_stock_total);
        stockSub = (TextView) dialog.findViewById(R.id.d_stock_sub);
        nwTotal = (TextView) dialog.findViewById(R.id.d_nw_total);
        nwSub = (TextView) dialog.findViewById(R.id.d_nw_sub);
        linesLabel = (TextView) dialog.findViewById(R.id.d_lines_label);
        imageMsg = (TextView) dialog.findViewById(R.id.d_image_msg);
        imageCount = (TextView) dialog.findViewById(R.id.d_image_count);
        image = (ImageView) dialog.findViewById(R.id.d_image);
        info = (LinearLayout) dialog.findViewById(R.id.d_info);
        stockLines = (LinearLayout) dialog.findViewById(R.id.d_stock_lines);

        View.OnClickListener close = new View.OnClickListener() {
            @Override public void onClick(View v) { dialog.dismiss(); }
        };
        dialog.findViewById(R.id.d_x).setOnClickListener(close);
        dialog.findViewById(R.id.d_close).setOnClickListener(close);
        dialog.findViewById(R.id.d_image_box).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                List<String> urls = images();
                if (urls.size() > 1) {
                    imageIndex = (imageIndex + 1) % urls.size();
                    renderImage();
                }
            }
        });
        dialog.findViewById(R.id.d_refresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (ScanDetailDialog.this.r.hasProduct()) ScanDetailDialog.this.host.loadExtra(ScanDetailDialog.this.r, true);
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface d) { ScanDetailDialog.this.host.popupClosed(ScanDetailDialog.this.r); }
        });
        // The popup never takes key focus: keyboard-wedge scanners (key events or IME "direct fill")
        // keep typing into the scan field of MainActivity behind it, which closes the popup and
        // looks the new code up. Back is handled by MainActivity.onBackPressed.
        Window w = dialog.getWindow();
        if (w != null) {
            w.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    void show() {
        render();
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    boolean isShowing() { return dialog.isShowing(); }
    LookupResult result() { return r; }
    void dismiss() { if (dialog.isShowing()) dialog.dismiss(); }

    // ------------------------------------------------------------------ rendering

    void render() {
        GradientDrawable bg = (GradientDrawable) head.getBackground().mutate();
        bg.setColor(r.color());
        tag.setText(r.headline());
        unit.setText(r.rfidUnit != null ? "Dán tem trên: " + r.rfidUnit.toUpperCase(Locale.ROOT)
                : r.status == LookupResult.ERROR ? "Quét lúc " + DateFmt.time(r.time) : "");
        unit.setVisibility(unit.getText().length() > 0 ? View.VISIBLE : View.GONE);

        String n = r.displayName();
        name.setText(n != null ? n : r.status == LookupResult.ERROR ? "Không tra được mã" : r.headline());
        String vol = r.detail != null ? r.detail.volumes : null;
        volumes.setText(vol != null ? "Quy cách: " + vol : "");
        volumes.setVisibility(vol != null ? View.VISIBLE : View.GONE);

        info.removeAllViews();
        if (r.productId >= 0) row("Product ID", String.valueOf(r.productId), false);
        row("SKU", r.sku, false);
        row("Mã QR", r.code, true);
        if (r.code == null && r.input != null && r.productId < 0) row("Đầu vào", r.input, true);
        row("Lô", r.lot, false);
        row("Hạn dùng", r.expiry, false);
        row("Đơn vị trên QR", r.qrUnit, false);
        String rfid = r.rfidUnit;
        CircaClient.Unit cu = r.detail != null ? r.detail.rfidUnit() : null;
        if (cu != null && (rfid == null || !rfid.equalsIgnoreCase(cu.name))) {
            rfid = (rfid != null ? rfid + " · " : "") + "Circa: " + cu.name;
        }
        row("Đơn vị dán tem", rfid, false);
        row("Số đăng ký", r.detail != null ? r.detail.registration : null, false);
        row("Quét lúc", new SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.US).format(new Date(r.time)), false);
        row("Kho lúc quét", r.storeLabel != null && !r.storeLabel.isEmpty() ? r.storeLabel : null, false);

        String w = r.status == LookupResult.ERROR ? r.error : r.warning;
        warn.setText(w != null ? "⚠ " + w : "");
        warn.setVisibility(w != null ? View.VISIBLE : View.GONE);

        renderStock();
        renderNw();
        renderImage();
    }

    private void row(String label, String value, boolean mono) {
        if (value == null || value.isEmpty()) return;
        TextView t = new TextView(act);
        SpannableStringBuilder b = new SpannableStringBuilder(label + ":  ");
        b.setSpan(new StyleSpan(Typeface.BOLD), 0, label.length() + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int start = b.length();
        b.append(value);
        // Monospace only the code itself; on the PDA a monospace label looked like a different row style.
        if (mono) b.setSpan(new TypefaceSpan("monospace"), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setText(b);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        t.setTextColor(0xFF374151);
        t.setPadding(0, dp(3), 0, dp(3));
        info.addView(t);
    }

    private void renderStock() {
        stockLines.removeAllViews();
        stockSub.setTextColor(0xFF6B7280);
        linesLabel.setVisibility(View.GONE);
        if (!r.hasProduct()) {
            stockTotal.setText("—");
            stockSub.setText("Chưa xác định được sản phẩm.");
            return;
        }
        CircaClient.Stock s = r.stock;
        if (s == null) {
            stockTotal.setText(r.stockLoading ? "Tồn POS: đang tải…" : "Tồn POS: chưa có số liệu");
            if (r.stockError != null) {
                stockSub.setText(r.stockError);
                stockSub.setTextColor(0xFFB3261E);
            } else {
                stockSub.setText("");
            }
            return;
        }
        stockTotal.setText("Tồn POS: " + r.stockSummary());
        StringBuilder sub = new StringBuilder();
        if (s.storeName != null && !s.storeName.isEmpty()) sub.append(s.storeName).append(" · ");
        sub.append("cập nhật ").append(DateFmt.time(s.fetchedAt));
        if (Math.abs(s.available - s.onHand) > 1e-9) sub.append(" · có thể bán ").append(CircaClient.qty(s.available));
        if (r.stockLoading) sub.append(" · đang làm mới…");
        if (r.stockError != null) sub.append("\nLàm mới lỗi: ").append(r.stockError);
        stockSub.setText(sub);

        int rows = 0;
        String unitName = s.baseUnit != null ? " " + s.baseUnit : "";
        for (CircaClient.SkuStock k : s.skus) {
            if (s.skus.size() > 1) {
                line("SKU " + k.sku + ": " + CircaClient.qty(k.onHand) + unitName, 14.5f, true, 0);
                rows++;
            }
            for (CircaClient.Location loc : k.locations) {
                if (rows >= MAX_STOCK_ROWS) break;
                String locName = loc.name != null ? loc.name : loc.code;
                if (loc.code != null && loc.name != null && !loc.code.equals(loc.name)) locName += " (" + loc.code + ")";
                line(locName + ": " + CircaClient.qty(loc.onHand) + unitName, 14.5f, true, dp(4));
                rows++;
                for (CircaClient.Lot lot : loc.lots) {
                    if (rows >= MAX_STOCK_ROWS) break;
                    StringBuilder t = new StringBuilder("Lô ").append(lot.lot != null ? lot.lot : "?")
                            .append(" · ").append(CircaClient.qty(lot.onHand)).append(unitName);
                    if (lot.doc != null) t.append(" · ").append(lot.doc);
                    line(t.toString(), 13.5f, false, dp(16));
                    rows++;
                }
            }
        }
        if (rows >= MAX_STOCK_ROWS) line("… còn nữa, xem đầy đủ trên Circa POS", 13f, false, 0);
        linesLabel.setVisibility(rows > 0 ? View.VISIBLE : View.GONE);
    }

    /** "Tồn NW: 31 thẻ · 93 cái" and where / when it was counted, or why there is no number. */
    private void renderNw() {
        nwSub.setTextColor(0xFF6B7280);
        if (!r.hasProduct()) {
            nwTotal.setVisibility(View.GONE);
            nwSub.setVisibility(View.GONE);
            return;
        }
        nwTotal.setVisibility(View.VISIBLE);
        nwSub.setVisibility(View.VISIBLE);
        NextwavesClient.EpcStock s = r.nw;
        if (s == null) {
            nwTotal.setText("Tồn NW: " + (r.nwLoading ? "đang đếm thẻ…" : r.nwSetup ? "chưa cấu hình" : "chưa có số liệu"));
            nwSub.setText(r.nwError != null ? r.nwError : "");
            if (r.nwError != null && !r.nwSetup) nwSub.setTextColor(0xFFB3261E);
            return;
        }
        nwTotal.setText("Tồn NW: " + NextwavesClient.line(s));
        String unitName = s.baseUnit != null ? " " + s.baseUnit : "";
        StringBuilder sub = new StringBuilder("Nextwaves ").append(NextwavesClient.warehouseLabel(s.warehouseId))
                .append(" · cập nhật ").append(DateFmt.time(s.fetchedAt));
        if (s.found) {
            if (s.consistent) {
                sub.append(" · khớp tổng NW");
            } else {
                sub.append("\nChưa khớp: NW ghi ").append(CircaClient.qty(s.rfidStock)).append(unitName)
                        .append(" trên thẻ, đếm sổ kho ra ").append(CircaClient.qty(s.epcQuantity)).append(unitName);
            }
            if (s.verified) sub.append(" · đã tra từng thẻ");
            if (s.untaggedStock > 0) sub.append(" · chưa gắn thẻ ").append(CircaClient.qty(s.untaggedStock)).append(unitName);
        }
        if (r.nwLoading) sub.append(" · đang làm mới…");
        if (r.nwError != null) sub.append("\nLàm mới lỗi: ").append(r.nwError);
        nwSub.setText(sub);
    }

    private void line(String text, float sp, boolean bold, int indentPx) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(bold ? 0xFF1F2937 : 0xFF4B5563);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(indentPx, dp(3), 0, dp(3));
        stockLines.addView(t);
    }

    private List<String> images() {
        if (!r.images.isEmpty()) return r.images;
        if (r.detail != null) return r.detail.images;
        return new ArrayList<String>();
    }

    private void renderImage() {
        List<String> urls = images();
        if (urls.isEmpty()) {
            shownUrl = null;
            image.setImageDrawable(null);
            imageCount.setVisibility(View.GONE);
            imageMsg.setVisibility(View.VISIBLE);
            imageMsg.setText(r.stockLoading ? "Đang tải ảnh…" : r.hasProduct() ? "Không có ảnh sản phẩm" : "");
            return;
        }
        if (imageIndex >= urls.size()) imageIndex = 0;
        imageCount.setText((imageIndex + 1) + "/" + urls.size() + (urls.size() > 1 ? " · chạm để xem tiếp" : ""));
        imageCount.setVisibility(View.VISIBLE);
        final String url = urls.get(imageIndex);
        if (url.equals(shownUrl) && (image.getDrawable() != null || url.equals(loadingUrl))) return;
        shownUrl = url;
        Bitmap hit = ImageLoader.cached(url);
        if (hit != null) {
            image.setImageBitmap(hit);
            imageMsg.setVisibility(View.GONE);
            return;
        }
        image.setImageDrawable(null);
        imageMsg.setVisibility(View.VISIBLE);
        imageMsg.setText("Đang tải ảnh…");
        loadingUrl = url;
        ImageLoader.load(url, 720, new ImageLoader.Callback() {
            @Override public void onImage(String u, Bitmap bmp, String error) {
                if (u.equals(loadingUrl)) loadingUrl = null;
                if (!u.equals(shownUrl) || !dialog.isShowing()) return;
                if (bmp != null) {
                    image.setImageBitmap(bmp);
                    imageMsg.setVisibility(View.GONE);
                } else {
                    imageMsg.setText(error);
                }
            }
        });
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, act.getResources().getDisplayMetrics());
    }
}
