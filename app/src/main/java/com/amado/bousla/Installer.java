package com.amado.bousla;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** One-tap update: download the latest APK and hand it to the system installer. */
final class Installer {
    static volatile String state = "idle";   // idle | need_perm | downloading | installing | done | error
    static volatile int progress = 0;

    static void start(Activity a) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            state = "need_perm";
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            } catch (Exception ignored) {}
            return;
        }
        if ("downloading".equals(state) || "installing".equals(state)) return;
        state = "downloading";
        progress = 0;
        final Context c = a.getApplicationContext();
        new Thread(() -> {
            try {
                File f = new File(c.getCacheDir(), "update.apk");
                download(Updater.APK_URL + "?t=" + System.currentTimeMillis(), f);
                install(c, f);
            } catch (Exception e) {
                state = "error";
            }
        }).start();
    }

    private static void download(String url, File out) throws Exception {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setInstanceFollowRedirects(false);
            h.setConnectTimeout(15000);
            h.setReadTimeout(30000);
            int code = h.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = h.getHeaderField("Location");
                h.disconnect();
                if (loc == null || !loc.startsWith("https://")) throw new Exception("bad redirect");
                url = loc;
                continue;
            }
            if (code != 200) { h.disconnect(); throw new Exception("http " + code); }
            long total = h.getContentLengthLong(), got = 0;
            try (InputStream in = h.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    got += n;
                    if (total > 0) progress = (int) (got * 100 / total);
                }
            } finally { h.disconnect(); }
            if (out.length() < 100000) throw new Exception("too small");
            return;
        }
        throw new Exception("too many redirects");
    }

    private static void install(Context c, File f) throws Exception {
        state = "installing";
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("base.apk", 0, f.length()); InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(c, Result.class);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(c, 3, i, fl).getIntentSender());
        }
    }

    /** Receives the installer's answer; asks the user to confirm. */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent in) {
            int status = in.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = in.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try { c.startActivity(confirm); } catch (Exception e) { state = "error"; }
                }
            } else if (status == PackageInstaller.STATUS_SUCCESS) {
                state = "done";
            } else {
                state = status == PackageInstaller.STATUS_FAILURE_ABORTED ? "idle" : "error";
            }
        }
    }
}
