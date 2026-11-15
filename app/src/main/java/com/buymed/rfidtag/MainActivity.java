package com.buymed.rfidtag;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scan screen. Input arrives three ways, all ending in handleScan():
 *  - keyboard-wedge scanners typing into the field and pressing Enter (default on most PDAs)
 *  - broadcast-intent scanners (Urovo, iData, Newland, Zebra DataWedge, Honeywell...)
 *  - the camera button
 */
public class MainActivity extends Activity {
    static final int REQ_CAMERA = 1;
    static final int MAX_HISTORY = 200;

    /** Broadcast actions used by common PDA scanner services, plus our own for DataWedge etc. */
    static final String[] SCAN_ACTIONS = {
            "com.buymed.rfidtag.SCAN",
            "android.intent.ACTION_DECODE_DATA",            // Urovo
            "com.android.server.scannerservice.broadcast",  // iData, Kaicom
            "nlscan.action.SCANNER_RESULT",                 // Newland
            "scan.rcv.message",                             // Urovo (older), Seuic
            "com.scanner.broadcast",                        // Chainway, generic
            "com.honeywell.decode.intent.action.EDIT_DATA", // Honeywell data intent
    };
    static final String[] SCAN_EXTRAS = {
            "barcode_string", "scannerdata", "SCAN_BARCODE1", "data", "value", "barcode",
            "com.symbol.datawedge.data_string", "barcodeData", "decode_rslt", "scanResult", "barocode",
    };

    private RfidApp app;
    private EditText input;
    private TextView dataInfo, modeBadge, storeBar, resultTag, resultSub, productName, productMeta, codeMeta,
            stockMeta, sourceMeta, warnText, moreHint, counters;
    private View resultCard, productPanel;
    private HistoryAdapter adapter;
    private final ArrayList<LookupResult> history = new ArrayList<LookupResult>();
    private int cntThuong, cntGap, cntOther;
    /** Scan resolution; kept apart from {@link #net} so a slow stock call never delays the tag type. */
    private ExecutorService exec;
    /** Stock + product details. */
    private ExecutorService net;
    private Handler ui;
    /** Result shown on the card/panel; null while a new scan is being looked up. Read by {@link #net}. */
    private volatile LookupResult current;
    private ScanDetailDialog popup;
    /** Result the open popup shows, or null. Read by {@link #net}. */
    private volatile LookupResult popupFor;
    private boolean destroyed;
    private boolean storePrompted;
    private String shownStoreId;
    private Feedback feedback;
    private String lastText;
    private long lastAt;
    private boolean keyboardShown;
    private BroadcastReceiver scanReceiver;
    private BroadcastReceiver netReceiver;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = (RfidApp) getApplication();
        setContentView(R.layout.activity_main);
        ui = new Handler(Looper.getMainLooper());
        exec = Executors.newSingleThreadExecutor();
        net = Executors.newSingleThreadExecutor();
        feedback = new Feedback(this);

