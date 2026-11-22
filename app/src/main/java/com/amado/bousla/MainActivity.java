package com.amado.bousla;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView web;
    private boolean loaded = false;
    private String pendingJs = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.parseColor("#0F1F1C"));
        getWindow().setNavigationBarColor(Color.parseColor("#0B1715"));
        AlarmReceiver.ensureChannel(this);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0F1F1C"));
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(this), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                loaded = true;
                if (pendingJs != null) { v.evaluateJavascript(pendingJs, null); pendingJs = null; }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
        });
        loadPage();
        Updater.check(this, this::onNewPage);

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        Scheduler.scheduleAll(this);
        handle(getIntent());
    }

    private void loadPage() {
        loaded = false;
        web.loadDataWithBaseURL("file:///android_asset/", Updater.currentPage(this), "text/html", "utf-8", null);
    }

    /** A newer page was downloaded: show it right away unless a dialog is open; otherwise on next resume. */
    private boolean pageWaiting = false;
    private void onNewPage() {
        if (!loaded) { loadPage(); return; }
        web.evaluateJavascript("(function(){var d=document.getElementById('dlg');return d&&d.classList.contains('on')?1:0})()",
                v -> { if ("1".equals(v)) pageWaiting = true; else loadPage(); });
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handle(i);
    }

    private void handle(Intent i) {
        if (i == null) return;
        String kind = i.getStringExtra("kind");
        if (kind == null) return;
        String id = i.getStringExtra("id");
        String js = "window.openFromNotif&&openFromNotif('" + kind.replaceAll("[^a-z]", "") + "','" +
                (id == null ? "" : id.replaceAll("[^A-Za-z0-9]", "")) + "')";
        i.removeExtra("kind");
        if (loaded) web.evaluateJavascript(js, null); else pendingJs = js;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageWaiting) { pageWaiting = false; loadPage(); }
        else if (loaded) web.evaluateJavascript("window.render&&render()", null);
        Updater.check(this, this::onNewPage);
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(function(){var d=document.getElementById('dlg');if(d&&d.classList.contains('on')){d.classList.remove('on');return 1}return 0})()",
                v -> { if (!"1".equals(v)) MainActivity.super.onBackPressed(); });
    }
}
