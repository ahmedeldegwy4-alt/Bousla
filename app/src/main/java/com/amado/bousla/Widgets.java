package com.amado.bousla;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;

/** Home-screen widgets: next prayer with live countdown, Hijri date, today's progress, wisdom. */
public final class Widgets {
    public static class Small extends AppWidgetProvider {
        @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { updateAll(c); }
    }
    public static class Large extends AppWidgetProvider {
        @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { updateAll(c); }
    }

    static final int CODE_WIDGET = 900050;

    static String arDigits(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) b.append(ch >= '0' && ch <= '9' ? (char) ('٠' + (ch - '0')) : ch);
        return b.toString();
    }

    static String hijri() {
        try {
            android.icu.util.ULocale loc = new android.icu.util.ULocale("ar_EG@calendar=islamic-umalqura");
            android.icu.text.DateFormat df = android.icu.text.DateFormat.getDateInstance(android.icu.text.DateFormat.LONG, loc);
            return "📅 " + df.format(new java.util.Date());
        } catch (Exception e) { return ""; }
    }

    static void updateAll(Context ctx) {
        Context c = ctx.getApplicationContext();
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] small = m.getAppWidgetIds(new ComponentName(c, Small.class));
        int[] large = m.getAppWidgetIds(new ComponentName(c, Large.class));
        if (small.length == 0 && large.length == 0) return;
        long now = System.currentTimeMillis();

        // next prayer from the times the page computed
        String prayer = "🕌 افتح التطبيق لتحديد المواقيت";
        long next = 0;
        try {
            JSONArray t = new JSONObject(Store.p(c).getString("adhan", "{}")).optJSONArray("times");
            if (t != null) for (int k = 0; k < t.length(); k++) {
                JSONArray e = t.getJSONArray(k);
                if (e.getLong(0) > now) {
                    next = e.getLong(0);
                    String time = new SimpleDateFormat("h:mm", Locale.US).format(new java.util.Date(next));
                    Calendar cal = Calendar.getInstance(); cal.setTimeInMillis(next);
                    prayer = "🕌 " + e.optString(2, "") + " " + arDigits(time) + (cal.get(Calendar.HOUR_OF_DAY) < 12 ? " ص" : " م");
                    break;
                }
            }
        } catch (Exception ignored) {}

        // today's progress: distinct pillars with at least one proven action
        String progress = "";
        try {
            JSONObject st = new JSONObject(Store.p(c).getString("status", "{}"));
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
            HashSet<String> pillars = new HashSet<>();
            if (today.equals(st.optString("date"))) {
                JSONArray d = st.optJSONArray("done");
                if (d != null) for (int i = 0; i < d.length(); i++) { String id = d.optString(i); int a = id.indexOf('a'); if (a > 0) pillars.add(id.substring(0, a)); }
            }
            progress = "🧭 " + arDigits(String.valueOf(pillars.size())) + " من ١٠";
        } catch (Exception ignored) {}

        // wisdom of the day (same pick as the page)
        String wis = "", src = "";
        try {
            JSONArray items = new JSONObject(Store.p(c).getString("wisdom", "{}")).optJSONArray("items");
            if (items != null && items.length() > 0) {
                long day = (now + java.util.TimeZone.getDefault().getOffset(now)) / 86400000L;
                JSONObject it = items.getJSONObject((int) (day % items.length()));
                wis = "h".equals(it.optString("k")) ? "«" + it.optString("t") + "»" : it.optString("t");
                src = "— " + it.optString("src");
            }
        } catch (Exception ignored) {}

        String hijri = hijri();
        for (int[] ids : new int[][]{small, large}) {
            boolean big = ids == large;
            for (int id : ids) {
                RemoteViews v = new RemoteViews(c.getPackageName(), big ? R.layout.widget_large : R.layout.widget_small);
                v.setTextViewText(R.id.w_prayer, prayer);
                v.setTextViewText(R.id.w_hijri, hijri);
                if (next > 0) {
                    v.setChronometer(R.id.w_count, SystemClock.elapsedRealtime() + (next - now), "باقي %s", true);
                    v.setChronometerCountDown(R.id.w_count, true);
                } else v.setChronometer(R.id.w_count, SystemClock.elapsedRealtime(), "", false);
                if (big) {
                    v.setTextViewText(R.id.w_progress, progress);
                    v.setTextViewText(R.id.w_wisdom, wis);
                    v.setTextViewText(R.id.w_src, src);
                }
                Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                open.putExtra("kind", big ? "today" : "prayer");
                open.putExtra("id", "");
                v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, big ? 7990 : 7991, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
                m.updateAppWidget(id, v);
            }
        }
        // refresh right after the next prayer and at midnight
        Calendar mid = Calendar.getInstance();
        mid.add(Calendar.DAY_OF_YEAR, 1);
        mid.set(Calendar.HOUR_OF_DAY, 0); mid.set(Calendar.MINUTE, 1); mid.set(Calendar.SECOND, 0);
        long at = mid.getTimeInMillis();
        if (next > 0 && next + 60000 < at) at = next + 60000;
        Intent i = Scheduler.alarmIntent(c, "widget", "", "", 0, 0, false);
        i.putExtra("code", CODE_WIDGET);
        Scheduler.setAt(c, CODE_WIDGET, i, at);
    }
}
