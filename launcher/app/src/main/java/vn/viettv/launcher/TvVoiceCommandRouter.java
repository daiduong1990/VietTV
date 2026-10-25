package vn.viettv.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Vietnamese TV voice command router, Android action executor, and Main-Looper
 * Sleep Timer manager. Designed with zero resource/AndroidX dependencies so it compiles and runs
 * identically on Android TV (API 26+) and standalone JDK 17 unit tests.
 */
public final class TvVoiceCommandRouter {

    private static final String TAG = "TvVoiceCmdRouter";
    private static final Locale VI_LOCALE = new Locale("vi", "VN");

    public static final String PKG_SMARTTUBE_STABLE = "org.smarttube.stable";
    public static final String PKG_SMARTTUBE_BETA = "org.smarttube.beta";
    public static final String PKG_SMARTTUBE_LEGACY = "com.liskovsoft.videomanager";
    public static final String PKG_YOUTUBE_TV = "com.google.android.youtube.tv";
    public static final String PKG_VTV_GO = "vn.vtv.vtvgo";
    public static final String PKG_VTV_GO_TV = "vn.vtv.vtvgotv";
    public static final String PKG_FPT_PLAY = "com.fptplay.ottbox";
    public static final String PKG_VIEON = "tv.vieon.vienthonga";
    public static final String PKG_TV360 = "com.tv360.tv";
    public static final String PKG_NETFLIX = "com.netflix.mediaclient";
    public static final String TARGET_LAUNCHER_SETTINGS = "vn.viettv.launcher.LauncherSettingsActivity";

    public static final int XIAOMI_INPUT_AV = 2;
    public static final int XIAOMI_INPUT_HDMI1 = 23;
    public static final int XIAOMI_INPUT_HDMI2 = 24;
    public static final int XIAOMI_INPUT_HDMI3 = 25;
    public static final int XIAOMI_INPUT_HDMI4 = 26;

    public interface SleepExecutor {
        boolean sleep(Context context);
    }

    public enum ActionType {
        POWER_SLEEP,
        SLEEP_TIMER_SET,
        SLEEP_TIMER_CANCEL,
        VOLUME_SET,
        VOLUME_UP,
        VOLUME_DOWN,
        VOLUME_MUTE,
        VOLUME_UNMUTE,
        GO_HOME,
        SWITCH_INPUT,
        OPEN_APP_OR_SETTINGS
    }

    public static final class ParsedCommand {
        public final ActionType type;
        public final int intValue;
        public final String targetId;
        public final boolean inlineOverlay;

        public ParsedCommand(ActionType type, int intValue, String targetId, boolean inlineOverlay) {
            this.type = type;
            this.intValue = intValue;
            this.targetId = targetId == null ? "" : targetId;
            this.inlineOverlay = inlineOverlay;
        }

        @Override
        public String toString() {
            return "ParsedCommand{type=" + type
                    + ", intValue=" + intValue
                    + ", targetId='" + targetId + '\''
                    + ", inlineOverlay=" + inlineOverlay + '}';
        }
    }

    public static final class CommandResult {
        public final boolean handled;
        public final String title;
        public final String subtitle;
        public final long dismissDelayMs;

        public CommandResult(boolean handled, String title, String subtitle, long dismissDelayMs) {
            this.handled = handled;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.dismissDelayMs = Math.max(0L, dismissDelayMs);
        }
    }

    private static volatile SleepExecutor sleepExecutor;
    private static volatile Handler sleepHandler;
    private static volatile long sleepDeadlineElapsedMs = 0L;
    private static Runnable warningRunnable;
    private static Runnable sleepActionRunnable;

    private static final String[] POLITE_PREFIXES = new String[]{
            "tivi ơi ",
            "tv ơi ",
            "ê tivi ",
            "này tivi ",
            "vui lòng ",
            "làm ơn ",
            "cho mình ",
            "cho tôi ",
            "giúp mình ",
            "giúp tôi ",
            "hãy ",
            "ơi "
    };

    private static final String[] POLITE_SUFFIXES = new String[]{
            " giúp tôi",
            " cho tôi",
            " giùm",
            " liền",
            " ngay",
            " nhé",
            " nha",
            " với",
            " đi"
    };

    private static final String[] SEARCH_QUERY_GUARDS = new String[]{
            "tìm kiếm ",
            "tìm ",
            "nhạc ",
            "bài hát ",
            "ca khúc ",
            "karaoke ",
            "phim ",
            "video ",
            "clip ",
            "cách ",
            "hướng dẫn ",
            "review ",
            "kể chuyện "
    };

    private static final Set<String> POWER_SLEEP_EXACT = immutableSet(
            "tắt tivi",
            "tắt tv",
            "tắt màn hình",
            "tắt màn hình tivi",
            "tắt nguồn",
            "tắt nguồn tivi",
            "tắt máy",
            "cho tivi đi ngủ",
            "cho tv đi ngủ",
            "cho tivi ngủ",
            "tivi đi ngủ",
            "đi ngủ",
            "đi ngủ thôi"
    );

