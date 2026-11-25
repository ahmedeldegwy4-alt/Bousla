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

    public static class Prayer extends AppWidgetProvider {
        @Override public void onUpdate(Context c, AppWidgetManager m, int[] ids) { updateAll(c); }
    }

    static final int CODE_WIDGET = 900050;
    static final int[] PC = {R.id.p_c0, R.id.p_c1, R.id.p_c2, R.id.p_c3, R.id.p_c4};
    static final int[] PL = {R.id.p_l0, R.id.p_l1, R.id.p_l2, R.id.p_l3, R.id.p_l4};
    static final int[] PN = {R.id.p_n0, R.id.p_n1, R.id.p_n2, R.id.p_n3, R.id.p_n4};
    static final int[] PT = {R.id.p_t0, R.id.p_t1, R.id.p_t2, R.id.p_t3, R.id.p_t4};

    static String hm(long t) {
        Calendar cal = Calendar.getInstance(); cal.setTimeInMillis(t);
        String s = new SimpleDateFormat("h:mm", Locale.US).format(new java.util.Date(t));
        return arDigits(s) + (cal.get(Calendar.HOUR_OF_DAY) < 12 ? " ص" : " م");
    }

    static long dayKey(long t) { Calendar c = Calendar.getInstance(); c.setTimeInMillis(t); return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR); }

    static void updatePrayer(Context c, AppWidgetManager m, int[] ids, long now) {
        if (ids.length == 0) return;
        long[] rowT = new long[5]; String[] rowN = new String[5]; int nextIdx = -1, curIdx = -1; long next = 0; String nextName = "";
        try {
            JSONArray t = new JSONObject(Store.p(c).getString("adhan", "{}")).optJSONArray("times");
            int ni = -1;
            if (t != null) for (int k = 0; k < t.length(); k++) if (t.getJSONArray(k).getLong(0) > now) { ni = k; break; }
            if (ni >= 0) {
                JSONArray ne = t.getJSONArray(ni); next = ne.getLong(0); nextName = ne.optString(2, "");
                long dk = dayKey(next); int start = ni;
                while (start > 0 && dayKey(t.getJSONArray(start - 1).getLong(0)) == dk) start--;
                for (int j = 0; j < 5 && start + j < t.length(); j++) {
                    JSONArray e = t.getJSONArray(start + j);
                    if (dayKey(e.getLong(0)) != dk) break;
                    rowT[j] = e.getLong(0); rowN[j] = e.optString(2, "");
                    if (start + j == ni) nextIdx = j;
                }
                if (nextIdx > 0) curIdx = nextIdx - 1;
            }
        } catch (Exception ignored) {}
        String date = "";
        try { date = new SimpleDateFormat("EEEE", new Locale("ar")).format(new java.util.Date(now)) + "، " + hijri().replace("📅 ", ""); } catch (Exception ignored) {}
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_prayer);
            v.setTextViewText(R.id.p_date, date);
            if (next > 0) {
                v.setTextViewText(R.id.p_next, nextName);
                v.setChronometer(R.id.p_count, SystemClock.elapsedRealtime() + (next - now), "%s", true);
                v.setChronometerCountDown(R.id.p_count, true);
            } else {
                v.setTextViewText(R.id.p_next, "افتح التطبيق");
                v.setChronometer(R.id.p_count, SystemClock.elapsedRealtime(), "--", false);
            }
            for (int j = 0; j < 5; j++) {
                boolean isN = j == nextIdx, isC = j == curIdx;
                v.setInt(PC[j], "setBackgroundResource", isN ? R.drawable.wp_cell_next : isC ? R.drawable.wp_cell_cur : R.drawable.wp_cell);
                v.setTextViewText(PL[j], isN ? "الجاية" : isC ? "الحالية" : " ");
                v.setTextColor(PL[j], isN ? 0xFF0F2A23 : 0xFF5FC59A);
                v.setTextViewText(PN[j], rowN[j] != null ? rowN[j] : "—");
                v.setTextViewText(PT[j], rowT[j] > 0 ? hm(rowT[j]).replace(" ص", "").replace(" م", "") : "");
                v.setTextColor(PN[j], isN ? 0xFF0F2A23 : 0xFFC9D8D3);
                v.setTextColor(PT[j], isN ? 0xFF0F2A23 : rowT[j] > 0 && rowT[j] < now && !isC ? 0x99EEF3EF : 0xFFEEF3EF);
            }
            Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            open.putExtra("kind", "prayer"); open.putExtra("id", "");
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 7992, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            m.updateAppWidget(id, v);
        }
    }

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
        int[] pray = m.getAppWidgetIds(new ComponentName(c, Prayer.class));
        if (small.length == 0 && large.length == 0 && pray.length == 0) return;
        long now = System.currentTimeMillis();
        updatePrayer(c, m, pray, now);

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
