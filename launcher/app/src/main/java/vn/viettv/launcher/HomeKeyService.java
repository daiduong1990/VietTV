package vn.viettv.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Collections;
import java.util.Map;

/**
 * Sends the remote's Home button (KEYCODE_HOME / KEYCODE_ALL_APPS) to this launcher, intercepts
 * Xiaomi's FallbackHome when com.xiaomi.mitv.settings is enabled, and provides lockScreen/standby
 * power-off via AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN.
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services vn.viettv.launcher/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {
    private static final String TAG = "HomeKeyService";

    public static final int XIAOMI_VOICE_SCANCODE = 63;

    public static boolean isVoiceKey(KeyEvent event) {
        if (event == null) {
            return false;
        }
        int keyCode = event.getKeyCode();
        return keyCode == KeyEvent.KEYCODE_VOICE_ASSIST
                || keyCode == KeyEvent.KEYCODE_SEARCH
                || (TvHardwareProfiler.isXiaomiMitvRom() && event.getScanCode() == XIAOMI_VOICE_SCANCODE);
    }

    private static volatile HomeKeyService instance;
    private final android.os.Handler guardHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean wakeReceiverRegistered = false;
    private boolean settingsObserverRegistered = false;
    private final android.database.ContentObserver adbAndPowerObserver = new android.database.ContentObserver(guardHandler) {
        @Override
        public void onChange(boolean selfChange) {
            BootReceiver.enforcePowerAndHomeGuard(HomeKeyService.this);
        }
    };
    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && "com.xiaomi.mitv.voice.SHORTPRESS".equals(intent.getAction())) {
                if (!VnRecognitionService.isListeningActive()
                        && !VoiceSearchActivity.isOverlayListening()) {
                    launchVoiceSearch();
                }
                return;
            }
            if (intent != null && Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                TvVoiceCommandRouter.cancelSleepTimer();
            }
            BootReceiver.enforcePowerAndHomeGuard(HomeKeyService.this);
            guardHandler.postDelayed(() -> BootReceiver.enforcePowerAndHomeGuard(HomeKeyService.this), 1500);
            guardHandler.postDelayed(() -> BootReceiver.enforcePowerAndHomeGuard(HomeKeyService.this), 4000);
        }
    };

    /** Puts the TV into standby/sleep immediately via AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN. */
    public static boolean sleepTv() {
        HomeKeyService s = instance;
        if (s != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
        return false;
    }

    /**
     * Self-heals power/ADB/BLE guards and HomeKeyService binding before putting the TV into standby.
     */
    public static boolean sleepTvWithSelfHeal(Context context) {
        if (context != null) {
            BootReceiver.enforcePowerAndHomeGuard(context);
        }
        if (sleepTv()) {
            return true;
        }
        if (instance == null && context != null) {
            BootReceiver.enforcePowerAndHomeGuard(context);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(HomeKeyService::sleepTv, 250);
            return true;
        }
        return false;
    }

    public static int scheduleSleepTimer(Context context, int minutes) {
        return TvVoiceCommandRouter.scheduleSleepTimer(context, minutes);
    }

    public static boolean cancelSleepTimer() {
        return TvVoiceCommandRouter.cancelSleepTimer();
    }

    public static int getRemainingSleepMinutes() {
        return TvVoiceCommandRouter.getRemainingSleepMinutes();
    }

    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];
    private boolean settingsPanel;
    /** app_keys, the same map InputKeyService reads, for setups that run only this service. */
    private Map<Integer, String> appKeys = Collections.emptyMap();
    private Map<Integer, String> appScancodes = Collections.emptyMap();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        TvVoiceCommandRouter.registerSleepExecutor(HomeKeyService::sleepTvWithSelfHeal);
        BootReceiver.enforcePowerAndHomeGuard(this);
        if (!wakeReceiverRegistered) {
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_SCREEN_ON);
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                filter.addAction(Intent.ACTION_USER_PRESENT);
                filter.addAction("mitv.action.STR_BOOT_COMPLETED");
                filter.addAction("com.xiaomi.mitv.settings.BLE_CONNECTED");
                filter.addAction("com.xiaomi.mitv.voice.SHORTPRESS");
                registerReceiver(wakeReceiver, filter);
                wakeReceiverRegistered = true;
            } catch (Throwable e) {
                Log.w(TAG, "Wake receiver registration skipped: " + e.getMessage());
            }
        }
        if (!settingsObserverRegistered && TvHardwareProfiler.isXiaomiMitvRom()) {
            try {
                android.content.ContentResolver cr = getContentResolver();
                if (cr != null) {
                    cr.registerContentObserver(
                            Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, adbAndPowerObserver);
                    cr.registerContentObserver(
                            Settings.Global.getUriFor("adb_wifi_enabled"), false, adbAndPowerObserver);
                    cr.registerContentObserver(
                            Settings.Secure.getUriFor("adb_enabled"), false, adbAndPowerObserver);
                    cr.registerContentObserver(
                            Settings.System.getUriFor("mitv.short.power.action"), false, adbAndPowerObserver);
                    settingsObserverRegistered = true;
                }
            } catch (Throwable e) {
                Log.w(TAG, "Settings observer registration skipped: " + e.getMessage());
            }
        }
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        Config config = Config.load(this);
        settingsKeycodes = config.settingsKeycodes;
        settingsPanel = config.settingsKeyPanel;
        appKeys = RemoteKeys.parseAppKeys(config.appKeys);
        appScancodes = RemoteKeys.parseAppScancodes(config.appKeys);
        if (android.os.SystemClock.elapsedRealtime() < 60_000L) {
            launchHome();
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        guardHandler.removeCallbacksAndMessages(null);
        if (settingsObserverRegistered) {
            try {
                android.content.ContentResolver cr = getContentResolver();
                if (cr != null) {
                    cr.unregisterContentObserver(adbAndPowerObserver);
                }
            } catch (Throwable e) {
                Log.w(TAG, "Settings observer unregister skipped: " + e.getMessage());
            }
            settingsObserverRegistered = false;
        }
        if (wakeReceiverRegistered) {
            try {
                unregisterReceiver(wakeReceiver);
            } catch (Throwable e) {
                Log.w(TAG, "Wake receiver unregister skipped: " + e.getMessage());
            }
            wakeReceiverRegistered = false;
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    private void launchHome() {
        Intent home = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_HOME_KEY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
    }

    private void launchVoiceSearch() {
        try {
            Intent voice = new Intent()
                    .setClassName(this, "vn.viettv.launcher.VoiceSearchActivity")
                    .setAction("android.speech.action.WEB_SEARCH")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(voice);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to launch VoiceSearchActivity: " + e.getMessage());
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event == null) {
            return false;
        }
        if (isVoiceKey(event)) {
            int action = event.getAction();
            if (action == KeyEvent.ACTION_DOWN) {
                VoiceAudioRecorder.notifyPhysicalVoiceKeyState(true);
                VoiceSearchActivity.notifyPhysicalVoiceKeyAction(true);
                if (event.getRepeatCount() == 0 && !event.isCanceled()) {
                    if (!VnRecognitionService.isListeningActive()
                            && !VoiceSearchActivity.isOverlayListening()) {
                        launchVoiceSearch();
                    }
                }
                return true;
            }
            if (action == KeyEvent.ACTION_UP) {
                VoiceAudioRecorder.notifyPhysicalVoiceKeyState(false);
                VoiceSearchActivity.notifyPhysicalVoiceKeyAction(false);
                return true;
            }
            return true;
        }
        String scanApp = appScancodes.get(event.getScanCode());
        if (scanApp != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) RemoteKeys.launch(this, scanApp);
            return true;
        }
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                Intent open = settingsPanel ? new Intent(this, QuickSettingsActivity.class)
                        : BootReceiver.resolveXiaomiSettingsIntent(this, Settings.ACTION_SETTINGS);
                try {
                    startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Throwable e) {
                    Log.w(TAG, "Failed to open settings from remote key: " + e.getMessage());
                }
            }
            return true;
        }
        String app = appKeys.get(event.getKeyCode());
        if (app != null) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) RemoteKeys.launch(this, app);
            return true;
        }
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_ALL_APPS && keyCode != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            launchHome();
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        CharSequence cls = event.getClassName();
        CharSequence pkg = event.getPackageName();
        if (cls != null && cls.toString().contains("FallbackHome")) {
            launchHome();
        } else if (pkg != null && "com.xiaomi.mitv.settings".contentEquals(pkg)
                && cls != null && cls.toString().contains("FallbackHome")) {
            launchHome();
        }
    }

    @Override
    public void onInterrupt() {
    }
}