    private static final Set<String> SLEEP_TIMER_CANCEL_EXACT = immutableSet(
            "hủy hẹn giờ",
            "tắt hẹn giờ",
            "bỏ hẹn giờ",
            "xóa hẹn giờ",
            "hủy hẹn giờ tắt",
            "hủy hẹn giờ tắt tivi",
            "hủy hẹn giờ ngủ",
            "tắt hẹn giờ ngủ",
            "tắt hẹn giờ tắt tivi"
    );

    private static final Set<String> VOLUME_MUTE_EXACT = immutableSet(
            "tắt tiếng",
            "tắt tiếng tivi",
            "tắt âm",
            "tắt âm thanh",
            "tắt loa",
            "câm tiếng",
            "im lặng",
            "mute"
    );

    private static final Set<String> VOLUME_UNMUTE_EXACT = immutableSet(
            "bật tiếng",
            "bật tiếng tivi",
            "mở tiếng",
            "bật âm",
            "bật âm thanh",
            "mở âm thanh",
            "bật loa",
            "mở loa"
    );

    private static final Set<String> VOLUME_UP_EXACT = immutableSet(
            "tăng âm lượng",
            "tăng âm lượng tivi",
            "tăng âm",
            "tăng tiếng",
            "to lên",
            "mở to lên",
            "vặn to lên",
            "cho to lên",
            "lớn lên",
            "mở lớn lên",
            "tăng loa"
    );

    private static final Set<String> VOLUME_DOWN_EXACT = immutableSet(
            "giảm âm lượng",
            "giảm âm lượng tivi",
            "hạ âm lượng",
            "hạ âm lượng tivi",
            "giảm âm",
            "giảm tiếng",
            "nhỏ xuống",
            "nhỏ lại",
            "mở nhỏ xuống",
            "vặn nhỏ xuống",
            "cho nhỏ xuống",
            "cho nhỏ lại",
            "nhỏ tiếng lại",
            "giảm loa"
    );

    private static final Set<String> GO_HOME_EXACT = immutableSet(
            "về trang chủ",
            "trang chủ",
            "mở trang chủ",
            "về màn hình chính",
            "màn hình chính",
            "quay về trang chủ"
    );

    private static final Set<String> OPEN_SMARTTUBE_EXACT = immutableSet(
            "mở smarttube",
            "mở smart tube",
            "bật smarttube",
            "bật smart tube",
            "mở youtube",
            "bật youtube",
            "vào youtube",
            "vào smarttube",
            "vào smart tube"
    );

    private static final Set<String> OPEN_VTV_EXACT = immutableSet(
            "mở vtv",
            "mở vtv go",
            "mở vtvgo",
            "bật vtv",
            "bật vtv go",
            "bật vtvgo",
            "mở truyền hình",
            "xem truyền hình"
    );

    private static final Set<String> OPEN_NETFLIX_EXACT = immutableSet(
            "mở netflix",
            "bật netflix",
            "vào netflix"
    );

    private static final Set<String> OPEN_FPT_EXACT = immutableSet(
            "mở fpt",
            "mở fpt play",
            "bật fpt",
            "bật fpt play",
            "vào fpt",
            "vào fpt play"
    );

    private static final Set<String> OPEN_VIEON_EXACT = immutableSet(
            "mở vieon",
            "mở vie on",
            "bật vieon",
            "bật vie on",
            "vào vieon",
            "vào vie on"
    );

    private static final Set<String> OPEN_TV360_EXACT = immutableSet(
            "mở tv360",
            "mở tv 360",
            "bật tv360",
            "bật tv 360",
            "vào tv360",
            "vào tv 360"
    );

    private static final Set<String> OPEN_LAUNCHER_UI_SETTINGS_EXACT = immutableSet(
            "mở cài đặt giao diện",
            "tùy chỉnh giao diện",
            "cài đặt giao diện",
            "mở tùy chỉnh giao diện"
    );

    private static final Set<String> OPEN_SETTINGS_EXACT = immutableSet(
            "mở cài đặt",
            "cài đặt tivi",
            "cài đặt tv",
            "mở cài đặt tivi",
            "mở cài đặt tv",
            "vào cài đặt",
            "vào cài đặt tivi"
    );

    private static final Set<String> OPEN_WIFI_EXACT = immutableSet(
            "mở wifi",
            "mở wi fi",
            "bật wifi",
            "bật wi fi",
            "vào wifi",
            "vào wi fi",
            "cài đặt wifi",
            "cài đặt wi fi",
            "mở cài đặt wifi",
            "mở cài đặt wi fi",
            "vào cài đặt wifi",
            "vào cài đặt wi fi",
            "cài đặt mạng",
            "mở cài đặt mạng",
            "vào cài đặt mạng"
    );

