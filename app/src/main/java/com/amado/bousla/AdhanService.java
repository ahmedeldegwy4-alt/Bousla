package com.amado.bousla;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;

/** Plays the adhan in the foreground so it is not cut off, with a stop button. */
public class AdhanService extends Service {
    static final String CH = "bousla_adhan";
    static final int NOTIF = 7997;
    private MediaPlayer mp;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        if (in != null && "stop".equals(in.getAction())) { stopSelf(); return START_NOT_STICKY; }
        String prayer = in != null ? in.getStringExtra("prayer") : null;
        String sheikh = in != null ? in.getStringExtra("sheikh") : null;
        if (sheikh == null) sheikh = "refaat";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            NotificationChannel ch = new NotificationChannel(CH, "الأذان", NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);
            ch.setDescription("الأذان في مواقيت الصلاة");
            nm.createNotificationChannel(ch);
        }
        Intent stop = new Intent(this, AdhanService.class).setAction("stop");
        PendingIntent piStop = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("kind", "prayer");
        open.putExtra("id", "");
        PendingIntent piOpen = PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = prayer != null ? "🕌 حان الآن موعد أذان " + prayer : "🕌 الأذان";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText("«حيّ على الصلاة، حيّ على الفلاح»")
                .setContentIntent(piOpen).setDeleteIntent(piStop).setCategory(Notification.CATEGORY_ALARM)
                .addAction(new Notification.Action.Builder(null, "⏹ إيقاف الأذان", piStop).build());
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIF, b.build());
        play(sheikh);
        return START_NOT_STICKY;
    }

    private void play(String sheikh) {
        release();
        try {
            AssetFileDescriptor fd = getAssets().openFd("adhan/" + sheikh + ".mp3");
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            mp.setOnCompletionListener(m -> stopSelf());
            mp.setOnErrorListener((m, w, e) -> { stopSelf(); return true; });
            mp.prepare();
            mp.start();
        } catch (Exception e) { stopSelf(); }
    }

    private void release() {
        if (mp != null) { try { mp.stop(); } catch (Exception ignored) {} try { mp.release(); } catch (Exception ignored) {} mp = null; }
    }

    @Override
    public void onDestroy() {
        release();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        super.onDestroy();
    }
}
