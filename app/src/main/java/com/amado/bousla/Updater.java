package com.amado.bousla;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Pulls the latest page from GitHub Pages and the latest APK version number. */
final class Updater {
    static final String PAGE_URL = "https://ahmedeldegwy4-alt.github.io/Bousla/index.html";
    static final String VERSION_URL = "https://github.com/ahmedeldegwy4-alt/Bousla/releases/download/latest/version.txt";
    static final String APK_URL = "https://github.com/ahmedeldegwy4-alt/Bousla/releases/download/latest/Bousla.apk";

    static File pageFile(Context c) { return new File(c.getFilesDir(), "page.html"); }

    /** Page to show now: the last downloaded one, else the one built into the APK. */
    static String currentPage(Context c) {
        try {
            File f = pageFile(c);
            if (f.exists() && f.length() > 1000) return new String(Files.read(f), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        try (InputStream in = c.getAssets().open("index.html")) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<!DOCTYPE html><html><body>تعذر تحميل الصفحة</body></html>";
        }
    }

    /** Runs in the background; calls onNewPage on the main thread when a newer page was saved. */
    static void check(Context ctx, Runnable onNewPage) {
        final Context c = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                byte[] page = get(PAGE_URL + "?t=" + System.currentTimeMillis());
                String html = page == null ? null : new String(page, StandardCharsets.UTF_8);
                if (html != null && html.startsWith("<!DOCTYPE html") && html.contains("PILLARS")
                        && !html.equals(currentPage(c))) {
                    File tmp = new File(c.getFilesDir(), "page.tmp");
                    try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(page); }
                    if (tmp.renameTo(pageFile(c))) new Handler(Looper.getMainLooper()).post(onNewPage);
                }
            } catch (Exception ignored) {}
            try {
                byte[] v = get(VERSION_URL + "?t=" + System.currentTimeMillis());
                if (v != null) {
                    int n = Integer.parseInt(new String(v, StandardCharsets.UTF_8).trim());
                    Store.p(c).edit().putInt("remoteApk", n).apply();
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private static byte[] get(String url) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(8000);
            h.setReadTimeout(10000);
            h.setUseCaches(false);
            h.setInstanceFollowRedirects(false);
            int code = h.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = h.getHeaderField("Location");
                h.disconnect();
                if (loc == null || !loc.startsWith("https://")) return null;
                url = loc;
                continue;
            }
            if (code != 200) { h.disconnect(); return null; }
            try (InputStream in = h.getInputStream()) { return readAll(in); } finally { h.disconnect(); }
        }
        return null;
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    static final class Files {
        static byte[] read(File f) throws Exception {
            try (InputStream in = new java.io.FileInputStream(f)) { return readAll(in); }
        }
    }
}
