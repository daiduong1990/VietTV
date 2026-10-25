package vn.viettv.launcher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import java.util.Locale;

/**
 * Multi-brand self-healing boot & resume guard.
 * Applies Xiaomi MIUI TV power/standby workarounds strictly on Xiaomi ROMs while keeping
 * Sony, TCL, Casper, Coocaa, and Hisense system settings untouched.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "TvHomeBootGuard";
    private static final String HOME_SERVICE = "vn.viettv.launcher/.HomeKeyService";
    private static final String CLOCK_DREAM = "vn.viettv.launcher/.ClockDream";
    private static final String VN_IME_SERVICE = "vn.viettv.launcher/.VnTvImeService";
    private static final String VN_RECOG_SERVICE = "vn.viettv.launcher/.VnRecognitionService";
    private static final String VN_VOICE_ASSIST = "vn.viettv.launcher/.VoiceSearchActivity";
    private static final String SOGOU_TV_IME_PREFIX = "com.sohu.inputmethod.sogou.tv";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        boolean isBootAction = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || "mitv.action.STR_BOOT_COMPLETED".equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action);
        if (!isBootAction) {
            return;
        }
        enforcePowerAndHomeGuard(context);
        try {
            Intent home = new Intent(context, MainActivity.class)
                    .setAction(MainActivity.ACTION_HOME_KEY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(home);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to start MainActivity on boot: " + e.getMessage());
        }
    }

    public static void enforcePowerAndHomeGuard(Context context) {
        if (context == null) return;
        ContentResolver cr = context.getContentResolver();
        if (cr == null) return;

        final boolean xiaomiRom = TvHardwareProfiler.isXiaomiMitvRom();

        // 1. Settings.Global: Xiaomi-specific power/BLE fast-connect guards strictly gated to Xiaomi ROMs.
        // Never overwrite private_dns_mode or adb_enabled on Sony, TCL, Casper, Coocaa, Hisense, etc.
        if (xiaomiRom) {
            try {
                String powerState = Settings.Global.getString(cr, "mitv_power_state");
                if (!"usermode".equals(powerState)) {
                    Settings.Global.putString(cr, "mitv_power_state", "usermode");
                }
                if (TvHardwareProfiler.isXiaomiModelPrefix()) {
                    String dnsMode = Settings.Global.getString(cr, "private_dns_mode");
                    if ("hostname".equals(dnsMode)) {
                        Settings.Global.putString(cr, "private_dns_mode", "off");
                    }
                }
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1);
                Settings.Global.putInt(cr, "adb_wifi_enabled", 1);
                Settings.Global.putInt(cr, "bluetooth_on", 1);
                Settings.Global.putInt(cr, "bluetooth_enable_fastconnect", 1);
                Settings.Global.putInt(cr, "bluetooth_feature_fastconnect", 1);
                Settings.Global.putInt(cr, "hdmi_control_auto_wakeup_enabled", 1);
                Settings.Global.putInt(cr, "wifi_scan_always_enabled", 0);
                Settings.Global.putInt(cr, "ble_scan_always_enabled", 0);
                Settings.Global.putInt(cr, "wifi_sleep_policy", 2);
            } catch (Throwable e) {
                Log.w(TAG, "Xiaomi global guard skipped: " + e.getMessage());
            }
        }

        // 2. Settings.Secure: Self-heal HomeKeyService accessibility, VnTvImeService, and fallback voice service.
        try {
            String services = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (services == null || !services.contains(HOME_SERVICE)) {
                String updated = (services == null || services.isEmpty() || "null".equals(services))
                        ? HOME_SERVICE
                        : services + ":" + HOME_SERVICE;
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
            }
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);

            if (xiaomiRom) {
                ContentValues cv = new ContentValues(2);
                cv.put("name", "adb_enabled");
                cv.put("value", "1");
                cr.insert(Settings.Secure.CONTENT_URI, cv);
                Settings.Secure.putInt(cr, "wake_gesture_enabled", 1);
                Settings.Secure.putInt(cr, "screensaver_enabled", 0);
                Settings.Secure.putString(cr, "screensaver_components", CLOCK_DREAM);
                Settings.Secure.putInt(cr, "upload_log_pref", 0);
                Settings.Secure.putInt(cr, "user_experience_enabled", 0);
            }

            PackageManager pm = context.getPackageManager();
            if (isServiceRegistered(pm, context, "vn.viettv.launcher.VnTvImeService")) {
                String enabledImes = Settings.Secure.getString(cr, Settings.Secure.ENABLED_INPUT_METHODS);
                if (enabledImes == null || !enabledImes.contains(VN_IME_SERVICE)) {
                    String updatedImes = (enabledImes == null || enabledImes.isEmpty() || "null".equals(enabledImes))
                            ? VN_IME_SERVICE
                            : enabledImes + ":" + VN_IME_SERVICE;
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_INPUT_METHODS, updatedImes);
                }
                String defaultIme = Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD);
                if (shouldPromoteVietnameseDefaultIme(defaultIme)) {
                    Settings.Secure.putString(cr, Settings.Secure.DEFAULT_INPUT_METHOD, VN_IME_SERVICE);
                }
            }
            if (isServiceRegistered(pm, context, "vn.viettv.launcher.VnRecognitionService")
                    && TvHardwareProfiler.findExternalRecognitionService(context) == null) {
                Settings.Secure.putString(cr, "voice_recognition_service", VN_RECOG_SERVICE);
                Settings.Secure.putString(cr, "assistant", VN_VOICE_ASSIST);
            }
        } catch (Throwable e) {
            Log.w(TAG, "Secure guard skipped: " + e.getMessage());
        }
    }

    private static boolean shouldPromoteVietnameseDefaultIme(String currentIme) {
        if (currentIme == null || currentIme.trim().isEmpty() || "null".equals(currentIme)) {
            return true;
        }
        if (VN_IME_SERVICE.equals(currentIme)) {
            return false;
        }
        String lower = currentIme.toLowerCase(Locale.ROOT);
        return lower.startsWith(SOGOU_TV_IME_PREFIX)
                || lower.startsWith("com.sohu.inputmethod")
                || lower.startsWith("com.baidu.input")
                || lower.startsWith("com.iflytek.inputmethod")
                || lower.startsWith("com.xiaomi.mitv.ime");
    }

    private static boolean isServiceRegistered(PackageManager pm, Context context, String className) {
        if (pm == null || context == null) return false;
        try {
            return pm.getServiceInfo(new ComponentName(context, className), 0) != null;
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "Optional service not yet registered: " + className);
            return false;
        } catch (Throwable e) {
            Log.w(TAG, "Service check skipped for " + className + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Backward-compatible wrapper delegating to {@link TvHardwareProfiler#resolveSystemSettingsIntent}.
     */
    public static Intent resolveXiaomiSettingsIntent(Context context, String standardAction) {
        return TvHardwareProfiler.resolveSystemSettingsIntent(context, standardAction);
    }
}
