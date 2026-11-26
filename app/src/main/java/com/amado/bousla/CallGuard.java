package com.amado.bousla;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/** Keeps the app quiet during phone/VoIP calls: checks the call state and stops the sound when another app takes audio focus. */
final class CallGuard {
    private final AudioManager am;
    private final Runnable onLoss;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Object req;
    private AudioManager.OnAudioFocusChangeListener l;
    private boolean active = false;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (inCall(am)) { fire(); return; }
            h.postDelayed(this, 500);
        }
    };

    CallGuard(Context c, Runnable onLoss) {
        this.am = (AudioManager) c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        this.onLoss = onLoss;
    }

    static boolean inCall(Context c) {
        return inCall((AudioManager) c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE));
    }

    static boolean inCall(AudioManager am) {
        if (am == null) return false;
        int m = am.getMode();
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE
                || (Build.VERSION.SDK_INT >= 33 && m == AudioManager.MODE_CALL_SCREENING)
                || (Build.VERSION.SDK_INT >= 30 && m == AudioManager.MODE_CALL_REDIRECT);
    }

    /** Takes short audio focus and starts watching. Returns false if a call is going on now. */
    boolean start(int usage) {
        if (am == null) return true;
        if (inCall(am)) return false;
        active = true;
        l = change -> {
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) h.post(this::fire);
        };
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                AudioFocusRequest r = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(usage)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setOnAudioFocusChangeListener(l, h).build();
                req = r;
                am.requestAudioFocus(r);
            } else {
                am.requestAudioFocus(l, AudioManager.STREAM_NOTIFICATION, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
        } catch (Exception ignored) {}
        h.postDelayed(poll, 500);
        return true;
    }

    private void fire() {
        if (!active) return;
        stop();
        try { onLoss.run(); } catch (Exception ignored) {}
    }

    void stop() {
        active = false;
        h.removeCallbacks(poll);
        try {
            if (Build.VERSION.SDK_INT >= 26 && req != null) am.abandonAudioFocusRequest((AudioFocusRequest) req);
            else if (l != null) am.abandonAudioFocus(l);
        } catch (Exception ignored) {}
    }
}
