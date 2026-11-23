package com.amado.bousla;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

final class Scheduler {
    static final int CODE_REVIEW = 900001;
    static final int CODE_REVIEW2 = 900002;
    static final int CODE_YCHECK = 900003;

    /** Cancel every alarm we set before and set them again from the saved schedule. */
    static void scheduleAll(Context c) {
        SharedPreferences p = Store.p(c);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        String old = p.getString("codes", "");
        for (String s : old.split(",")) {
            if (s.isEmpty()) continue;
            try { am.cancel(broadcast(c, Integer.parseInt(s), new Intent(c, AlarmReceiver.class))); } catch (Exception ignored) {}
        }
        StringBuilder codes = new StringBuilder();
        try {
            JSONArray a = new JSONArray(p.getString("sched", "[]"));
            for (int i = 0; i < a.length() && i < 200; i++) {
                JSONObject o = a.getJSONObject(i);
                String id = o.getString("id");
                String t = o.getString("t");
                int s = o.getInt("s"), e = o.getInt("e");
                int span = ((e - s) % 24 + 24) % 24;
                int startCode = 1000 + i * 2, followCode = 1001 + i * 2;
                setDaily(c, startCode, "start", id, t, s, 0);
                if (span >= 2) setDaily(c, followCode, "follow", id, t, ((e - 1) % 24 + 24) % 24, 0);
                else setDaily(c, followCode, "follow", id, t, s, 45);
                codes.append(startCode).append(',').append(followCode).append(',');
            }
        } catch (Exception ignored) {}
        setDaily(c, CODE_REVIEW, "review", "", "", 21, 0);
        setDaily(c, CODE_REVIEW2, "review2", "", "", 22, 30);
        setDaily(c, CODE_YCHECK, "ycheck", "", "", 9, 30);
        p.edit().putString("codes", codes.toString()).apply();
        scheduleDhikr(c);
        scheduleWisdom(c);
        scheduleAdhan(c);
    }

    static final int CODE_DHIKR = 900020;
    static final int CODE_WISDOM = 900030;
    static final int CODE_ADHAN = 900040;

    /** Next enabled prayer from the list of upcoming times the page computed. */
    static void scheduleAdhan(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent i = alarmIntent(c, "adhan", "", "", 0, 0, false);
        i.putExtra("code", CODE_ADHAN);
        am.cancel(broadcast(c, CODE_ADHAN, i));
        try {
            JSONObject a = new JSONObject(Store.p(c).getString("adhan", "{}"));
            if (!a.optBoolean("on", false)) return;
            JSONObject en = a.optJSONObject("prayers");
            JSONArray t = a.optJSONArray("times");
            if (t == null) return;
            long now = System.currentTimeMillis();
            for (int k = 0; k < t.length(); k++) {
                JSONArray e = t.getJSONArray(k);
                long ts = e.getLong(0);
                String key = e.getString(1);
                if (ts <= now + 5000) continue;
                if (en != null && !en.optBoolean(key, true)) continue;
                i.putExtra("id", key);
                i.putExtra("title", e.optString(2, ""));
                i.putExtra("when", ts);
                setAt(c, CODE_ADHAN, i, ts);
                return;
            }
        } catch (Exception ignored) {}
    }

    static void scheduleWisdom(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        try {
            JSONObject w = new JSONObject(Store.p(c).getString("wisdom", "{}"));
            Intent i = alarmIntent(c, "wisdom", "", "", w.optInt("h", 8), w.optInt("m", 0), true);
            i.putExtra("code", CODE_WISDOM);
            am.cancel(broadcast(c, CODE_WISDOM, i));
            if (!w.optBoolean("on", true) || w.optJSONArray("items") == null) return;
            setAt(c, CODE_WISDOM, i, nextAt(w.optInt("h", 8), w.optInt("m", 0)));
        } catch (Exception ignored) {}
    }

    /** Next dhikr reminder: every N minutes between from:00 and to:00. */
    static void scheduleDhikr(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent i = alarmIntent(c, "dhikr", "", "", 0, 0, false);
        i.putExtra("code", CODE_DHIKR);
        am.cancel(broadcast(c, CODE_DHIKR, i));
        try {
            JSONObject d = new JSONObject(Store.p(c).getString("dhikr", "{}"));
            if (!d.optBoolean("on", false)) return;
            int every = Math.max(15, d.optInt("every", 60));
            int from = d.optInt("from", 8), to = d.optInt("to", 22);
            if (to <= from) to = from + 1;
            Calendar now = Calendar.getInstance();
            int m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            int start = from * 60, end = to * 60, next;
            if (m < start) next = start;
            else next = start + ((m - start) / every + 1) * every;
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            if (next > end) {
                cal.add(Calendar.DAY_OF_YEAR, 1);
                next = start;
            }
            cal.set(Calendar.HOUR_OF_DAY, next / 60);
            cal.set(Calendar.MINUTE, next % 60);
            setAt(c, CODE_DHIKR, i, cal.getTimeInMillis());
        } catch (Exception ignored) {}
    }

    static Intent alarmIntent(Context c, String kind, String id, String title, int h, int m, boolean daily) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.putExtra("kind", kind);
        i.putExtra("id", id);
        i.putExtra("title", title);
        i.putExtra("h", h);
        i.putExtra("m", m);
        i.putExtra("daily", daily);
        return i;
    }

    static PendingIntent broadcast(Context c, int code, Intent i) {
        return PendingIntent.getBroadcast(c, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Next occurrence of h:m that is in the future. */
    static long nextAt(int h, int m) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, h);
        cal.set(Calendar.MINUTE, m);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis() + 1000) cal.add(Calendar.DAY_OF_YEAR, 1);
        return cal.getTimeInMillis();
    }

    static void setDaily(Context c, int code, String kind, String id, String title, int h, int m) {
        Intent i = alarmIntent(c, kind, id, title, h, m, true);
        i.putExtra("code", code);
        setAt(c, code, i, nextAt(h, m));
    }

    static void setAt(Context c, int code, Intent i, long when) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = broadcast(c, code, i);
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        } catch (SecurityException se) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        }
    }
}
