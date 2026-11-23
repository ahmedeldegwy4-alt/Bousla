package com.amado.bousla;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/** Speaks a reminder aloud with the phone's text-to-speech engine (Arabic voice). */
final class Speaker {
    static boolean enabled(Context c) { return Store.p(c).getBoolean("speak", true); }

    /** Plays voices/<clip>.mp3 from the app if it exists, otherwise reads text with TTS. */
    static void say(Context ctx, String clip, String text, BroadcastReceiver.PendingResult pr) {
        final Context c = ctx.getApplicationContext();
        AssetFileDescriptor fd = null;
        if (clip != null) {
            try { fd = c.getAssets().openFd("voices/" + clip + ".mp3"); } catch (Exception ignored) {}
        }
        if (fd == null) { speak(c, text, pr); return; }
        final Handler h = new Handler(Looper.getMainLooper());
        final boolean[] done = {false};
        final MediaPlayer mp = new MediaPlayer();
        final Runnable finish = () -> {
            if (done[0]) return;
            done[0] = true;
            try { mp.release(); } catch (Exception ignored) {}
            if (pr != null) try { pr.finish(); } catch (Exception ignored) {}
        };
        h.postDelayed(finish, 9000);
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            mp.setOnCompletionListener(m -> h.post(finish));
            mp.setOnErrorListener((m, w, e) -> { h.post(finish); return true; });
            mp.prepare();
            mp.start();
            status(c, "ok");
        } catch (Exception e) {
            finish.run();
            speak(c, text, null);
        }
    }

    static void speak(Context ctx, String text, BroadcastReceiver.PendingResult pr) {
        final Context c = ctx.getApplicationContext();
        final Handler h = new Handler(Looper.getMainLooper());
        final TextToSpeech[] tts = new TextToSpeech[1];
        final boolean[] done = {false};
        final Runnable finish = () -> {
            if (done[0]) return;
            done[0] = true;
            try { if (tts[0] != null) tts[0].shutdown(); } catch (Exception ignored) {}
            if (pr != null) try { pr.finish(); } catch (Exception ignored) {}
        };
        h.postDelayed(finish, 9000); // never hold the broadcast longer than this
        try {
            tts[0] = new TextToSpeech(c, status -> h.post(() -> {
                TextToSpeech t = tts[0];
                if (status != TextToSpeech.SUCCESS || t == null) { status(c, "init_fail"); finish.run(); return; }
                int r = t.setLanguage(new Locale("ar", "EG"));
                if (r < 0) r = t.setLanguage(new Locale("ar"));
                if (r < 0) { status(c, "no_arabic"); finish.run(); return; }
                status(c, "ok");
                t.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                t.setSpeechRate(0.95f);
                t.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}
                    @Override public void onDone(String id) { h.post(finish); }
                    @Override public void onError(String id) { h.post(finish); }
                });
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "bousla");
            }));
        } catch (Exception e) {
            status(c, "init_fail");
            finish.run();
        }
    }

    private static void status(Context c, String s) { Store.p(c).edit().putString("ttsStatus", s).apply(); }
}
