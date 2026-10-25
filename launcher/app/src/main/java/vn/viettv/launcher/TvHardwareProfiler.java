package vn.viettv.launcher;

/*
 * Gain: Zero-dependency multi-brand TV hardware & speech backend auto-profiler.
 * Risk: Vendor ROM missing services or self-looping into VnRecognitionService.
 * Guard: Declarative lookup tables, self-package exclusion, null-safe Android API wrappers.
 * Alternative: Hardcoded Xiaomi-only checks rejected because they break Sony/TCL/Casper/Coocaa/Hisense TVs.
 */

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.provider.Settings;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Multi-brand Android TV hardware & speech service auto-profiler.
 */
public final class TvHardwareProfiler {

    private static final String TAG = "TvHwProfiler";
    public static final String LAUNCHER_PACKAGE = "vn.viettv.launcher";
    public static final String PREFS_NAME = "launcher_settings";

    public enum Brand {
        XIAOMI_MITV,
        SONY_BRAVIA,
        TCL,
        CASPER,
        COOCAA_SKYWORTH,
        HISENSE,
        PROJECTOR_CN,
        GENERIC_ANDROID_TV
    }

    private static final Map<String, Brand> KEYWORD_BRAND_MAP = createBrandMap();
    private static final String[] KEYWORD_SCAN_ORDER = new String[]{
            "fengmi", "formovie", "xgimi", "wanbo", "jmgo", "dangbei",
            "xiaomi", "redmi", "mitv", "poco", "mibox",
            "sony", "bravia",
            "tcl", "iffalcon", "thomson",
            "casper",
            "skyworth", "coocaa", "metz",
            "hisense", "toshiba"
    };

    private static final String[] EXCLUDED_CN_VOICE = new String[]{
            "com.xiaomi.voicecontrol", "com.xiaomi.mibrain",
            "com.iflytek.", "com.baidu.", "com.aispeech.", "com.sohu."
    };

    private static final String[] OFFLINE_ASR_PREFIXES = new String[]{
            "com.k2fsa.sherpa.onnx", "org.kaldi.", "org.vosk.", "com.whisperonnx.", "org.rhasspy."
    };

    private static final Set<String> GOOGLE_SPEECH_PKGS = new HashSet<>(Arrays.asList(
            "com.google.android.tts",
            "com.google.android.katniss",
            "com.google.android.googlequicksearchbox",
            "com.google.android.as"
    ));

    private TvHardwareProfiler() {
    }

    private static Map<String, Brand> createBrandMap() {
        Map<String, Brand> m = new HashMap<>();
        for (String k : new String[]{"fengmi", "formovie", "xgimi", "wanbo", "jmgo", "dangbei"}) m.put(k, Brand.PROJECTOR_CN);
        for (String k : new String[]{"xiaomi", "redmi", "mitv", "poco", "mibox"}) m.put(k, Brand.XIAOMI_MITV);
        for (String k : new String[]{"sony", "bravia"}) m.put(k, Brand.SONY_BRAVIA);
        for (String k : new String[]{"tcl", "iffalcon", "thomson"}) m.put(k, Brand.TCL);
        m.put("casper", Brand.CASPER);
        for (String k : new String[]{"skyworth", "coocaa", "metz"}) m.put(k, Brand.COOCAA_SKYWORTH);
        for (String k : new String[]{"hisense", "toshiba"}) m.put(k, Brand.HISENSE);
        return m;
    }

    public static Brand detectBrand(String manufacturer, String brand, String model) {
        String combined = safeLower(manufacturer) + " " + safeLower(brand) + " " + safeLower(model);
        for (String kw : KEYWORD_SCAN_ORDER) {
            if (combined.contains(kw)) {
                return KEYWORD_BRAND_MAP.get(kw);
            }
        }
        return Brand.GENERIC_ANDROID_TV;
    }

    public static Brand detectBrand() {
        return detectBrand(Build.MANUFACTURER, Build.BRAND, Build.MODEL);
    }

    public static boolean isXiaomiMitvRom() {
        return detectBrand() == Brand.XIAOMI_MITV;
    }

    public static boolean isXiaomiModelPrefix() {
        return safeLower(Build.MODEL).startsWith("mitv-");
    }

    public static ComponentName findExternalRecognitionService(Context context) {
        return queryBestSpeechComponent(context, true);
    }

