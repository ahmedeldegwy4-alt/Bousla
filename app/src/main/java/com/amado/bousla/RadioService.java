package com.amado.bousla;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/** Streams a radio station in the foreground (keeps playing with the screen off). */
public class RadioService extends Service {
    static final String CH = "bousla_radio";
    static final int NOTIF = 7995;
    static volatile String state = "stopped";   // stopped | buffering | playing | error
    static volatile String station = "";
    static volatile long stopAt = 0;
    private MediaPlayer mp;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> { if ("buffering".equals(state)) { state = "error"; stopSelf(); } };
    private final Runnable sleepCheck = new Runnable() {
        @Override public void run() {
            if (stopAt > 0 && System.currentTimeMillis() >= stopAt) { stopSelf(); return; }
            h.postDelayed(this, 15000);
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        if (in != null && "stop".equals(in.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (in != null && "sleep".equals(in.getAction())) {
            int min = in.getIntExtra("min", 0);
            stopAt = min > 0 ? System.currentTimeMillis() + min * 60000L : 0;
            return START_NOT_STICKY;
        }
        String url = in != null ? in.getStringExtra("url") : null;
        station = in != null && in.getStringExtra("name") != null ? in.getStringExtra("name") : "إذاعة القرآن الكريم";
        if (url == null || !url.startsWith("https://")) { stopSelf(); return START_NOT_STICKY; }
        startForegroundNow();
        play(url);
        h.removeCallbacks(sleepCheck);
        h.postDelayed(sleepCheck, 15000);
        return START_NOT_STICKY;
    }

    private void startForegroundNow() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "الراديو", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("تشغيل إذاعة القرآن الكريم");
            nm.createNotificationChannel(ch);
        }
        Intent stop = new Intent(this, RadioService.class).setAction("stop");
        PendingIntent piStop = PendingIntent.getService(this, 11, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("kind", "radio");
        open.putExtra("id", "");
        PendingIntent piOpen = PendingIntent.getActivity(this, 12, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat).setContentTitle("📻 " + station).setContentText("بث مباشر")
                .setContentIntent(piOpen).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "⏹ إيقاف", piStop).build());
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIF, b.build());
    }

    private void play(String url) {
        release();
        state = "buffering";
        try {
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setDataSource(url);
            mp.setOnPreparedListener(m -> { h.removeCallbacks(timeout); m.start(); state = "playing"; });
            mp.setOnErrorListener((m, w, e) -> { state = "error"; h.postDelayed(this::stopSelf, 500); return true; });
            mp.setOnCompletionListener(m -> { state = "stopped"; stopSelf(); });
            mp.prepareAsync();
            h.removeCallbacks(timeout); h.postDelayed(timeout, 20000);
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) { wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "bousla:radio"); wifi.acquire(); }
        } catch (Exception e) { state = "error"; stopSelf(); }
    }

    private void release() {
        if (mp != null) { try { mp.stop(); } catch (Exception ignored) {} try { mp.release(); } catch (Exception ignored) {} mp = null; }
        if (wifi != null && wifi.isHeld()) { try { wifi.release(); } catch (Exception ignored) {} }
        wifi = null;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(sleepCheck);
        release();
        if (!"error".equals(state)) state = "stopped";
        stopAt = 0;
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        super.onDestroy();
    }
}
