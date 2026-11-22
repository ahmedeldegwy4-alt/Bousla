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
