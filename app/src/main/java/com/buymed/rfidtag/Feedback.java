package com.buymed.rfidtag;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Distinct sounds so the operator does not have to look at the screen:
 * tem thường = one short beep, tem gập = two beeps, anything else = error tone + vibration.
 */
final class Feedback {
    private ToneGenerator tone;
    private final Vibrator vibrator;

    Feedback(Context ctx) {
        vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
        } catch (RuntimeException e) {
            tone = null; // some devices refuse a ToneGenerator; feedback is optional
        }
    }

    void play(LookupResult r, Settings s) {
        if (s.sound() && tone != null) {
            switch (r.status) {
                case LookupResult.TEM_THUONG: tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150); break;
                case LookupResult.TEM_GAP: tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 300); break;
                case LookupResult.OTHER_TAG:
                case LookupResult.CONFIG: tone.startTone(ToneGenerator.TONE_PROP_ACK, 200); break;
                default: tone.startTone(ToneGenerator.TONE_SUP_ERROR, 500); break;
            }
        }
        if (s.vibrate() && vibrator != null && !r.isTag() && r.status != LookupResult.CONFIG) {
            vibrate(new long[]{0, 120, 80, 120});
        }
    }

    @SuppressWarnings("deprecation")
    private void vibrate(long[] pattern) {
        try {
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else vibrator.vibrate(pattern, -1);
        } catch (RuntimeException ignored) {
        }
    }

    void release() {
        if (tone != null) tone.release();
        tone = null;
    }
}
