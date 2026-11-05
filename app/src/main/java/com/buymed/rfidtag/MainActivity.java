package com.buymed.rfidtag;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
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
    private TextView dataInfo, modeBadge, resultTag, resultSub, productName, productMeta, codeMeta, sourceMeta, warnText, counters;
    private View resultCard;
    private HistoryAdapter adapter;
    private final ArrayList<LookupResult> history = new ArrayList<LookupResult>();
    private int cntThuong, cntGap, cntOther;
    private ExecutorService exec;
    private Handler ui;
    private Feedback feedback;
    private String lastText;
    private long lastAt;
    private boolean keyboardShown;
    private BroadcastReceiver scanReceiver;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = (RfidApp) getApplication();
        setContentView(R.layout.activity_main);
        ui = new Handler(Looper.getMainLooper());
        exec = Executors.newSingleThreadExecutor();
        feedback = new Feedback(this);

        input = (EditText) findViewById(R.id.input);
        dataInfo = (TextView) findViewById(R.id.data_info);
        modeBadge = (TextView) findViewById(R.id.mode_badge);
        resultCard = findViewById(R.id.result_card);
        resultTag = (TextView) findViewById(R.id.result_tag);
        resultSub = (TextView) findViewById(R.id.result_sub);
        productName = (TextView) findViewById(R.id.product_name);
        productMeta = (TextView) findViewById(R.id.product_meta);
        codeMeta = (TextView) findViewById(R.id.code_meta);
        sourceMeta = (TextView) findViewById(R.id.source_meta);
        warnText = (TextView) findViewById(R.id.warn_text);
        counters = (TextView) findViewById(R.id.counters);
        ListView list = (ListView) findViewById(R.id.history);
        adapter = new HistoryAdapter(this, history);
        list.setAdapter(adapter);

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
        input.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (scanReceiver != null) {
            try { unregisterReceiver(scanReceiver); } catch (IllegalArgumentException ignored) {}
            scanReceiver = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdownNow();
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
                ui.post(new Runnable() { @Override public void run() { show(result); } });
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
        if (s.canCallApi()) {
            modeBadge.setText("ONLINE");
            modeBadge.setBackgroundColor(0xFF2E7D52);
        } else {
            modeBadge.setText("OFFLINE");
            modeBadge.setBackgroundColor(0xFF6B7780);
        }
    }

    private void setCard(int color, String tag, String sub) {
        GradientDrawable bg = (GradientDrawable) resultCard.getBackground().mutate();
        bg.setColor(color);
        resultTag.setText(tag);
        resultSub.setText(sub);
    }

    private void showIdle() {
        setCard(0xFF12313F, "SẴN SÀNG", "Quét mã QR trên thùng / hộp, hoặc nhập product ID");
        productName.setText("");
        productMeta.setText("");
        codeMeta.setText("");
        sourceMeta.setText("");
        warnText.setVisibility(View.GONE);
    }

    private void showBusy(String text) {
        setCard(0xFF33434D, "ĐANG TRA…", text);
    }

    private void show(LookupResult r) {
        refreshHeader();
        setCard(r.color(), r.headline(), r.status == LookupResult.ERROR ? r.error
                : r.rfidUnit != null ? "Dán tem trên: " + r.rfidUnit.toUpperCase() : r.status == LookupResult.CONFIG ? r.source : "");
        productName.setText(r.productName != null ? r.productName : "");
        productMeta.setText(r.productId >= 0 ? "Product ID: " + r.productId
                + (r.rfidUnit != null ? "   ·   Đơn vị dán tem: " + r.rfidUnit : "") : "");
        codeMeta.setText(r.code != null ? "Mã QR: " + r.code : r.input != null ? "Đầu vào: " + r.input : "");
        sourceMeta.setText(r.source != null && r.status != LookupResult.CONFIG
                ? "Nguồn: " + r.source + "   ·   " + r.elapsedMs + " ms" : "");
        if (r.warning != null) {
            warnText.setText("⚠ " + r.warning);
            warnText.setVisibility(View.VISIBLE);
        } else {
            warnText.setVisibility(View.GONE);
        }

        if (r.status == LookupResult.TEM_THUONG) cntThuong++;
        else if (r.status == LookupResult.TEM_GAP) cntGap++;
        else if (r.status != LookupResult.CONFIG) cntOther++;
        history.add(0, r);
        while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
        adapter.notifyDataSetChanged();
        updateCounters();
        feedback.play(r, app.settings());
        input.requestFocus();
    }

    private void updateCounters() {
        counters.setText("Thường " + cntThuong + "  ·  Gập " + cntGap + "  ·  Khác/lỗi " + cntOther);
    }
}