        input = (EditText) findViewById(R.id.input);
        dataInfo = (TextView) findViewById(R.id.data_info);
        modeBadge = (TextView) findViewById(R.id.mode_badge);
        storeBar = (TextView) findViewById(R.id.store_bar);
        resultCard = findViewById(R.id.result_card);
        resultTag = (TextView) findViewById(R.id.result_tag);
        resultSub = (TextView) findViewById(R.id.result_sub);
        productPanel = findViewById(R.id.product_panel);
        productName = (TextView) findViewById(R.id.product_name);
        productMeta = (TextView) findViewById(R.id.product_meta);
        codeMeta = (TextView) findViewById(R.id.code_meta);
        stockMeta = (TextView) findViewById(R.id.stock_meta);
        sourceMeta = (TextView) findViewById(R.id.source_meta);
        warnText = (TextView) findViewById(R.id.warn_text);
        moreHint = (TextView) findViewById(R.id.more_hint);
        counters = (TextView) findViewById(R.id.counters);
        ListView list = (ListView) findViewById(R.id.history);
        adapter = new HistoryAdapter(this, history);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < history.size()) openPopup(history.get(position));
            }
        });
        productPanel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (current != null) openPopup(current); }
        });
        storeBar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Class<?> target = app.settings().hasCircaKey() ? StoreActivity.class : SettingsActivity.class;
                startActivity(new Intent(MainActivity.this, target));
            }
        });

        if (Build.VERSION.SDK_INT >= 21) input.setShowSoftInputOnFocus(false);
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (event != null && event.getAction() != KeyEvent.ACTION_DOWN) return true;
                submitInput();
                return true;
            }
        });
        input.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                        || keyCode == KeyEvent.KEYCODE_TAB) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) submitInput();
                    return true;
                }
                return false;
            }
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                // Scanners that "type" a newline character instead of pressing Enter.
                for (int i = 0; i < s.length(); i++) {
                    char ch = s.charAt(i);
                    if (ch == '\n' || ch == '\r') {
                        ui.post(new Runnable() { @Override public void run() { submitInput(); } });
                        return;
                    }
                }
            }
        });

        click(R.id.btn_go, new Runnable() { @Override public void run() { submitInput(); } });
        click(R.id.btn_keyboard, new Runnable() { @Override public void run() { toggleKeyboard(); } });
        click(R.id.btn_camera, new Runnable() {
            @Override public void run() {
                startActivityForResult(new Intent(MainActivity.this, CameraScanActivity.class), REQ_CAMERA);
            }
        });
        click(R.id.btn_settings, new Runnable() {
            @Override public void run() { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
        });
        click(R.id.btn_clear, new Runnable() {
            @Override public void run() {
                history.clear();
                cntThuong = cntGap = cntOther = 0;
                adapter.notifyDataSetChanged();
                updateCounters();
            }
        });

        showIdle();
        updateCounters();
        waitForData();
    }

    private void click(int id, final Runnable action) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { action.run(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHeader();
        registerScanner();
        registerNetworkWatch();
        input.requestFocus();
        promptStoreIfNeeded();
        // Back from "Chọn kho" with another store: the stock on screen belongs to the old one.
        String store = app.settings().storeId();
        if (shownStoreId != null && !shownStoreId.equals(store) && current != null && current.hasProduct()) {
            loadExtra(current, true);
        }
        shownStoreId = store;
    }

    /** New flow: open the app -> pick the Circa store once -> scan. Asked once per screen. */
    private void promptStoreIfNeeded() {
        Settings s = app.settings();
        if (!storePrompted && s.hasCircaKey() && !s.hasStore()) {
            storePrompted = true;
            startActivity(new Intent(this, StoreActivity.class));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (scanReceiver != null) {
            try { unregisterReceiver(scanReceiver); } catch (IllegalArgumentException ignored) {}
            scanReceiver = null;
        }
        if (netReceiver != null) {
            try { unregisterReceiver(netReceiver); } catch (IllegalArgumentException ignored) {}
            netReceiver = null;
        }
    }

    /** Keeps the ONLINE badge honest when Wi-Fi / mobile data goes on or off. */
    private void registerNetworkWatch() {
        netReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) { refreshHeader(); }
        };
        IntentFilter f = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(netReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(netReceiver, f);
        }
    }

    private boolean hasNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (SecurityException e) {
            return true; // cannot tell; do not claim we are offline
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // A lookup may still finish after this; show()/loadExtra() check the flag.
        destroyed = true;
        if (popup != null) popup.dismiss();
        exec.shutdownNow();
        net.shutdownNow();
        feedback.release();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAMERA && resultCode == RESULT_OK && data != null) {
            String text = data.getStringExtra(CameraScanActivity.EXTRA_TEXT);
            if (text != null) handleScan(text);
        }
    }

    // ------------------------------------------------------------------ input

    private void submitInput() {
        String t = input.getText().toString();
        input.setText("");
        handleScan(t);
    }

    private void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        input.requestFocus();
        keyboardShown = !keyboardShown;
        if (Build.VERSION.SDK_INT >= 21) input.setShowSoftInputOnFocus(keyboardShown);
        if (keyboardShown) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        else imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private void registerScanner() {
        IntentFilter f = new IntentFilter();
        for (String a : SCAN_ACTIONS) f.addAction(a);
        final String custom = app.settings().scanAction();
        if (!custom.isEmpty()) f.addAction(custom);
        final String customExtra = app.settings().scanExtra();
        scanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String text = extractScan(intent, customExtra);
                if (text != null) handleScan(text);
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(scanReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(scanReceiver, f);
        }
    }

    static String extractScan(Intent intent, String customExtra) {
        Bundle b = intent.getExtras();
        if (b == null) return null;
        if (!customExtra.isEmpty()) {
            String s = asText(b.get(customExtra));
            if (s != null) return s;
        }
        for (String k : SCAN_EXTRAS) {
            String s = asText(b.get(k));
            if (s != null) return s;
        }
        return null;
    }

    private static String asText(Object o) {
        if (o instanceof String) return ((String) o).isEmpty() ? null : (String) o;
        if (o instanceof byte[]) return new String((byte[]) o, ProductStore.UTF8);
        if (o instanceof CharSequence) return o.toString();
        return null;
    }

    // ------------------------------------------------------------------ lookup

    private void handleScan(final String raw) {
        String clean = QrParser.clean(raw);
        if (clean.isEmpty()) return;
        long now = System.currentTimeMillis();
        // A wedge and a broadcast can both deliver the same scan.
        if (clean.equals(lastText) && now - lastAt < 800) return;
        lastText = clean;
        lastAt = now;

        // A broadcast scanner keeps working while the popup is open; the new scan wins.
        if (popup != null) popup.dismiss();
        showBusy(clean);
        exec.execute(new Runnable() {
            @Override
            public void run() {
                LookupResult r;
                try {
                    r = app.resolver().resolve(raw);
                } catch (RuntimeException e) {
                    r = new LookupResult();
                    r.status = LookupResult.ERROR;
                    r.input = raw;
                    r.error = "Lỗi xử lý: " + e;
                }
                final LookupResult result = r;
                ui.post(new Runnable() { @Override public void run() { if (!destroyed) show(result); } });
            }
        });
    }

    private void waitForData() {
        exec.execute(new Runnable() {
            @Override public void run() {
                app.repo().awaitReady(60000);
                ui.post(new Runnable() { @Override public void run() { refreshHeader(); } });
            }
        });
    }

    // ------------------------------------------------------------------ rendering

    private void refreshHeader() {
        ProductRepository repo = app.repo();
        if (!repo.isReady()) {
            dataInfo.setText("Đang tải dữ liệu…");
        } else if (repo.loadError() != null) {
            dataInfo.setText(repo.loadError());
        } else {
            dataInfo.setText(String.format(Locale.US, "%,d", repo.size()).replace(',', '.') + " SP · " + repo.sourceLabel());
        }
        Settings s = app.settings();
        if (s.canCallApi() && !hasNetwork()) {
            tint(modeBadge, 0xFFB3261E);
            modeBadge.setText("MẤT MẠNG");
        } else if (s.canCallApi()) {
            tint(modeBadge, 0xFF187A34);
            modeBadge.setText("ONLINE");
        } else {
            tint(modeBadge, 0xFF6B7780);
            modeBadge.setText("OFFLINE");
        }
        if (!s.hasCircaKey()) {
            storeBar.setText("⚠ Chưa có khóa API Circa — chạm để cài đặt");
        } else if (!s.hasStore()) {
            storeBar.setText("⚠ Chưa chọn kho — chạm để chọn  ›");
        } else {
            storeBar.setText("Kho: " + s.storeLabel() + "  ›");
        }
    }

    private static void tint(View v, int color) {
        ((GradientDrawable) v.getBackground().mutate()).setColor(color);
    }

    private void setCard(int color, String tag, String sub, boolean subBold) {
        tint(resultCard, color);
        resultTag.setText(tag);
        resultSub.setText(sub);
        resultSub.setVisibility(sub == null || sub.isEmpty() ? View.GONE : View.VISIBLE);
        resultSub.setTypeface(null, subBold ? Typeface.BOLD : Typeface.NORMAL);
        resultSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, subBold ? 17 : 15);
    }

    private void showIdle() {
        setCard(0xFF1F5FA8, "SẴN SÀNG", "Quét mã QR trên thùng / hộp, hoặc nhập product ID", false);
        productPanel.setVisibility(View.GONE);
    }

    private void showBusy(String text) {
        setCard(0xFF4B5563, "ĐANG TRA…", text, false);
        // The panel still shows the previous product; do not let a tap open it as if it were this scan.
        current = null;
    }

    private void show(LookupResult r) {
        // A new result replaces whatever popup was open (e.g. one opened while "ĐANG TRA…").
        if (popup != null) popup.dismiss();
        refreshHeader();
        if (r.status == LookupResult.ERROR) {
            setCard(r.color(), r.headline(), r.error, false);
        } else if (r.status == LookupResult.CONFIG) {
            setCard(r.color(), r.headline(), r.source, false);
        } else {
            setCard(r.color(), r.headline(),
                    r.rfidUnit != null ? "Dán tem trên: " + r.rfidUnit.toUpperCase(Locale.ROOT) : "", true);
        }
        current = r;
        renderPanel(r);

        if (r.status == LookupResult.TEM_THUONG) cntThuong++;
        else if (r.status == LookupResult.TEM_GAP) cntGap++;
        else if (r.status != LookupResult.CONFIG) cntOther++;
        history.add(0, r);
        while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
        adapter.notifyDataSetChanged();
        updateCounters();
        feedback.play(r, app.settings());
        input.requestFocus();

        if (r.hasProduct()) loadExtra(r, false);
        if (r.status == LookupResult.CONFIG) {
            // A settings QR may have brought a Circa key; continue with picking the store.
            storePrompted = false;
            promptStoreIfNeeded();
        }
    }

    /** The white panel under the tag card: product, IDs, QR / lot, stock, source, warning. */
    private void renderPanel(LookupResult r) {
        if (r.status == LookupResult.CONFIG) {
            productPanel.setVisibility(View.GONE);
            return;
        }
        productPanel.setVisibility(View.VISIBLE);
        String name = r.displayName();
        productName.setText(name != null ? name : r.status == LookupResult.ERROR ? "Không tra được mã" : r.headline());

        StringBuilder meta = new StringBuilder();
        if (r.productId >= 0) meta.append("ID ").append(r.productId);
        if (r.lot != null) meta.append(meta.length() > 0 ? "  ·  " : "").append("Lô ").append(r.lot);
        if (r.expiry != null) meta.append(meta.length() > 0 ? "  ·  " : "").append("HSD ").append(r.expiry);
        productMeta.setText(meta);
        productMeta.setVisibility(meta.length() > 0 ? View.VISIBLE : View.GONE);

        codeMeta.setText(r.code != null ? "QR " + r.code : r.input != null ? "Đầu vào: " + r.input : "");

        String stock = r.hasProduct() ? r.stockSummary() : null;
        if (stock != null) {
            boolean bad = r.stock == null && !r.stockLoading;
            // A failed refresh keeps the last numbers of the same store; say they are not fresh.
            boolean stale = r.stock != null && !r.stockLoading && r.stockError != null;
            stockMeta.setText("Tồn kho: " + stock
                    + (stale ? "  (chưa làm mới được, số lúc " + DateFmt.time(r.stock.fetchedAt) + ")" : ""));
            stockMeta.setTextColor(bad ? 0xFFB3261E : stale ? 0xFFB45309 : 0xFF1F2937);
            stockMeta.setVisibility(View.VISIBLE);
        } else {
            stockMeta.setVisibility(View.GONE);
        }

        sourceMeta.setText(r.source != null ? "Nguồn: " + r.source + "  ·  " + r.elapsedMs + " ms" : "");
        sourceMeta.setVisibility(r.source != null ? View.VISIBLE : View.GONE);
        // The error itself is on the card; the panel only repeats warnings.
        if (r.warning != null) {
            warnText.setText("⚠ " + r.warning);
            warnText.setVisibility(View.VISIBLE);
        } else {
            warnText.setVisibility(View.GONE);
        }
        moreHint.setVisibility(r.hasProduct() ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ stock + popup

    /** Fetches stock and product details off the UI thread, then refreshes every view showing {@code r}. */
    private void loadExtra(final LookupResult r, final boolean force) {
        if (destroyed) return;
        // Numbers from another store must never be shown as the current store's stock.
        dropStockOfOtherStore(r);
        r.stockLoading = true;
        r.stockError = null;
        refreshViewsFor(r);
        net.execute(new Runnable() {
            @Override public void run() {
                // Scans queue up on a slow network; skip results that are no longer on screen.
                if (!force && r != current && r != popupFor) {
                    ui.post(new Runnable() {
                        @Override public void run() { r.stockLoading = false; refreshViewsFor(r); }
                    });
                    return;
                }
                Resolver.Extra fetched;
                try {
                    fetched = app.resolver().fetchExtra(r.productId, force);
                } catch (RuntimeException e) {
                    fetched = new Resolver.Extra();
                    fetched.error = "Lỗi khi lấy tồn kho: " + e;
                }
                final Resolver.Extra x = fetched;
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (destroyed) return;
                        r.stockLoading = false;
                        if (x.detail != null) r.detail = x.detail;
                        if (x.stock != null) r.stock = x.stock;
                        // The store may have changed while this was in flight.
                        dropStockOfOtherStore(r);
                        r.stockError = x.error;
                        refreshViewsFor(r);
                    }
                });
            }
        });
    }

    private void dropStockOfOtherStore(LookupResult r) {
        if (r.stock != null && !r.stock.storeId.equals(app.settings().storeId())) r.stock = null;
    }

    private void refreshViewsFor(LookupResult r) {
        if (r == current) renderPanel(r);
        adapter.notifyDataSetChanged();
        if (popup != null && popup.isShowing() && popup.result() == r) popup.render();
    }

    private void openPopup(LookupResult r) {
        if (popup != null) popup.dismiss();
        popup = new ScanDetailDialog(this, r, new ScanDetailDialog.Host() {
            @Override public void loadExtra(LookupResult res, boolean force) { MainActivity.this.loadExtra(res, force); }
            @Override public void popupClosed(LookupResult res) {
                if (popupFor == res) popupFor = null;
                input.requestFocus();
            }
        });
        popupFor = r;
        popup.show();
        input.requestFocus();
        // An old scan opened from the history may never have had its stock fetched (or it failed).
        if (r.hasProduct() && r.stock == null && !r.stockLoading) loadExtra(r, false);
    }

    @Override
    public void onBackPressed() {
        // The popup does not take key focus (so scanners keep working), so Back arrives here.
        if (popup != null && popup.isShowing()) {
            popup.dismiss();
            return;
        }
        super.onBackPressed();
    }

    private void updateCounters() {
        counters.setText("Thường " + cntThuong + "  ·  Gập " + cntGap + "  ·  Khác/lỗi " + cntOther);
    }
}
