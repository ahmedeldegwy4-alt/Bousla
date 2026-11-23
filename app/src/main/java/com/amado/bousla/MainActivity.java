package com.amado.bousla;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    static MainActivity current;
    private WebView web;
    private boolean loaded = false;
    private String pendingJs = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = this;
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
        s.setMediaPlaybackRequiresUserGesture(false);
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
        if (compassOn) registerCompass();
    }

    /* ---------- القبلة: compass + location ---------- */
    private SensorManager sm;
    private SensorEventListener sl;
    private boolean compassOn = false;
    private final float[] rot = new float[9], ori = new float[3];
    private long lastSend = 0;
    private float declination = 0;
    private int acc = 3;

    private void js(String code) {
        runOnUiThread(() -> { if (loaded && web != null) web.evaluateJavascript(code, null); });
    }

    void startCompass() {
        runOnUiThread(() -> { compassOn = true; registerCompass(); requestLocation(); });
    }

    void stopCompass() {
        runOnUiThread(() -> { compassOn = false; unregisterCompass(); });
    }

    private final SensorEventListener magAcc = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) { acc = e.accuracy; }
        @Override public void onAccuracyChanged(Sensor s, int a) { acc = a; }
    };

    private void registerCompass() {
        if (sl != null) return;
        if (sm == null) sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rv == null) { js("window.onHeadingError&&onHeadingError('no_sensor')"); return; }
        sl = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent e) {
                SensorManager.getRotationMatrixFromVector(rot, e.values);
                SensorManager.getOrientation(rot, ori);
                float az = (float) Math.toDegrees(ori[0]);
                az = ((az + declination) % 360 + 360) % 360;
                long now = SystemClock.uptimeMillis();
                if (now - lastSend < 70) return;
                lastSend = now;
                js("window.onHeading&&onHeading(" + String.format(java.util.Locale.US, "%.1f", az) + "," + acc + ")");
            }
            @Override public void onAccuracyChanged(Sensor s, int a) {}
        };
        sm.registerListener(sl, rv, SensorManager.SENSOR_DELAY_UI);
        Sensor mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (mag != null) sm.registerListener(magAcc, mag, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void unregisterCompass() {
        if (sm != null) {
            if (sl != null) sm.unregisterListener(sl);
            sm.unregisterListener(magAcc);
        }
        sl = null;
    }

    private void requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 2);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location best = null;
        for (String p : lm.getProviders(true)) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException ignored) {}
        }
        if (best != null) onLoc(best);
        String prov = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER
                : lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null;
        if (prov == null) {
            if (best == null) js("window.onQiblaLocError&&onQiblaLocError('off')");
            return;
        }
        try {
            lm.requestSingleUpdate(prov, new LocationListener() {
                @Override public void onLocationChanged(Location l) { onLoc(l); }
                @Override public void onStatusChanged(String p, int s, Bundle x) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            }, Looper.getMainLooper());
        } catch (SecurityException ignored) {}
    }

    private void onLoc(Location l) {
        GeomagneticField g = new GeomagneticField((float) l.getLatitude(), (float) l.getLongitude(),
                (float) l.getAltitude(), System.currentTimeMillis());
        declination = g.getDeclination();
        Store.p(this).edit().putString("qloc", l.getLatitude() + "," + l.getLongitude()).apply();
        js(String.format(java.util.Locale.US, "window.onQiblaLoc&&onQiblaLoc(%.5f,%.5f)", l.getLatitude(), l.getLongitude()));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != 2) return;
        boolean ok = false;
        for (int r : res) if (r == PackageManager.PERMISSION_GRANTED) ok = true;
        if (ok) requestLocation(); else js("window.onQiblaLocError&&onQiblaLocError('denied')");
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterCompass();
    }

    @Override
    protected void onDestroy() {
        unregisterCompass();
        if (current == this) current = null;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(function(){var d=document.getElementById('dlg');if(d&&d.classList.contains('on')){d.classList.remove('on');return 1}return 0})()",
                v -> { if (!"1".equals(v)) MainActivity.super.onBackPressed(); });
    }
}
