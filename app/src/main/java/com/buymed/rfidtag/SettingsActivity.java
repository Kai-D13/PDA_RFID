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

    private RfidApp app;
    private EditText baseUrl, token, customerId, scanAction, scanExtra;
    private CheckBox online, batch, sound, vibrate;
    private TextView dataStatus;
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

        findViewById(R.id.s_save).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
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
        long cid;
        try {
            cid = Long.parseLong(customerId.getText().toString().trim());
        } catch (NumberFormatException e) {
            toast("customerId phải là số");
            return;
        }
        app.settings().save(baseUrl.getText().toString().trim(), token.getText().toString().trim(), cid,
                online.isChecked(), batch.isChecked(), scanAction.getText().toString().trim(),
                scanExtra.getText().toString().trim(), sound.isChecked(), vibrate.isChecked());
        toast("Đã lưu cài đặt");
        finish();
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