    private static final Pattern PUNCTUATION_STRIP =
            Pattern.compile("[.,!?…:;'\u2018\u2019\u201C\u201D\"()\\[\\]]+|(?<=[\\p{L}\\d])-|-(?!\\d)");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private static final Pattern TIMER_PAT_1 = Pattern.compile(
            "^hẹn giờ (?:tắt tivi |tắt tv |tắt màn hình |tắt |ngủ )?(?:sau |trong )?(.+)$");
    private static final Pattern TIMER_PAT_2 = Pattern.compile(
            "^(.+) nữa tắt (?:tivi|tv|màn hình|nguồn)$");
    private static final Pattern TIMER_PAT_3 = Pattern.compile(
            "^tắt (?:tivi|tv|màn hình|nguồn) (?:sau|trong) (.+)$");
    private static final Pattern TIMER_PAT_4 = Pattern.compile(
            "^sau (.+) (?:thì )?tắt (?:tivi|tv|màn hình)$");

    private static final Pattern DUR_COMPACT_HM = Pattern.compile(
            "^(\\d+)\\s*h(?:\\s*(\\d+)\\s*(?:phút|p)?)?$");
    private static final Pattern DUR_COMPACT_M = Pattern.compile(
            "^(\\d+)\\s*(?:phút|p)$");
    private static final Pattern DUR_HOURS_HALF = Pattern.compile(
            "^(.+?)\\s+(?:tiếng|giờ)\\s+rưỡi$");
    private static final Pattern DUR_HOURS_MINS = Pattern.compile(
            "^(.+?)\\s+(?:tiếng|giờ)(?:\\s+(.+?)(?:\\s*(?:phút|p))?)?$");
    private static final Pattern DUR_MINS_ONLY = Pattern.compile(
            "^(.+?)\\s+(?:phút|p)$");

    private static final Pattern VOL_UP_DELTA_PAT = Pattern.compile(
            "^(?:tăng âm lượng|to lên|tăng tiếng) thêm (.+?)(?: phần trăm| %|%)?$");
    private static final Pattern VOL_DOWN_EXPLICIT_DELTA_PAT = Pattern.compile(
            "^(?:giảm âm lượng|hạ âm lượng|nhỏ xuống) (?:thêm |đi )(.+?)(?: phần trăm| %|%)?$");
    private static final Pattern VOL_DOWN_NHO_XUONG_PAT = Pattern.compile(
            "^nhỏ xuống (.+?)(?: phần trăm| %|%)?$");
    private static final Pattern VOL_SET_PAT = Pattern.compile(
            "^(?:chỉnh |đặt |mở |vặn |cho |tăng |giảm |hạ )?(?:mức )?"
                    + "(?:âm lượng|âm thanh|tiếng|loa|volume)"
                    + "(?: lên| xuống| về| ở| mức| số| là)? (.+?)(?: phần trăm| %|%)?$");

    private static final Pattern HDMI_PAT = Pattern.compile(
            "^(?:mở |chuyển |chuyển sang |bật |vào )?(?:cổng |nguồn )?hdmi ?(1|2|3|4|một|hai|ba|bốn|tư)$");
    private static final Pattern AV_PAT = Pattern.compile(
            "^(?:(?:mở |chuyển |chuyển sang |bật |vào )(?:cổng |nguồn )?av|cổng av)$");

    private TvVoiceCommandRouter() {
    }

