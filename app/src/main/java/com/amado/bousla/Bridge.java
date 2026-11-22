package com.amado.bousla;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
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

    @JavascriptInterface
    public void openUrl(String url) {
        if (url == null || !url.startsWith("https://")) return;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); } catch (Exception ignored) {}
    }
}
