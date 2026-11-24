package com.amado.bousla;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.Manifest;
import android.app.NotificationManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.net.Uri;
import android.os.Build;
import android.webkit.JavascriptInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Called from the page's JavaScript as window.Android. */
public class Bridge {
    private final Context c;

    Bridge(Context c) { this.c = c.getApplicationContext(); }

    @JavascriptInterface
    public void setSchedule(String json) {
        SharedPreferences p = Store.p(c);
        if (json == null || json.equals(p.getString("sched", ""))) return;
        p.edit().putString("sched", json).commit();
        Scheduler.scheduleAll(c);
    }

    @JavascriptInterface
    public void setStatus(String json) {
        if (json == null) return;
        Store.p(c).edit().putString("status", json).apply();
        Widgets.updateAll(c);
    }

    /** The user's data lives in the app's own storage, so page updates never touch it. */
    @JavascriptInterface
    public synchronized String loadData() {
        try {
            File f = new File(c.getFilesDir(), "data.json");
            if (!f.exists()) return "";
            return new String(Updater.Files.read(f), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    @JavascriptInterface
    public synchronized void saveData(String json) {
        if (json == null || json.length() < 2) return;
        try {
            File tmp = new File(c.getFilesDir(), "data.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
            tmp.renameTo(new File(c.getFilesDir(), "data.json"));
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public int getAppVersion() {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) { return 0; }
    }

    @JavascriptInterface
    public int getApkUpdate() { return Store.p(c).getInt("remoteApk", 0); }

    /** Posts a test notification now (delaySec <= 0) or after delaySec seconds. */
    @JavascriptInterface
    public String testNotification(int delaySec) {
        boolean allowed = Build.VERSION.SDK_INT < 33 ||
                c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (!allowed || !nm.areNotificationsEnabled()) {
            try {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Exception ignored) {}
            return "no_permission";
        }
        Intent i = Scheduler.alarmIntent(c, "test", "", "", 0, 0, false);
        i.putExtra("code", 900010);
        if (delaySec <= 0) new AlarmReceiver().onReceive(c, i);
        else Scheduler.setAt(c, 900010, i, System.currentTimeMillis() + delaySec * 1000L);
        return "ok";
    }

    @JavascriptInterface
    public void qiblaStart() { MainActivity a = MainActivity.current; if (a != null) a.startCompass(); }

    @JavascriptInterface
    public void qiblaStop() { MainActivity a = MainActivity.current; if (a != null) a.stopCompass(); }

    @JavascriptInterface
    public String getQiblaLoc() { return Store.p(c).getString("qloc", ""); }

    @JavascriptInterface
    public void openLocationSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void radioPlay(String url, String name) {
        if (url == null || !url.startsWith("https://")) return;
        Intent s = new Intent(c, RadioService.class).putExtra("url", url).putExtra("name", name);
        RadioService.state = "buffering";
        try { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s); else c.startService(s); } catch (Exception e) { RadioService.state = "error"; }
    }

    @JavascriptInterface
    public void radioStop() { if (!"stopped".equals(RadioService.state)) c.startService(new Intent(c, RadioService.class).setAction("stop")); RadioService.state = "stopped"; }

    @JavascriptInterface
    public String radioState() { return RadioService.state + "|" + RadioService.station + "|" + RadioService.stopAt; }

    @JavascriptInterface
    public void radioSleep(int min) { if (!"stopped".equals(RadioService.state)) c.startService(new Intent(c, RadioService.class).setAction("sleep").putExtra("min", min)); }

    @JavascriptInterface
    public void setAdhan(String json) {
        SharedPreferences p = Store.p(c);
        if (json == null || json.equals(p.getString("adhan", ""))) return;
        p.edit().putString("adhan", json).commit();
        Scheduler.scheduleAdhan(c);
        Widgets.updateAll(c);
    }

    @JavascriptInterface
    public void testAdhan(String sheikh) {
        Intent s = new Intent(c, AdhanService.class);
        s.putExtra("prayer", "(تجربة)");
        s.putExtra("sheikh", sheikh);
        try { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s); else c.startService(s); } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void stopAdhan() { c.startService(new Intent(c, AdhanService.class).setAction("stop")); }

    @JavascriptInterface
    public void setWisdom(String json) {
        SharedPreferences p = Store.p(c);
        if (json == null || json.equals(p.getString("wisdom", ""))) return;
        p.edit().putString("wisdom", json).commit();
        Scheduler.scheduleWisdom(c);
        Widgets.updateAll(c);
    }

    @JavascriptInterface
    public void testWisdom() { AlarmReceiver.notifyWisdom(c); }

    @JavascriptInterface
    public void setDhikr(String json) {
        SharedPreferences p = Store.p(c);
        if (json == null || json.equals(p.getString("dhikr", ""))) return;
        p.edit().putString("dhikr", json).commit();
        Scheduler.scheduleDhikr(c);
    }

    @JavascriptInterface
    public void testDhikr() {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            String[] ph = AlarmReceiver.nextDhikr(c);
            if (ph == null) return;
            if (AlarmReceiver.notifyDhikr(c, ph[0]) && Speaker.enabled(c)) Speaker.say(c, ph[1], ph[0], null);
        });
    }

    @JavascriptInterface
    public void setSpeak(boolean on) { Store.p(c).edit().putBoolean("speak", on).apply(); }

    @JavascriptInterface
    public boolean getSpeak() { return Speaker.enabled(c); }

    @JavascriptInterface
    public void testSpeak() {
        Store.p(c).edit().putString("ttsStatus", "pending").apply();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                Speaker.say(c, "test", "بوصلة قلبك. التنبيهات الناطقة شغالة، الحمد لله.", null));
    }

    @JavascriptInterface
    public String getTtsStatus() { return Store.p(c).getString("ttsStatus", ""); }

    @JavascriptInterface
    public void openTtsSettings() {
        try {
            Intent i = new Intent("com.android.settings.TTS_SETTINGS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Exception ignored) {}
        }
    }

    @JavascriptInterface
    public void openUrl(String url) {
        if (url == null || !url.startsWith("https://")) return;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); } catch (Exception ignored) {}
    }
}
