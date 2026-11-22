package com.amado.bousla;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

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
}