    public static Intent findExternalRecognizeSpeechActivity(Context context) {
        ComponentName comp = queryBestSpeechComponent(context, false);
        if (comp == null) {
            return null;
        }
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).setComponent(comp);
    }

    private static ComponentName queryBestSpeechComponent(Context context, boolean service) {
        if (context == null) {
            return null;
        }
        try {
            PackageManager pm = context.getPackageManager();
            Intent query = new Intent(service
                    ? RecognitionService.SERVICE_INTERFACE
                    : RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            List<ResolveInfo> list = service
                    ? pm.queryIntentServices(query, 0)
                    : pm.queryIntentActivities(query, 0);
            return selectLowestRank(Objects.requireNonNull(list), safeLower(context.getPackageName()), service);
        } catch (Throwable t) {
            Log.d(TAG, "Speech component lookup skipped: " + t.getMessage());
            return null;
        }
    }

    private static ComponentName selectLowestRank(List<ResolveInfo> list, String selfPkg, boolean service) {
        ComponentName best = null;
        int bestRank = Integer.MAX_VALUE;
        for (ResolveInfo ri : list) {
            ComponentInfo ci = service ? ri.serviceInfo : ri.activityInfo;
            int rank = computeSpeechRank(ci, selfPkg);
            if (rank < bestRank) {
                bestRank = rank;
                best = new ComponentName(ci.packageName, ci.name);
            }
        }
        return best;
    }

    private static int computeSpeechRank(ComponentInfo ci, String selfPkg) {
        if (ci == null || !ci.enabled || ci.name == null) {
            return Integer.MAX_VALUE;
        }
        String pkgLower = safeLower(ci.packageName);
        if (pkgLower.isEmpty() || LAUNCHER_PACKAGE.equals(pkgLower) || pkgLower.equals(selfPkg)
                || startsWithAny(pkgLower, EXCLUDED_CN_VOICE)) {
            return Integer.MAX_VALUE;
        }
        if (startsWithAny(pkgLower, OFFLINE_ASR_PREFIXES)) {
            return 1;
        }
        return GOOGLE_SPEECH_PKGS.contains(pkgLower) ? 2 : 3;
    }

    public static String describeVoiceBackend(Context context) {
        String voiceMode = readPrefSafe(context, "voice_mode", "auto");
        boolean hasKey = !readPrefSafe(context, "voice_api_key", "").isEmpty()
                || !readPrefSafe(context, "voice_api_url", "").isEmpty()
                || VoiceSttClient.hasConfiguredCloudStt(context);
        if ("cloud_key".equals(voiceMode) && hasKey) {
            return "Khóa API cá nhân (Groq Whisper / Google / Local)";
        }
        if (!"cloud_key".equals(voiceMode)) {
            ComponentName ext = findExternalRecognitionService(context);
            if (ext != null) {
                return formatExternalBackend(ext.getPackageName());
            }
        }
        if (hasKey && !"on_tv".equals(voiceMode)) {
            return "Khóa API cá nhân (Miễn phí)";
        }
        return "Chưa có bộ nhận giọng nói (Bấm để cài Offline miễn phí hoặc nhập Key)";
    }

    private static String formatExternalBackend(String pkgName) {
        String pkg = safeLower(pkgName);
        if (pkg.contains("sherpa") || pkg.contains("vosk")) {
            return "Offline trực tiếp trên CPU Tivi (Sherpa-ONNX \u00B7 Miễn phí)";
        }
        if (pkg.contains("google")) {
            return "Bộ nhận giọng nói Google gốc của Tivi (Miễn phí)";
        }
        return "Dịch vụ giọng nói trên Tivi (" + pkgName + ")";
    }

    private static String readPrefSafe(Context context, String key, String fallback) {
        if (context == null) {
            return fallback;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return prefs != null ? Objects.toString(prefs.getString(key, fallback), fallback).trim() : fallback;
        } catch (Throwable t) {
            Log.d(TAG, "readPrefSafe fallback: " + t.getMessage());
            return fallback;
        }
    }

    public static Intent resolveSystemSettingsIntent(Context context, String standardAction) {
        String action = Objects.toString(standardAction, Settings.ACTION_SETTINGS);
        if (action.isEmpty()) {
            action = Settings.ACTION_SETTINGS;
        }
        Intent std = new Intent(action);
        if (context == null || !isXiaomiMitvRom()) {
            return std;
        }
        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            return std;
        }
        if (Settings.ACTION_WIFI_SETTINGS.equals(action)) {
            Intent wifi = buildXiaomiSettings("com.xiaomi.mitv.settings.WIFI_SETTINGS",
                    "com.xiaomi.mitv.settings.wifi.MitvWifiActivity");
            if (wifi.resolveActivity(pm) != null) {
                return wifi;
            }
        }
        Intent xiaomiMain = buildXiaomiSettings("com.xiaomi.mitv.settings.SETTINGS",
                "com.xiaomi.mitv.settings.entry.MainActivity");
        if (Settings.ACTION_SETTINGS.equals(action) && xiaomiMain.resolveActivity(pm) != null) {
            return xiaomiMain;
        }
        if (std.resolveActivity(pm) != null) {
            return std;
        }
        return xiaomiMain.resolveActivity(pm) != null ? xiaomiMain : std;
    }

    private static Intent buildXiaomiSettings(String action, String cls) {
        return new Intent(action).setComponent(new ComponentName("com.xiaomi.mitv.settings", cls));
    }

    /**
     * Resolves an Intent that switches live passthrough stream data to HDMI 1..4 or AV across TV brands.
     */
    public static Intent resolveTvInputSwitchIntent(Context context, int portNumber, boolean isAv) {
        PackageManager pm = context != null ? context.getPackageManager() : null;
        if (isXiaomiMitvRom()) {
            Intent xiaomiIntent = buildXiaomiInputIntent(portNumber, isAv);
            if (pm == null || xiaomiIntent.resolveActivity(pm) != null) {
                return xiaomiIntent;
            }
        }
        Intent passthrough = resolveStandardPassthroughIntent(context, pm, portNumber, isAv);
        if (passthrough != null) {
            return passthrough;
        }
        return isXiaomiMitvRom() ? buildXiaomiInputIntent(portNumber, isAv) : null;
    }

    private static Intent resolveStandardPassthroughIntent(Context context, PackageManager pm, int portNumber, boolean isAv) {
        if (context == null) {
            return null;
        }
        try {
            TvInputManager tim = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
            List<TvInputInfo> inputs = tim != null ? tim.getTvInputList() : null;
            if (inputs == null || inputs.isEmpty()) {
                return null;
            }
            TvInputInfo matched = isAv ? findAvTvInput(context, inputs) : findHdmiTvInput(context, inputs, portNumber);
            if (matched == null) {
                return null;
            }
            Intent tvIntent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(TvContract.buildChannelUriForPassthroughInput(matched.getId()),
                            "vnd.android.cursor.item/channel")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return (pm == null || tvIntent.resolveActivity(pm) != null) ? tvIntent : null;
        } catch (Throwable t) {
            Log.d(TAG, "Passthrough stream data lookup skipped: " + t.getMessage());
            return null;
        }
    }

    private static TvInputInfo findAvTvInput(Context context, List<TvInputInfo> inputs) {
        for (TvInputInfo info : inputs) {
            if (info == null) {
                continue;
            }
            int type = info.getType();
            if (type == TvInputInfo.TYPE_COMPOSITE || type == TvInputInfo.TYPE_COMPONENT) {
                return info;
            }
            String label = loadInputLabelLower(context, info);
            if (label.equals("av") || label.contains("composite")) {
                return info;
            }
        }
        return null;
    }

    private static TvInputInfo findHdmiTvInput(Context context, List<TvInputInfo> inputs, int portNumber) {
        List<TvInputInfo> hdmiList = new ArrayList<>();
        boolean xiaomiRom = isXiaomiMitvRom();
        for (TvInputInfo info : inputs) {
            if (info != null && info.getType() == TvInputInfo.TYPE_HDMI && info.getParentId() == null) {
                if (xiaomiRom && safeLower(info.getId()).endsWith("/hw8")) {
                    continue;
                }
                hdmiList.add(info);
            }
        }
        // Maintain stable comparator ordering by input ID for deterministic port indexing
        hdmiList.sort((a, b) -> safeLower(a.getId()).compareTo(safeLower(b.getId())));
        for (TvInputInfo info : hdmiList) {
            if (matchesHdmiPort(context, info, portNumber)) {
                return info;
            }
        }
        int idx = portNumber - 1;
        return (idx >= 0 && idx < hdmiList.size()) ? hdmiList.get(idx) : null;
    }

    private static boolean matchesHdmiPort(Context context, TvInputInfo info, int portNumber) {
        String idLower = safeLower(info.getId());
        String label = loadInputLabelLower(context, info);
        String tokenSpaced = "hdmi " + portNumber;
        String tokenCompact = "hdmi" + portNumber;
        if (label.contains(tokenSpaced) || label.contains(tokenCompact) || idLower.contains(tokenCompact)) {
            return true;
        }
        return portNumber >= 1 && portNumber <= 3 && idLower.endsWith("/hw" + (portNumber + 4));
    }

    private static String loadInputLabelLower(Context context, TvInputInfo info) {
        try {
            CharSequence cs = info.loadLabel(context);
            return cs != null ? safeLower(cs.toString()) : "";
        } catch (Throwable t) {
            Log.d(TAG, "Input label fallback: " + t.getMessage());
            return "";
        }
    }

    private static Intent buildXiaomiInputIntent(int portNumber, boolean isAv) {
        int safePort = Math.max(1, Math.min(4, portNumber));
        int xiaomiCode = isAv ? 2 : (22 + safePort);
        return new Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY")
                .setClassName("com.xiaomi.mitv.tvplayer", "com.xiaomi.mitv.tvplayer.ExternalSourceActivity")
                .putExtra("input", xiaomiCode)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static boolean startsWithAny(String target, String[] prefixes) {
        for (String p : prefixes) {
            if (target.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static String safeLower(String s) {
        return Objects.toString(s, "").trim().toLowerCase(Locale.ROOT);
    }
}
