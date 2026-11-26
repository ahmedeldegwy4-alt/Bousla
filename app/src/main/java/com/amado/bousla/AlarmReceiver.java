package com.amado.bousla;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class AlarmReceiver extends BroadcastReceiver {
    static final String CH = "bousla_reminders";

    static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CH) == null) {
                NotificationChannel ch = new NotificationChannel(CH, "تذكيرات البوصلة", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("تذكير بالأعمال والمتابعة والمحاسبة");
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }
        }
    }

    @Override
    public void onReceive(Context c, Intent in) {
        String kind = in.getStringExtra("kind");
        if (kind == null) return;
        String id = in.getStringExtra("id");
        String title = in.getStringExtra("title");
        int code = in.getIntExtra("code", 0);

        if ("snooze".equals(kind)) {
            Speaker.stopAll();
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(in.getIntExtra("notif", 0));
            Intent again = Scheduler.alarmIntent(c, in.getStringExtra("orig"), id, title, 0, 0, false);
            int sc = 600000 + (code % 100000);
            again.putExtra("code", sc);
            Scheduler.setAt(c, sc, again, System.currentTimeMillis() + 30 * 60 * 1000L);
            return;
        }

        if ("widget".equals(kind)) { Widgets.updateAll(c); return; }
        if ("hush".equals(kind)) { Speaker.stopAll(); return; }

        if ("adhan".equals(kind)) {
            long when = in.getLongExtra("when", 0);
            Scheduler.scheduleAdhan(c);
            // skip a stale alarm (e.g. phone was off at prayer time)
            if (when > 0 && System.currentTimeMillis() - when > 10 * 60 * 1000L) return;
            startAdhan(c, title);
            mark(c);
            return;
        }

        if ("wisdom".equals(kind)) {
            if (in.getBooleanExtra("daily", false)) Scheduler.scheduleWisdom(c);
            long w = waitMs(c);
            if (w > 0) { defer(c, in, "wisdom", Scheduler.CODE_WISDOM, w); return; }
            if (notifyWisdom(c)) mark(c);
            return;
        }

        if ("dhikr".equals(kind)) {
            Scheduler.scheduleDhikr(c);
            if (waitMs(c) > 0) return;   // too close to another notification: skip this one
            String[] ph = nextDhikr(c);
            if (ph == null) return;
            boolean ok = notifyDhikr(c, ph[0]);
            if (ok) mark(c);
            if (ok && Speaker.enabled(c)) Speaker.say(c, ph[1], ph[0], goAsync());
            return;
        }

        // Daily alarms re-arm themselves for tomorrow.
        if (in.getBooleanExtra("daily", false)) {
            Intent next = Scheduler.alarmIntent(c, kind, id, title, in.getIntExtra("h", 0), in.getIntExtra("m", 0), true);
            next.putExtra("code", code);
            Scheduler.setAt(c, code, next, Scheduler.nextAt(in.getIntExtra("h", 0), in.getIntExtra("m", 0)));
        }

        JSONObject st;
        try { st = new JSONObject(Store.p(c).getString("status", "{}")); } catch (Exception e) { st = new JSONObject(); }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar now = Calendar.getInstance();
        String today = f.format(now.getTime());
        Calendar y = Calendar.getInstance(); y.add(Calendar.DAY_OF_YEAR, -1);
        String yesterday = f.format(y.getTime());
        String sd = st.optString("date", "");
        int hour = now.get(Calendar.HOUR_OF_DAY);

        String nTitle, nText, openKind, say, clip;
        switch (kind) {
            case "start":
            case "follow": {
                if (today.equals(sd) && has(st.optJSONArray("ex"), id)) return; // فترة العذر
                boolean done = false;
                if (today.equals(sd)) done = has(st.optJSONArray("done"), id);
                if (!done && hour < 6) {
                    if (yesterday.equals(sd)) done = has(st.optJSONArray("done"), id);
                    else if (today.equals(sd)) done = has(st.optJSONArray("yDone"), id);
                }
                if (done) return;
                if ("start".equals(kind)) {
                    nTitle = "⏰ " + title;
                    nText = "دلوقتي وقته. عملتها؟ دوس وسجّل الإثبات.";
                    say = "حان وقت " + title + ". عملتها؟";
                    clip = "s_" + id;
                } else {
                    nTitle = "لسه ما سجّلتش: " + title;
                    nText = "وقته قرب يخلص. ما تسيبهوش يفوت — وسجّل الإثبات.";
                    say = "لسه ما سجّلتش " + title + ". وقته قرب يخلص.";
                    clip = "f_" + id;
                }
                openKind = "prove";
                break;
            }
            case "review":
            case "review2": {
                if (today.equals(sd) && st.optBoolean("reviewed", false)) return;
                nTitle = "review".equals(kind) ? "⚖ وقت المحاسبة" : "⚖ لسه ما حاسبتش نفسك النهاردة";
                nText = "«حاسبوا أنفسكم قبل أن تحاسبوا» — راجع يومك قبل ما تنام.";
                openKind = "review";
                clip = kind;
                say = "review".equals(kind) ? "وقت المحاسبة. حاسبوا أنفسكم قبل أن تحاسبوا." : "لسه ما حاسبتش نفسك النهاردة.";
                break;
            }
            case "ycheck": {
                boolean reviewed;
                if (today.equals(sd)) reviewed = st.optBoolean("yReviewed", false) || !st.optBoolean("hasHistory", false);
                else if (yesterday.equals(sd)) reviewed = st.optBoolean("reviewed", false);
                else reviewed = sd.isEmpty(); // never opened: nothing to review
                if (reviewed) return;
                nTitle = "⚖ ما حاسبتش نفسك امبارح";
                nText = "اقفل حساب امبارح الأول، وبعدين ابدأ يومك.";
                openKind = "yreview";
                say = "ما حاسبتش نفسك امبارح. اقفل حساب امبارح الأول.";
                clip = "ycheck";
                break;
            }
            case "test": {
                nTitle = "🔔 بوصلة قلبك — إشعار تجريبي";
                nText = "لو شايف الرسالة دي على الموبايل أو الساعة، التنبيهات شغالة ✓";
                openKind = "test";
                say = "بوصلة قلبك. التنبيهات الناطقة شغالة، الحمد لله.";
                clip = "test";
                break;
            }
            default:
                return;
        }
        if (!"test".equals(kind)) {
            long w = waitMs(c);
            if (w > 0) { defer(c, in, kind, in.getIntExtra("orig", code), w); return; }
        }
        int nid = in.getIntExtra("orig", code);
        boolean shown = notify(c, nid, nTitle, nText, openKind, id, kind, title);
        if (shown && !"test".equals(kind)) mark(c);
        if (shown && Speaker.enabled(c)) Speaker.say(c, clip, say, goAsync());
    }

    static final String CH_DHIKR = "bousla_dhikr";

    /** Minimum minutes between two notifications (0 = off). */
    static long gapMs(Context c) { return Store.p(c).getInt("gap", 10) * 60_000L; }
    static void mark(Context c) { Store.p(c).edit().putLong("lastNotif", System.currentTimeMillis()).apply(); }
    /** ms to wait before another notification may come, 0 if it can come now. */
    static long waitMs(Context c) {
        long g = gapMs(c); if (g <= 0) return 0;
        long w = Store.p(c).getLong("lastNotif", 0) + g - System.currentTimeMillis();
        return w > 0 && w <= g ? w : 0;
    }
    /** Puts this alarm off until the gap has passed (one time, keeps its daily schedule). */
    static void defer(Context c, Intent in, String kind, int code, long wait) {
        long first = in.getLongExtra("dfrom", System.currentTimeMillis());
        if (System.currentTimeMillis() - first > 90 * 60_000L) return; // waited long enough: give up quietly
        Intent again = Scheduler.alarmIntent(c, kind, in.getStringExtra("id"), in.getStringExtra("title"), in.getIntExtra("h", 0), in.getIntExtra("m", 0), false);
        int dc = 700000 + (code % 100000);
        again.putExtra("code", dc);
        again.putExtra("dfrom", first);
        again.putExtra("orig", code);
        Scheduler.setAt(c, dc, again, System.currentTimeMillis() + wait + 15_000L);
    }

    /** Swiping the notification away stops its voice. */
    static PendingIntent hush(Context c) {
        Intent i = new Intent(c, AlarmReceiver.class).putExtra("kind", "hush");
        return PendingIntent.getBroadcast(c, 7990, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static final String CH_WISDOM = "bousla_wisdom";

    static void startAdhan(Context c, String prayerName) {
        String sheikh = "refaat";
        try { sheikh = new JSONObject(Store.p(c).getString("adhan", "{}")).optString("sheikh", "refaat"); } catch (Exception ignored) {}
        Intent s = new Intent(c, AdhanService.class);
        s.putExtra("prayer", prayerName);
        s.putExtra("sheikh", sheikh);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s); else c.startService(s);
        } catch (Exception e) {
            // foreground start not allowed: at least show the notification
            notify(c, 7996, "🕌 حان الآن موعد أذان " + prayerName, "«حيّ على الصلاة، حيّ على الفلاح»", "prayer", "", "adhan", prayerName);
        }
    }

    /** Same pick as the page: local day number modulo the list length. */
    static boolean notifyWisdom(Context c) {
        try {
            JSONObject w = new JSONObject(Store.p(c).getString("wisdom", "{}"));
            JSONArray items = w.optJSONArray("items");
            if (items == null || items.length() == 0) return false;
            long now = System.currentTimeMillis();
            long day = (now + java.util.TimeZone.getDefault().getOffset(now)) / 86400000L;
            JSONObject it = items.getJSONObject((int) (day % items.length()));
            String k = it.optString("k");
            String title = "q".equals(k) ? "📖 آية اليوم" : "h".equals(k) ? "💬 حديث اليوم" : "📜 حكمة من السلف";
            String body = it.optString("t") + "\n— " + it.optString("src");
            if (Build.VERSION.SDK_INT >= 33 &&
                    c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_WISDOM) == null) {
                NotificationChannel ch = new NotificationChannel(CH_WISDOM, "حكمة اليوم", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("آية أو حديث أو حكمة من السلف كل يوم");
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(c, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            open.putExtra("kind", "wisdom");
            open.putExtra("id", "");
            PendingIntent pi = PendingIntent.getActivity(c, 7998, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH_WISDOM) : new Notification.Builder(c);
            b.setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(it.optString("t"))
                    .setStyle(new Notification.BigTextStyle().bigText(body)).setAutoCancel(true).setDeleteIntent(hush(c)).setContentIntent(pi);
            nm.notify(7998, b.build());
            return true;
        } catch (Exception e) { return false; }
    }

    /** Rotates through the chosen phrases. */
    static String[] nextDhikr(Context c) {
        try {
            JSONObject d = new JSONObject(Store.p(c).getString("dhikr", "{}"));
            JSONArray l = d.optJSONArray("list");
            if (l == null || l.length() == 0) return null;
            int idx = Store.p(c).getInt("dhikrIdx", 0);
            Store.p(c).edit().putInt("dhikrIdx", idx + 1).apply();
            JSONArray ids = d.optJSONArray("ids");
            int k = idx % l.length();
            String clip = ids != null && k < ids.length() && ids.optInt(k, -1) >= 0 ? "dk" + ids.optInt(k) : null;
            return new String[]{l.optString(k), clip};
        } catch (Exception e) { return null; }
    }

    static boolean notifyDhikr(Context c, String phrase) {
        if (Build.VERSION.SDK_INT >= 33 &&
                c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH_DHIKR) == null) {
            NotificationChannel ch = new NotificationChannel(CH_DHIKR, "ذكر على مدار اليوم", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("تذكير بذكر قصير كل فترة");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("kind", "dhikr");
        open.putExtra("id", "");
        PendingIntent pi = PendingIntent.getActivity(c, 7999, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH_DHIKR) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("📿 " + phrase)
                .setContentText("ذكر خفيف على اللسان، ثقيل في الميزان")
                .setAutoCancel(true)
                .setDeleteIntent(hush(c)).setContentIntent(pi)
                .setCategory(Notification.CATEGORY_REMINDER);
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(20 * 60 * 1000L);
        nm.notify(7999, b.build());
        return true;
    }

    private static boolean has(JSONArray a, String id) {
        if (a == null || id == null) return false;
        for (int i = 0; i < a.length(); i++) if (id.equals(a.optString(i))) return true;
        return false;
    }

    private static boolean notify(Context c, int code, String title, String text, String openKind,
                               String id, String origKind, String actTitle) {
        if (Build.VERSION.SDK_INT >= 33 &&
                c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        ensureChannel(c);
        int notifId = 7000 + (code % 100000);

        Intent open = new Intent(c, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("kind", openKind);
        open.putExtra("id", id == null ? "" : id);
        PendingIntent piOpen = PendingIntent.getActivity(c, notifId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setDeleteIntent(hush(c)).setContentIntent(piOpen)
                .setCategory(Notification.CATEGORY_REMINDER);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL);

        String btn = "prove".equals(openKind) ? "عملتها ✓ سجّل الإثبات" : "حاسب نفسك دلوقتي";
        b.addAction(new Notification.Action.Builder(null, btn, piOpen).build());

        Intent sn = new Intent(c, AlarmReceiver.class);
        sn.putExtra("kind", "snooze");
        sn.putExtra("orig", "review2".equals(origKind) ? "review" : origKind);
        sn.putExtra("id", id);
        sn.putExtra("title", actTitle);
        sn.putExtra("code", code);
        sn.putExtra("notif", notifId);
        PendingIntent piSnooze = PendingIntent.getBroadcast(c, 800000 + (code % 100000), sn,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(null, "لسه — فكّرني بعد ٣٠ د", piSnooze).build());

        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(notifId, b.build());
        return true;
    }
}
