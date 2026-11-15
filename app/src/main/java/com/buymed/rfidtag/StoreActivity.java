package com.buymed.rfidtag;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** "Chọn kho": the Circa stores the API key can see; the choice scopes QR lookups and stock. */
public class StoreActivity extends Activity {
    private RfidApp app;
    private Handler ui;
    private EditText search;
    private TextView status, action;
    private final List<CircaClient.Store> all = new ArrayList<CircaClient.Store>();
    private final List<CircaClient.Store> shown = new ArrayList<CircaClient.Store>();
    private StoreAdapter adapter;
    private boolean loading;
    /** A list (possibly empty) came back; do not refetch on every resume. */
    private boolean loadedOnce;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_store);
        app = (RfidApp) getApplication();
        ui = new Handler(Looper.getMainLooper());
        search = (EditText) findViewById(R.id.st_search);
        status = (TextView) findViewById(R.id.st_status);
        action = (TextView) findViewById(R.id.st_action);
        ListView list = (ListView) findViewById(R.id.st_list);
        adapter = new StoreAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                choose(shown.get(position));
            }
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { filter(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // First open, or back from Settings after the key was missing or the load failed.
        if (!loadedOnce && !loading) load();
    }

    private void load() {
        action.setVisibility(View.GONE);
        if (!app.settings().hasCircaKey()) {
            status.setText("Chưa có khóa API Circa. Quét QR cài đặt trên màn hình chính, hoặc nhập khóa trong Cài đặt.");
            action.setText("MỞ CÀI ĐẶT");
            action.setVisibility(View.VISIBLE);
            action.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    // Opened from Settings: go back to it instead of stacking a second one.
                    if (getCallingActivity() != null) finish();
                    else startActivity(new Intent(StoreActivity.this, SettingsActivity.class));
                }
            });
            return;
        }
        status.setText("Đang tải danh sách kho từ Circa…");
        loading = true;
        new Thread(new Runnable() {
            @Override public void run() {
                final CircaClient.Reply<List<CircaClient.Store>> r = CircaClient.stores(CircaClient.Auth.of(app.settings()));
                ui.post(new Runnable() { @Override public void run() { loaded(r); } });
            }
        }, "stores").start();
    }

    private void loaded(CircaClient.Reply<List<CircaClient.Store>> r) {
        loading = false;
        if (isFinishing()) return;
        if (!r.ok) {
            status.setText("Không tải được danh sách kho: " + r.message);
            action.setText("THỬ LẠI");
            action.setVisibility(View.VISIBLE);
            action.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { load(); }
            });
            return;
        }
        loadedOnce = true;
        all.clear();
        all.addAll(r.value);
        filter();
        if (all.isEmpty()) {
            status.setText("Khóa API Circa này không thấy kho nào. Kiểm tra lại khóa (đúng tài khoản có quyền kho) rồi thử lại.");
            action.setText("THỬ LẠI");
            action.setVisibility(View.VISIBLE);
            action.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { load(); }
            });
        }
    }

    private void filter() {
        String q = fold(search.getText().toString());
        shown.clear();
        for (CircaClient.Store s : all) {
            if (q.isEmpty() || fold(s.name).contains(q) || fold(s.code).contains(q)) shown.add(s);
        }
        adapter.notifyDataSetChanged();
        if (!all.isEmpty()) {
            status.setText(shown.size() == all.size() ? all.size() + " kho" : shown.size() + " / " + all.size() + " kho");
        }
    }

    private void choose(CircaClient.Store s) {
        boolean changed = !s.id.equals(app.settings().storeId());
        app.settings().saveStore(s.id, s.code, s.name, s.clientId);
        if (changed) app.resolver().clearStockCache();
        Toast.makeText(this, "Đã chọn kho: " + s.label(), Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    /** Lower case without Vietnamese diacritics, so "bac ninh" finds "BẮC NINH". */
    static String fold(String s) {
        if (s == null) return "";
        String d = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return d.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT).trim();
    }

    private final class StoreAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            if (view == null) view = LayoutInflater.from(StoreActivity.this).inflate(R.layout.item_store, parent, false);
            CircaClient.Store s = shown.get(position);
            ((TextView) view.findViewById(R.id.si_name)).setText(s.name);
            ((TextView) view.findViewById(R.id.si_code)).setText(s.code != null ? s.code : s.id);
            view.findViewById(R.id.si_check).setVisibility(s.id.equals(app.settings().storeId()) ? View.VISIBLE : View.GONE);
            return view;
        }
    }
}
