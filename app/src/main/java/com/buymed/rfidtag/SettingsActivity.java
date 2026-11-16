package com.buymed.rfidtag;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class SettingsActivity extends Activity {
    static final int REQ_IMPORT = 2;
    static final int REQ_STORE = 3;

    private RfidApp app;
    private EditText baseUrl, token, customerId, scanAction, scanExtra, circaKey, nwKey, nwWarehouse;
    private CheckBox online, batch, sound, vibrate;
    private TextView dataStatus, storeStatus, nwStatus;
    private Handler ui;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        app = (RfidApp) getApplication();
        ui = new Handler(Looper.getMainLooper());

        baseUrl = (EditText) findViewById(R.id.s_base_url);
        token = (EditText) findViewById(R.id.s_token);
        customerId = (EditText) findViewById(R.id.s_customer);
        scanAction = (EditText) findViewById(R.id.s_action);
        scanExtra = (EditText) findViewById(R.id.s_extra);
        online = (CheckBox) findViewById(R.id.s_online);
        batch = (CheckBox) findViewById(R.id.s_batch);
        sound = (CheckBox) findViewById(R.id.s_sound);
        vibrate = (CheckBox) findViewById(R.id.s_vibrate);
        dataStatus = (TextView) findViewById(R.id.s_data_status);
        storeStatus = (TextView) findViewById(R.id.s_store_status);
        circaKey = (EditText) findViewById(R.id.s_circa_key);
        nwKey = (EditText) findViewById(R.id.s_nw_key);
        nwWarehouse = (EditText) findViewById(R.id.s_nw_warehouse);
        nwStatus = (TextView) findViewById(R.id.s_nw_status);

        Settings s = app.settings();
        baseUrl.setText(s.baseUrl());
        token.setText(s.token());
        customerId.setText(String.valueOf(s.customerId()));
        scanAction.setText(s.scanAction());
        scanExtra.setText(s.scanExtra());
        online.setChecked(s.online());
        batch.setChecked(s.batchInference());
        sound.setChecked(s.sound());
        vibrate.setChecked(s.vibrate());
        circaKey.setText(s.circaKey());
        nwKey.setText(s.nwKeyOverride());
        nwWarehouse.setText(s.nwWarehouseOverride());

        findViewById(R.id.s_save).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        findViewById(R.id.s_choose_store).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Settings.headerSafe(circaKey.getText().toString()).isEmpty()) {
                    toast("Nhập khóa API Circa trước, rồi bấm Chọn kho");
                    return;
                }
                // Save the form first so the store list is fetched with the key on screen.
                if (!persist()) return;
                startActivityForResult(new Intent(SettingsActivity.this, StoreActivity.class), REQ_STORE);
            }
        });
        findViewById(R.id.s_import).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickFile(); }
        });
        findViewById(R.id.s_reset).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmReset(); }
        });
        findViewById(R.id.s_clear_cache).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                app.cache().clear();
                toast("Đã xóa cache mã QR");
                refreshStatus();
            }
        });
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        String store = app.settings().storeLabel();
        storeStatus.setText(store.isEmpty() ? "Kho: chưa chọn" : "Kho: " + store);
        refreshNwStatus();
    }

    /** Which NW key and warehouse are in use (saved settings). */
    private void refreshNwStatus() {
        Settings s = app.settings();
        String key = !s.nwKeyOverride().isEmpty() ? "Khóa Nextwaves: đã nhập / quét QR"
                : Settings.hasBuiltinNwKey() ? "Khóa Nextwaves: có sẵn trong app (để trống ô trên)"
                : "Khóa Nextwaves: chưa có";
        String wh = s.nwWarehouseId();
        String how = s.nwWarehouseOverride().isEmpty() ? "tự khớp theo tên kho" : "nhập tay";
        nwStatus.setText(key + "\n" + (wh.isEmpty()
                ? "Kho Nextwaves: chưa có cho kho Circa đang chọn"
                : "Kho Nextwaves: " + NextwavesClient.warehouseLabel(wh) + " (" + how + ")"));
    }

    private void refreshStatus() {
        ProductRepository repo = app.repo();
        String data = repo.isReady()
                ? String.format(Locale.US, "%,d", repo.size()).replace(',', '.') + " sản phẩm · " + repo.sourceLabel()
                : "Đang tải dữ liệu…";
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        dataStatus.setText(data + "\nCache mã QR: " + app.cache().countCodes() + " mã\nPhiên bản app: " + version);
    }

    private void save() {
        if (!persist()) return;
        toast("Đã lưu cài đặt");
        finish();
    }

    /** Writes the form to Settings; false (with a toast) when a field is invalid. */
    private boolean persist() {
        long cid;
        try {
            cid = Long.parseLong(customerId.getText().toString().trim());
        } catch (NumberFormatException e) {
            toast("customerId phải là số");
            return false;
        }
        String wh = Settings.headerSafe(nwWarehouse.getText().toString());
        if (!wh.isEmpty() && !NextwavesClient.isUuid(wh)) {
            toast("Kho Nextwaves phải là UUID (dạng 3f7bd541-…) hoặc để trống");
            return false;
        }
        Settings s = app.settings();
        String oldKey = s.circaKey(), oldNwKey = s.nwKey(), oldWh = s.nwWarehouseOverride();
        String key = circaKey.getText().toString().trim();
        String nk = nwKey.getText().toString().trim();
        s.save(baseUrl.getText().toString().trim(), token.getText().toString().trim(), cid,
                online.isChecked(), batch.isChecked(), scanAction.getText().toString().trim(),
                scanExtra.getText().toString().trim(), sound.isChecked(), vibrate.isChecked(), key, nk, wh);
        if (!key.equals(oldKey) || !s.nwKey().equals(oldNwKey) || !wh.equals(oldWh)) app.resolver().clearStockCache();
        refreshNwStatus();
        return true;
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (android.content.ActivityNotFoundException e) {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.addCategory(Intent.CATEGORY_OPENABLE);
            g.setType("*/*");
            startActivityForResult(Intent.createChooser(g, "Chọn file dữ liệu"), REQ_IMPORT);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            runImport(data.getData());
        }
        if (requestCode == REQ_STORE) {
            // The form was saved before leaving; show what is stored now (the key is sanitized on
            // save), so a later "Lưu" can never write a stale key back.
            circaKey.setText(app.settings().circaKey());
            nwKey.setText(app.settings().nwKeyOverride());
            nwWarehouse.setText(app.settings().nwWarehouseOverride());
        }
    }

    private void runImport(final Uri uri) {
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle("Đang nhập dữ liệu")
                .setMessage("Đang đọc file…")
                .setCancelable(false)
                .show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final TableImporter.Result r = DataImporter.importUri(SettingsActivity.this, uri,
                            new TableImporter.Progress() {
                                @Override public void onRows(final int rows) {
                                    ui.post(new Runnable() {
                                        @Override public void run() { progress.setMessage("Đã đọc " + rows + " dòng…"); }
                                    });
                                }
                            });
                    if (r.products.isEmpty()) throw new Exception("Không đọc được sản phẩm nào. Kiểm tra cột product_id.");
                    ui.post(new Runnable() {
                        @Override public void run() { progress.dismiss(); confirmImport(r); }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            progress.dismiss();
                            new AlertDialog.Builder(SettingsActivity.this)
                                    .setTitle("Nhập thất bại")
                                    .setMessage(String.valueOf(e.getMessage()))
                                    .setPositiveButton("OK", null)
                                    .show();
                        }
                    });
                }
            }
        }, "import").start();
    }

    private void confirmImport(final TableImporter.Result r) {
        String msg = "Đọc được " + r.products.size() + " sản phẩm:\n"
                + "• Tem thường: " + r.temThuong + "\n"
                + "• Tem gập: " + r.temGap + "\n"
                + "• Chưa cấu hình: " + r.unconfigured + "\n"
                + (r.skipped > 0 ? "• Bỏ qua: " + r.skipped + " dòng (trống/trùng/thiếu ID)\n" : "")
                + "\nThay thế dữ liệu hiện tại (" + app.repo().size() + " SP)?";
        new AlertDialog.Builder(this)
                .setTitle("Xác nhận nhập dữ liệu")
                .setMessage(msg)
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Thay thế", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        background("Đang lưu dữ liệu…", new Work() {
                            @Override public String run() throws Exception {
                                app.repo().replace(SettingsActivity.this, r.products);
                                return "Đã nhập " + r.products.size() + " sản phẩm";
                            }
                        });
                    }
                })
                .show();
    }

    private void confirmReset() {
        if (!app.repo().hasImported(this)) {
            toast("Đang dùng dữ liệu gốc của app");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Khôi phục dữ liệu gốc?")
                .setMessage("Xóa dữ liệu đã nhập và dùng lại bảng đóng gói sẵn trong app.")
                .setNegativeButton("Hủy", null)
                .setPositiveButton("Khôi phục", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        background("Đang tải dữ liệu gốc…", new Work() {
                            @Override public String run() {
                                app.repo().resetToBuiltIn(SettingsActivity.this);
                                return "Đã khôi phục dữ liệu gốc";
                            }
                        });
                    }
                })
                .show();
    }

    interface Work {
        String run() throws Exception;
    }

    /** Runs slow storage work off the UI thread behind a blocking dialog, then toasts the outcome. */
    private void background(String message, final Work work) {
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setMessage(message)
                .setCancelable(false)
                .show();
        new Thread(new Runnable() {
            @Override public void run() {
                String out;
                try {
                    out = work.run();
                } catch (Exception e) {
                    out = "Lỗi: " + e.getMessage();
                }
                final String msg = out;
                ui.post(new Runnable() {
                    @Override public void run() {
                        progress.dismiss();
                        toast(msg);
                        refreshStatus();
                    }
                });
            }
        }, "storage").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
