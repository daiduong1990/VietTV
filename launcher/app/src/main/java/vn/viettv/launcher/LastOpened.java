package vn.viettv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateUtils;

import java.util.Calendar;
import java.util.Locale;

/**
 * When an app was last opened from Home, for the cinematic shelf's "Last opened" line: the launcher notes the
 * time whenever it opens an app. No permission and nothing read from other apps, so an app started some other
 * way (a remote's own Netflix button) shows the last time Home opened it.
 */
final class LastOpened {
    private static final String PREFS = "last_opened";

    private LastOpened() {
    }

    /** Notes that Home just opened this app. */
    static void record(Context ctx, String pkg) {
        prefs(ctx).edit().putLong(pkg, System.currentTimeMillis()).apply();
    }

    /** Milliseconds since the epoch, or 0 when Home never opened the app. */
    static long get(Context ctx, String pkg) {
        return pkg == null ? 0L : prefs(ctx).getLong(pkg, 0L);
    }

    /**
     * "5 minutes ago" within the hour, then the day and the time: "today, 11:40 am", "yesterday, 9:40 pm",
     * "3 days ago, 9:40 pm", "Oct 12, 9:40 pm". The parts are Android's own wording in the TV's language.
     */
    static String describe(Context ctx, long when) {
        long now = System.currentTimeMillis();
        // Under a minute Android says "0 minutes ago"; one minute reads better.
        when = Math.min(when, now - DateUtils.MINUTE_IN_MILLIS);
        if ("vi".equalsIgnoreCase(Locale.getDefault().getLanguage())) {
            if (now - when < DateUtils.HOUR_IN_MILLIS) {
                long mins = Math.max(1L, (now - when) / DateUtils.MINUTE_IN_MILLIS);
                return mins + " phút trước";
            }
            Calendar calNow = Calendar.getInstance();
            calNow.setTimeInMillis(now);
            Calendar calWhen = Calendar.getInstance();
            calWhen.setTimeInMillis(when);
            String timeStr = String.format(Locale.US, "%02d:%02d",
                    calWhen.get(Calendar.HOUR_OF_DAY), calWhen.get(Calendar.MINUTE));
            Calendar dayNow = (Calendar) calNow.clone();
            dayNow.set(Calendar.HOUR_OF_DAY, 0);
            dayNow.set(Calendar.MINUTE, 0);
            dayNow.set(Calendar.SECOND, 0);
            dayNow.set(Calendar.MILLISECOND, 0);
            Calendar dayWhen = (Calendar) calWhen.clone();
            dayWhen.set(Calendar.HOUR_OF_DAY, 0);
            dayWhen.set(Calendar.MINUTE, 0);
            dayWhen.set(Calendar.SECOND, 0);
            dayWhen.set(Calendar.MILLISECOND, 0);
            long daysDiff = Math.round((dayNow.getTimeInMillis() - dayWhen.getTimeInMillis())
                    / (double) DateUtils.DAY_IN_MILLIS);
            if (daysDiff <= 0) return "hôm nay, " + timeStr;
            if (daysDiff == 1) return "hôm qua, " + timeStr;
            if (daysDiff < 7) return daysDiff + " ngày trước, " + timeStr;
            return calWhen.get(Calendar.DAY_OF_MONTH) + " thg " + (calWhen.get(Calendar.MONTH) + 1) + ", " + timeStr;
        }
        if (now - when < DateUtils.HOUR_IN_MILLIS) {
            return DateUtils.getRelativeTimeSpanString(when, now, DateUtils.MINUTE_IN_MILLIS).toString();
        }
        String day = DateUtils.getRelativeTimeSpanString(when, now, DateUtils.DAY_IN_MILLIS).toString();
        // It follows "Last opened" mid-sentence: "yesterday", not "Yesterday". Month names keep their capital.
        if (Locale.getDefault().getLanguage().equals("en") && (day.startsWith("Yesterday") || day.startsWith("Today"))) {
            day = Character.toLowerCase(day.charAt(0)) + day.substring(1);
        }
        return day + ", " + DateUtils.formatDateTime(ctx, when, DateUtils.FORMAT_SHOW_TIME);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