    private static Set<String> immutableSet(String... items) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(items)));
    }

    private static synchronized Handler getSleepHandler() {
        if (sleepHandler == null) {
            sleepHandler = new Handler(Looper.getMainLooper());
        }
        return sleepHandler;
    }

    public static void registerSleepExecutor(SleepExecutor exec) {
        sleepExecutor = exec;
    }

    public static boolean triggerTvSleep(Context context) {
        SleepExecutor exec = sleepExecutor;
        if (exec != null) {
            return exec.sleep(context);
        }
        try {
            Object result = Class.forName("vn.viettv.launcher.HomeKeyService")
                    .getMethod("sleepTvWithSelfHeal", Context.class)
                    .invoke(null, context);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable t) {
            Log.w(TAG, "Reflective sleepTvWithSelfHeal fallback failed: " + t.getMessage());
            return false;
        }
    }

    public static synchronized int scheduleSleepTimer(Context context, int minutes) {
        int clamped = Math.max(1, Math.min(480, minutes));
        Handler handler = getSleepHandler();
        if (warningRunnable != null) {
            handler.removeCallbacks(warningRunnable);
            warningRunnable = null;
        }
        if (sleepActionRunnable != null) {
            handler.removeCallbacks(sleepActionRunnable);
            sleepActionRunnable = null;
        }

        final Context appCtx = context != null
                ? (context.getApplicationContext() != null ? context.getApplicationContext() : context)
                : null;
        long delayMs = clamped * 60_000L;
        sleepDeadlineElapsedMs = SystemClock.elapsedRealtime() + delayMs;

        if (clamped >= 2 && appCtx != null) {
            warningRunnable = () -> Toast.makeText(
                    appCtx,
                    "⏰ Tivi sẽ tự động tắt sau 1 phút nữa",
                    Toast.LENGTH_LONG
            ).show();
            handler.postDelayed(warningRunnable, delayMs - 60_000L);
        }

        sleepActionRunnable = () -> {
            synchronized (TvVoiceCommandRouter.class) {
                sleepDeadlineElapsedMs = 0L;
                warningRunnable = null;
                sleepActionRunnable = null;
            }
            triggerTvSleep(appCtx);
        };
        handler.postDelayed(sleepActionRunnable, delayMs);
        return clamped;
    }

    public static synchronized boolean cancelSleepTimer() {
        boolean wasActive = sleepDeadlineElapsedMs > 0L
                && sleepDeadlineElapsedMs > SystemClock.elapsedRealtime();
        sleepDeadlineElapsedMs = 0L;
        Handler handler = sleepHandler;
        if (handler != null) {
            if (warningRunnable != null) {
                handler.removeCallbacks(warningRunnable);
            }
            if (sleepActionRunnable != null) {
                handler.removeCallbacks(sleepActionRunnable);
            }
        }
        warningRunnable = null;
        sleepActionRunnable = null;
        return wasActive;
    }

    public static int getRemainingSleepMinutes() {
        long deadline = sleepDeadlineElapsedMs;
        if (deadline <= 0L) {
            return 0;
        }
        long remMs = deadline - SystemClock.elapsedRealtime();
        if (remMs <= 0L) {
            sleepDeadlineElapsedMs = 0L;
            return 0;
        }
        return (int) Math.max(1L, (remMs + 59_999L) / 60_000L);
    }

    /**
     * Normalizes transcript for anchored Vietnamese command matching, returning {@code null}
     * immediately if the transcript starts with media/search query prefixes.
     */
    public static String normalizeForCommand(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.toLowerCase(VI_LOCALE);
        s = PUNCTUATION_STRIP.matcher(s).replaceAll(" ");
        s = MULTI_SPACE.matcher(s.trim()).replaceAll(" ");
        if (s.isEmpty()) {
            return null;
        }
        if (startsWithSearchGuard(s)) {
            return null;
        }

        for (int pass = 0; pass < 3; pass++) {
            boolean changed = false;
            for (String prefix : POLITE_PREFIXES) {
                if (s.startsWith(prefix) && s.length() > prefix.length()) {
                    s = s.substring(prefix.length()).trim();
                    changed = true;
                    break;
                }
            }
            if (startsWithSearchGuard(s)) {
                return null;
            }
            for (String suffix : POLITE_SUFFIXES) {
                if (s.endsWith(suffix) && s.length() > suffix.length()) {
                    s = s.substring(0, s.length() - suffix.length()).trim();
                    changed = true;
                    break;
                }
            }
            if (!changed) {
                break;
            }
        }

        if (s.isEmpty() || startsWithSearchGuard(s)) {
            return null;
        }
        return s;
    }

    private static boolean startsWithSearchGuard(String normalized) {
        for (String guard : SEARCH_QUERY_GUARDS) {
            if (normalized.startsWith(guard)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses spoken Vietnamese TV control commands using strict full-string anchored rules.
     * Returns {@code null} for regular video/music search queries so SmartTube search is never hijacked.
     */
    public static ParsedCommand parseCommand(String rawTranscript) {
        String s = normalizeForCommand(rawTranscript);
        if (s == null) {
            return null;
        }

        // 1. POWER_SLEEP
        if (POWER_SLEEP_EXACT.contains(s)) {
            return new ParsedCommand(ActionType.POWER_SLEEP, 0, "", false);
        }

        // 2. SLEEP_TIMER_CANCEL
        if (SLEEP_TIMER_CANCEL_EXACT.contains(s)) {
            return new ParsedCommand(ActionType.SLEEP_TIMER_CANCEL, 0, "", true);
        }

        // 3. SLEEP_TIMER_SET (1..480 minutes)
        Integer timerMins = matchSleepTimerMinutes(s);
        if (timerMins != null) {
            return new ParsedCommand(ActionType.SLEEP_TIMER_SET, timerMins, "", true);
        }

        // 4. VOLUME_MUTE & VOLUME_UNMUTE
        if (VOLUME_MUTE_EXACT.contains(s)) {
            return new ParsedCommand(ActionType.VOLUME_MUTE, 0, "", true);
        }
        if (VOLUME_UNMUTE_EXACT.contains(s)) {
            return new ParsedCommand(ActionType.VOLUME_UNMUTE, 0, "", true);
        }

        // 5 & 6. VOLUME_UP, VOLUME_DOWN, and VOLUME_SET
        if (VOLUME_UP_EXACT.contains(s)) {
            return new ParsedCommand(ActionType.VOLUME_UP, 5, "", true);
        }
        if (VOLUME_DOWN_EXACT.contains(s)) {
            return new ParsedCommand(ActionType.VOLUME_DOWN, 5, "", true);
        }

        Matcher upDeltaMatcher = VOL_UP_DELTA_PAT.matcher(s);
        if (upDeltaMatcher.matches()) {
            int delta = parseVietnameseNumber(upDeltaMatcher.group(1));
            if (delta >= 1 && delta <= 100) {
                return new ParsedCommand(ActionType.VOLUME_UP, Math.min(50, delta), "", true);
            }
        }

        Matcher downExplicitMatcher = VOL_DOWN_EXPLICIT_DELTA_PAT.matcher(s);
        if (downExplicitMatcher.matches()) {
            int delta = parseVietnameseNumber(downExplicitMatcher.group(1));
            if (delta >= 1 && delta <= 100) {
                return new ParsedCommand(ActionType.VOLUME_DOWN, Math.min(50, delta), "", true);
            }
        }

        Matcher downNhoXuongMatcher = VOL_DOWN_NHO_XUONG_PAT.matcher(s);
        if (downNhoXuongMatcher.matches()) {
            int delta = parseVietnameseNumber(downNhoXuongMatcher.group(1));
            if (delta >= 1 && delta <= 100) {
                return new ParsedCommand(ActionType.VOLUME_DOWN, Math.min(50, delta), "", true);
            }
        }

        Matcher volSetMatcher = VOL_SET_PAT.matcher(s);
        if (volSetMatcher.matches()) {
            int targetPct = parseVietnameseNumber(volSetMatcher.group(1));
            if (targetPct >= 0 && targetPct <= 100) {
                return new ParsedCommand(ActionType.VOLUME_SET, targetPct, "", true);
            }
        }

        // Fallback for "giảm âm lượng 10%" only if not already matched above (VOL_SET handles "giảm âm lượng 10" as set,
        // while "giảm âm lượng thêm 10" / "giảm âm lượng đi 10" / "nhỏ xuống 10" handled as delta above).

        // 7. GO_HOME
        if (GO_HOME_EXACT.contains(s)) {
            return new ParsedCommand(ActionType.GO_HOME, 0, "", false);
        }

        // 8. SWITCH_INPUT (Universal Multi-Brand HDMI 1..4 & AV)
        Matcher hdmiMatcher = HDMI_PAT.matcher(s);
        if (hdmiMatcher.matches()) {
            int portNum = parseHdmiPortToken(hdmiMatcher.group(1));
            if (portNum >= 1 && portNum <= 4) {
                return new ParsedCommand(ActionType.SWITCH_INPUT, portNum, "HDMI " + portNum, false);
            }
        }
        if (AV_PAT.matcher(s).matches()) {
            return new ParsedCommand(ActionType.SWITCH_INPUT, 0, "AV", false);
        }

        // 9. OPEN_APP_OR_SETTINGS
        String targetAppOrSetting = matchOpenTarget(s);
        if (targetAppOrSetting != null) {
            return new ParsedCommand(ActionType.OPEN_APP_OR_SETTINGS, 0, targetAppOrSetting, false);
        }

        return null;
    }

    private static int parseHdmiPortToken(String token) {
        if ("1".equals(token) || "một".equals(token)) return 1;
        if ("2".equals(token) || "hai".equals(token)) return 2;
        if ("3".equals(token) || "ba".equals(token)) return 3;
        if ("4".equals(token) || "bốn".equals(token) || "tư".equals(token)) return 4;
        return -1;
    }

    private static String matchOpenTarget(String s) {
        if (OPEN_LAUNCHER_UI_SETTINGS_EXACT.contains(s)) return TARGET_LAUNCHER_SETTINGS;
        if (OPEN_SMARTTUBE_EXACT.contains(s)) return PKG_SMARTTUBE_STABLE;
        if (OPEN_VTV_EXACT.contains(s)) return PKG_VTV_GO;
        if (OPEN_FPT_EXACT.contains(s)) return PKG_FPT_PLAY;
        if (OPEN_VIEON_EXACT.contains(s)) return PKG_VIEON;
        if (OPEN_TV360_EXACT.contains(s)) return PKG_TV360;
        if (OPEN_NETFLIX_EXACT.contains(s)) return PKG_NETFLIX;
        if (OPEN_SETTINGS_EXACT.contains(s)) return Settings.ACTION_SETTINGS;
        if (OPEN_WIFI_EXACT.contains(s)) return Settings.ACTION_WIFI_SETTINGS;
        return null;
    }

    private static Integer matchSleepTimerMinutes(String normalized) {
        Pattern[] patterns = new Pattern[]{TIMER_PAT_1, TIMER_PAT_2, TIMER_PAT_3, TIMER_PAT_4};
        for (Pattern pat : patterns) {
            Matcher m = pat.matcher(normalized);
            if (m.matches()) {
                Integer mins = parseVietnameseDurationMinutes(m.group(1));
                if (mins != null) {
                    return mins;
                }
            }
        }
        return null;
    }

    /**
     * Parses a Vietnamese duration expression into minutes clamped to {@code [1, 480]},
     * or returns {@code null} if {@code <= 0} or unparseable.
     */
    public static Integer parseVietnameseDurationMinutes(String dur) {
        if (dur == null) {
            return null;
        }
        String d = MULTI_SPACE.matcher(dur.toLowerCase(VI_LOCALE).trim()).replaceAll(" ");
        if (d.endsWith(" nữa") && d.length() > " nữa".length()) {
            d = d.substring(0, d.length() - " nữa".length()).trim();
        }
        if (d.isEmpty()) {
            return null;
        }

        if ("nửa tiếng".equals(d) || "nửa giờ".equals(d)) {
            return 30;
        }

        Matcher compactHm = DUR_COMPACT_HM.matcher(d);
        if (compactHm.matches()) {
            int h = parseSafeNonNegativeDigits(compactHm.group(1));
            int m = compactHm.group(2) != null ? parseSafeNonNegativeDigits(compactHm.group(2)) : 0;
            if (h < 0 || m < 0) {
                return null;
            }
            int total = h * 60 + m;
            return total > 0 ? Math.min(480, total) : null;
        }

        Matcher compactM = DUR_COMPACT_M.matcher(d);
        if (compactM.matches()) {
            int m = parseSafeNonNegativeDigits(compactM.group(1));
            return m > 0 ? Math.min(480, m) : null;
        }

        Matcher hoursHalf = DUR_HOURS_HALF.matcher(d);
        if (hoursHalf.matches()) {
            int h = parseVietnameseNumber(hoursHalf.group(1));
            if (h >= 1) {
                return Math.min(480, h * 60 + 30);
            }
            return null;
        }

        Matcher hoursMins = DUR_HOURS_MINS.matcher(d);
        if (hoursMins.matches()) {
            int h = parseVietnameseNumber(hoursMins.group(1));
            if (h < 0) {
                return null;
            }
            String rem = hoursMins.group(2);
            if (rem == null || rem.trim().isEmpty()) {
                return h > 0 ? Math.min(480, h * 60) : null;
            }
            int m = parseVietnameseNumber(rem.trim());
            if (m < 0) {
                return null;
            }
            int total = h * 60 + m;
            return total > 0 ? Math.min(480, total) : null;
        }

        Matcher minsOnly = DUR_MINS_ONLY.matcher(d);
        if (minsOnly.matches()) {
            int m = parseVietnameseNumber(minsOnly.group(1));
            return m > 0 ? Math.min(480, m) : null;
        }

        return null;
    }

    private static int parseSafeNonNegativeDigits(String digits) {
        if (digits == null || digits.isEmpty() || digits.length() > 4) {
            return -1;
        }
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        return Integer.parseInt(digits);
    }

    /**
     * Parses Arabic digits or spoken Vietnamese numbers in {@code [0, 999]}.
     * Returns {@code -1} if invalid or out of range.
     */
    public static int parseVietnameseNumber(String text) {
        if (text == null) {
            return -1;
        }
        String s = MULTI_SPACE.matcher(text.toLowerCase(VI_LOCALE).trim()).replaceAll(" ");
        if (s.isEmpty()) {
            return -1;
        }

        int directDigits = parseSafeNonNegativeDigits(s);
        if (directDigits >= 0) {
            return directDigits <= 999 ? directDigits : -1;
        }

        String[] tokens = s.split(" ");
        if (tokens.length == 1) {
            if ("không".equals(tokens[0])) {
                return 0;
            }
            if ("mười".equals(tokens[0]) || "chục".equals(tokens[0])) {
                return 10;
            }
            return parseSingleDigitWord(tokens[0]);
        }

        if (tokens.length >= 2 && "trăm".equals(tokens[1])) {
            int h = "không".equals(tokens[0]) ? 0 : parseSingleDigitWord(tokens[0]);
            if (h < 0 || h > 9) {
                return -1;
            }
            int hundreds = h * 100;
            if (tokens.length == 2) {
                return hundreds;
            }
            if (tokens.length == 3 && "rưỡi".equals(tokens[2])) {
                return h >= 1 ? hundreds + 50 : -1;
            }
            if (tokens.length == 4 && ("lẻ".equals(tokens[2]) || "linh".equals(tokens[2]))) {
                int u = parseSingleDigitWord(tokens[3]);
                return (u >= 1 && u <= 9) ? hundreds + u : -1;
            }
            String[] rem = Arrays.copyOfRange(tokens, 2, tokens.length);
            int subHundred = parseUnderHundredTokens(rem);
            return (subHundred >= 10 && subHundred <= 99) ? hundreds + subHundred : -1;
        }

        return parseUnderHundredTokens(tokens);
    }

    private static int parseUnderHundredTokens(String[] tokens) {
        if (tokens.length == 1) {
            int d = parseSafeNonNegativeDigits(tokens[0]);
            if (d >= 0 && d <= 99) {
                return d;
            }
            if ("mười".equals(tokens[0]) || "chục".equals(tokens[0])) {
                return 10;
            }
            return parseSingleDigitWord(tokens[0]);
        }
        if (tokens.length == 2) {
            if ("mười".equals(tokens[0])) {
                int u = parseSingleDigitWord(tokens[1]);
                return (u >= 1 && u <= 9) ? 10 + u : -1;
            }
            if ("chục".equals(tokens[1])) {
                int d = parseSingleDigitWord(tokens[0]);
                return (d >= 1 && d <= 9) ? d * 10 : -1;
            }
            if ("mươi".equals(tokens[1])) {
                int d = parseSingleDigitWord(tokens[0]);
                return (d >= 2 && d <= 9) ? d * 10 : -1;
            }
            int d = parseSingleDigitWord(tokens[0]);
            int u = parseSingleDigitWord(tokens[1]);
            if (d >= 2 && d <= 9 && u >= 1 && u <= 9) {
                return d * 10 + u;
            }
            return -1;
        }
        if (tokens.length == 3 && "mươi".equals(tokens[1])) {
            int d = parseSingleDigitWord(tokens[0]);
            int u = parseSingleDigitWord(tokens[2]);
            if (d >= 2 && d <= 9 && u >= 1 && u <= 9) {
                return d * 10 + u;
            }
        }
        return -1;
    }

    private static int parseSingleDigitWord(String word) {
        if (word == null) {
            return -1;
        }
        switch (word) {
            case "1":
            case "một":
            case "mốt":
                return 1;
            case "2":
            case "hai":
                return 2;
            case "3":
            case "ba":
                return 3;
            case "4":
            case "bốn":
            case "tư":
                return 4;
            case "5":
            case "năm":
            case "lăm":
            case "nhăm":
                return 5;
            case "6":
            case "sáu":
                return 6;
            case "7":
            case "bảy":
            case "bẩy":
                return 7;
            case "8":
            case "tám":
                return 8;
            case "9":
            case "chín":
                return 9;
            default:
                return -1;
        }
    }

    public static String formatDurationVi(int mins) {
        if (mins < 60) {
            return mins + " phút";
        }
        int h = mins / 60;
        int m = mins % 60;
        if (m == 0) {
            return h + " tiếng";
        }
        return h + " tiếng " + m + " phút";
    }

    /**
     * Executes a parsed voice command on Android TV and returns the overlay presentation feedback.
     */
    public static CommandResult executeCommand(Context context, ParsedCommand cmd) {
        if (context == null || cmd == null || cmd.type == null) {
            return new CommandResult(false, "", "", 0L);
        }
        final Context appCtx = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;

        switch (cmd.type) {
            case POWER_SLEEP: {
                cancelSleepTimer();
                getSleepHandler().postDelayed(() -> triggerTvSleep(appCtx), 280L);
                return new CommandResult(
                        true,
                        "😴 Đang tắt màn hình Tivi...",
                        "Nhấn nút Nguồn trên điều khiển để bật lại",
                        300L
                );
            }
            case SLEEP_TIMER_SET: {
                int mins = scheduleSleepTimer(context, cmd.intValue);
                return new CommandResult(
                        true,
                        "⏰ Đã hẹn giờ tắt Tivi: " + formatDurationVi(mins),
                        "Tivi sẽ báo trước 1 phút trước khi tự tắt",
                        950L
                );
            }
            case SLEEP_TIMER_CANCEL: {
                boolean hadTimer = cancelSleepTimer();
                return new CommandResult(
                        true,
                        hadTimer ? "⏰ Đã hủy hẹn giờ tắt Tivi" : "⏰ Hiện không có hẹn giờ nào đang bật",
                        "Tivi sẽ tiếp tục hoạt động bình thường",
                        900L
                );
            }
            case VOLUME_SET: {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am == null) {
                    return new CommandResult(false, "", "", 0L);
                }
                int maxVol = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                int clampedPct = Math.max(0, Math.min(100, cmd.intValue));
                int target = Math.round(clampedPct * maxVol / 100.0f);
                if (target > 0 && Build.VERSION.SDK_INT >= 23) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                }
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI);
                return new CommandResult(
                        true,
                        "🔊 Âm lượng: " + clampedPct + "%",
                        "Đã điều chỉnh âm lượng Tivi",
                        850L
                );
            }
            case VOLUME_UP:
            case VOLUME_DOWN: {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am == null) {
                    return new CommandResult(false, "", "", 0L);
                }
                boolean up = (cmd.type == ActionType.VOLUME_UP);
                int maxVol = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                int curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                int step = Math.max(1, Math.round(cmd.intValue * maxVol / 100.0f));
                if (up && Build.VERSION.SDK_INT >= 23) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                }
                int newVol = Math.max(0, Math.min(maxVol, curVol + (up ? step : -step)));
                am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, AudioManager.FLAG_SHOW_UI);
                int pct = Math.round(newVol * 100.0f / maxVol);
                return new CommandResult(
                        true,
                        (up ? "🔊 Đã tăng âm lượng lên " : "🔉 Đã giảm âm lượng xuống ") + pct + "%",
                        "Đã điều chỉnh âm lượng Tivi",
                        850L
                );
            }
            case VOLUME_MUTE: {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am == null) {
                    return new CommandResult(false, "", "", 0L);
                }
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI);
                return new CommandResult(
                        true,
                        "🔇 Đã tắt tiếng Tivi",
                        "Đã tắt âm thanh Tivi",
                        850L
                );
            }
            case VOLUME_UNMUTE: {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am == null) {
                    return new CommandResult(false, "", "", 0L);
                }
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
                return new CommandResult(
                        true,
                        "🔊 Đã bật tiếng Tivi",
                        "Đã khôi phục âm thanh Tivi",
                        850L
                );
            }
            case GO_HOME: {
                try {
                    Intent homeIntent = new Intent(context, MainActivity.class)
                            .setAction(MainActivity.ACTION_HOME_KEY)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(homeIntent);
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to open Home screen: " + t.getMessage());
                }
                return new CommandResult(true, "🏠 Đang về Trang chủ...", "", 0L);
            }
            case SWITCH_INPUT: {
                boolean isAv = "AV".equalsIgnoreCase(cmd.targetId);
                int portNum = isAv ? 0 : (cmd.intValue >= 23 ? (cmd.intValue - 22) : Math.max(1, cmd.intValue));
                Intent extIntent = TvHardwareProfiler.resolveTvInputSwitchIntent(context, portNum, isAv);
                if (extIntent == null) {
                    Toast.makeText(context, "Không tìm thấy cổng " + cmd.targetId, Toast.LENGTH_SHORT).show();
                    return new CommandResult(true, "Chưa hỗ trợ cổng " + cmd.targetId, "", 900L);
                }
                try {
                    context.startActivity(extIntent);
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to switch input to " + cmd.targetId + ": " + t.getMessage());
                    Toast.makeText(context, "Không thể chuyển sang cổng " + cmd.targetId, Toast.LENGTH_SHORT).show();
                }
                return new CommandResult(true, "📺 Đang chuyển sang " + cmd.targetId, "", 0L);
            }
            case OPEN_APP_OR_SETTINGS: {
                if (TARGET_LAUNCHER_SETTINGS.equals(cmd.targetId)) {
                    try {
                        Intent uiSettings = new Intent(context, LauncherSettingsActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(uiSettings);
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to launch LauncherSettingsActivity: " + t.getMessage());
                        Toast.makeText(context, "Không thể mở Cài đặt giao diện", Toast.LENGTH_SHORT).show();
                    }
                    return new CommandResult(true, "⚙️ Đang mở Cài đặt giao diện...", "", 0L);
                }
                if (Settings.ACTION_SETTINGS.equals(cmd.targetId)
                        || Settings.ACTION_WIFI_SETTINGS.equals(cmd.targetId)) {
                    try {
                        Intent settingsIntent = TvHardwareProfiler.resolveSystemSettingsIntent(context, cmd.targetId);
                        context.startActivity(settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to launch settings " + cmd.targetId + ": " + t.getMessage());
                        Toast.makeText(context, "Không thể mở Cài đặt Tivi", Toast.LENGTH_SHORT).show();
                    }
                    return new CommandResult(true, "⚙️ Đang mở Cài đặt...", "", 0L);
                }

                Intent appIntent = resolveAppWithFallbacks(context, cmd.targetId);
                if (appIntent != null) {
                    try {
                        context.startActivity(appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to launch app " + cmd.targetId + ": " + t.getMessage());
                        Toast.makeText(context, "Không thể mở ứng dụng", Toast.LENGTH_SHORT).show();
                    }
                    return new CommandResult(true, "🚀 Đang mở ứng dụng...", "", 0L);
                }

                Toast.makeText(context, "Ứng dụng chưa được cài đặt trên Tivi", Toast.LENGTH_SHORT).show();
                return new CommandResult(true, "Chưa cài đặt ứng dụng", "Vui lòng kiểm tra lại ứng dụng trên Tivi", 0L);
            }
            default:
                return new CommandResult(false, "", "", 0L);
        }
    }

    private static Intent resolveAppWithFallbacks(Context context, String targetId) {
        Intent primary = resolvePackageLaunchIntent(context, targetId);
        if (primary != null) {
            return primary;
        }
        if (PKG_SMARTTUBE_STABLE.equals(targetId)) {
            String[] ytFallbacks = new String[]{PKG_SMARTTUBE_BETA, PKG_SMARTTUBE_LEGACY, PKG_YOUTUBE_TV};
            for (String candidate : ytFallbacks) {
                Intent alt = resolvePackageLaunchIntent(context, candidate);
                if (alt != null) {
                    return alt;
                }
            }
        } else if (PKG_VTV_GO.equals(targetId)) {
            return resolvePackageLaunchIntent(context, PKG_VTV_GO_TV);
        }
        return null;
    }

    private static Intent resolvePackageLaunchIntent(Context context, String pkg) {
        if (context == null || pkg == null || pkg.isEmpty()) {
            return null;
        }
        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            return null;
        }
        Intent leanback = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (leanback != null) {
            return leanback;
        }
        return pm.getLaunchIntentForPackage(pkg);
    }
}
