package com.buymed.rfidtag;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Camera;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Fallback scanner using the phone camera, for devices without a hardware scan engine.
 * Plain Camera1 + ZXing core so it works from Android 5 upwards without extra libraries.
 */
@SuppressWarnings("deprecation")
public class CameraScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    public static final String EXTRA_TEXT = "text";
    private static final int REQ_PERMISSION = 7;

    private SurfaceView surface;
    private Camera camera;
    private boolean surfaceReady;
    private volatile boolean decoding;
    private volatile boolean done;
    private boolean torch;
    private int previewW, previewH;
    private HandlerThread decodeThread;
    private Handler decodeHandler;
    private final Handler ui = new Handler();
    private MultiFormatReader reader;
    private final Runnable refocus = new Runnable() {
        @Override public void run() {
            Camera c = camera;
            if (c == null || done) return;
            try {
                c.autoFocus(null);
            } catch (RuntimeException ignored) {
            }
            ui.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        surface = new SurfaceView(this);
        root.addView(surface, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(new Frame(this), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(24, 24, 24, 24);
        bar.setBackgroundColor(0x99000000);
        TextView hint = new TextView(this);
        hint.setText("Đưa mã QR vào khung");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(16);
        bar.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button light = new Button(this);
        light.setText("Đèn");
        light.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleTorch(); }
        });
        bar.addView(light);
        Button cancel = new Button(this);
        cancel.setText("Hủy");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(cancel);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.BOTTOM;
        root.addView(bar, lp);
        setContentView(root);

        reader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX,
                BarcodeFormat.CODE_128, BarcodeFormat.EAN_13));
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        reader.setHints(hints);

        surface.getHolder().addCallback(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        decodeThread = new HandlerThread("decode");
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper());
        decoding = false;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERMISSION);
        } else {
            startIfReady();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopCamera();
        decodeHandler = null;
        if (decodeThread != null) decodeThread.quit();
        decodeThread = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_PERMISSION) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startIfReady();
        } else {
            Toast.makeText(this, "Cần quyền camera để quét bằng camera", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; startIfReady(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
    @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; stopCamera(); }

    private void startIfReady() {
        if (camera != null || !surfaceReady || done) return;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        int id = 0;
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break; }
        }
        try {
            camera = Camera.open(id);
            Camera.getCameraInfo(id, info);
            Camera.Parameters p = camera.getParameters();
            Camera.Size best = pickSize(p.getSupportedPreviewSizes());
            p.setPreviewSize(best.width, best.height);
            previewW = best.width;
            previewH = best.height;
            List<String> modes = p.getSupportedFocusModes();
            boolean continuous = false;
            if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                continuous = true;
            } else if (modes != null && modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            camera.setParameters(p);
            camera.setDisplayOrientation(displayOrientation(info));
            camera.setPreviewDisplay(surface.getHolder());
            camera.setPreviewCallback(this);
            camera.startPreview();
            if (!continuous) ui.postDelayed(refocus, 500);
        } catch (Exception e) {
            Toast.makeText(this, "Không mở được camera: " + e.getMessage(), Toast.LENGTH_LONG).show();
            stopCamera();
            finish();
        }
    }

    private void stopCamera() {
        ui.removeCallbacks(refocus);
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
            } catch (RuntimeException ignored) {
            }
            camera.release();
            camera = null;
        }
        torch = false;
    }

    private void toggleTorch() {
        if (camera == null) return;
        try {
            Camera.Parameters p = camera.getParameters();
            List<String> modes = p.getSupportedFlashModes();
            if (modes == null || !modes.contains(Camera.Parameters.FLASH_MODE_TORCH)) {
                Toast.makeText(this, "Máy không có đèn", Toast.LENGTH_SHORT).show();
                return;
            }
            torch = !torch;
            p.setFlashMode(torch ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(p);
        } catch (RuntimeException ignored) {
        }
    }

    private static Camera.Size pickSize(List<Camera.Size> sizes) {
        Camera.Size best = sizes.get(0);
        long bestScore = Long.MAX_VALUE;
        for (Camera.Size s : sizes) {
            long score = Math.abs((long) s.width * s.height - 1280L * 720L);
            if (score < bestScore) { best = s; bestScore = score; }
        }
        return best;
    }

    private int displayOrientation(Camera.CameraInfo info) {
        int degrees = 0;
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
            default: break;
        }
        return (info.orientation - degrees + 360) % 360;
    }

    @Override
    public void onPreviewFrame(final byte[] data, Camera cam) {
        Handler h = decodeHandler;
        if (decoding || done || h == null) return;
        decoding = true;
        final int w = previewW, hh = previewH;
        boolean queued = h.post(new Runnable() {
            @Override public void run() { decode(data, w, hh); }
        });
        if (!queued) decoding = false;
    }

    private void decode(byte[] data, int w, int h) {
        try {
            int side = Math.min(w, h) * 3 / 4;
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(data, w, h, (w - side) / 2, (h - side) / 2, side, side, false);
            Result r;
            try {
                r = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src)));
            } catch (ReaderException e) {
                // QR codes print large on cartons; retry on the full frame before giving up.
                PlanarYUVLuminanceSource full = new PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false);
                r = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(full)));
            }
            final String text = r.getText();
            done = true;
            ui.post(new Runnable() {
                @Override public void run() {
                    Intent out = new Intent();
                    out.putExtra(EXTRA_TEXT, text);
                    setResult(RESULT_OK, out);
                    finish();
                }
            });
        } catch (ReaderException e) {
            // no code in this frame
        } catch (RuntimeException e) {
            // bad frame size during a configuration change
        } finally {
            reader.reset();
            decoding = false;
        }
    }

    /** Dim overlay with a clear square where the code should go. */
    static final class Frame extends View {
        private final Paint dim = new Paint();
        private final Paint edge = new Paint();

        Frame(android.content.Context c) {
            super(c);
            dim.setColor(0x88000000);
            edge.setColor(0xFF58B3CE);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(6f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            int side = Math.min(w, h) * 3 / 4;
            int l = (w - side) / 2, t = (h - side) / 2 - h / 16;
            canvas.drawRect(0, 0, w, t, dim);
            canvas.drawRect(0, t + side, w, h, dim);
            canvas.drawRect(0, t, l, t + side, dim);
            canvas.drawRect(l + side, t, w, t + side, dim);
            canvas.drawRect(l, t, l + side, t + side, edge);
        }
    }
}
