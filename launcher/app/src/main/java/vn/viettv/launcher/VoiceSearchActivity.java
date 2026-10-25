package vn.viettv.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

/**
 * Translucent Apple TV Dark Glass overlay activity handling global physical Voice button searches
 * (routing to SmartTube), standard Android RecognizerIntent.ACTION_RECOGNIZE_SPEECH requests, and
 * Vietnamese IME voice dictation (ACTION_IME_DICTATE).
 */
public final class VoiceSearchActivity extends Activity {

    private static final String TAG = "TvHomeVoiceUI";

    public static final String ACTION_IME_DICTATE = "vn.viettv.launcher.ACTION_IME_DICTATE";
    public static final String ACTION_IME_VOICE_RESULT = "vn.viettv.launcher.ACTION_IME_VOICE_RESULT";

    public static final String PKG_SMARTTUBE_STABLE = "org.smarttube.stable";
    public static final String PKG_SMARTTUBE_BETA = "org.smarttube.beta";
    public static final String CLS_SMARTTUBE_SPLASH =
            "com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity";
    public static final String PKG_SMARTTUBE_LEGACY = "com.liskovsoft.videomanager";
    public static final String PKG_YOUTUBE_TV = "com.google.android.youtube.tv";
    public static final String PKG_VTV_GO = "vn.vtv.vtvgo";
    public static final String PKG_FPT_PLAY = "com.fptplay.ottbox";
    public static final String PKG_VIEON = "tv.vieon.vienthonga";
    public static final String PKG_TV360 = "com.tv360.tv";
    public static final String PKG_NETFLIX = "com.netflix.mediaclient";

    private static final int REQ_RECORD_AUDIO = 701;
    private static final long ERROR_DISMISS_DELAY_MS = 2800L;
    private static final long SETUP_GUIDE_DISMISS_DELAY_MS = 9500L;

    private static final int MODE_GLOBAL_SEARCH = 0;
    private static final int MODE_IME_DICTATE = 1;
    private static final int MODE_RECOGNIZE_RESULT = 2;

    private static final int STATE_IDLE = 0;
    private static final int STATE_LISTENING = 1;
    private static final int STATE_TRANSCRIBING = 2;
    private static final int STATE_DONE = 3;
    private static final int STATE_ERROR = 4;

    private static final Locale VI_LOCALE = new Locale("vi", "VN");
    private static final String[] SEARCH_PREFIXES = new String[]{
            "tìm kiếm ",
            "tìm video ",
            "mở video "
    };

    private static volatile VoiceSearchActivity activeOverlay = null;

    public static boolean isOverlayListening() {
        VoiceSearchActivity a = activeOverlay;
        return a != null
                && !a.isFinishing()
                && a.uiState == STATE_LISTENING
                && ((a.recorder != null && a.recorder.isRecording()) || a.speechRecognizerListening);
    }

    public static void notifyPhysicalVoiceKeyAction(final boolean held) {
        final VoiceSearchActivity a = activeOverlay;
        if (a != null && !a.isFinishing()) {
            a.mainHandler.post(() -> a.handlePhysicalVoiceKeyChanged(held));
        }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable errorAutoFinishRunnable = this::finish;

    private int launchMode = MODE_GLOBAL_SEARCH;
    private int uiState = STATE_IDLE;
    private int activeSessionId = 0;
    private boolean speechStartedFlag = false;
    private boolean speechRecognizerListening = false;
    private long speechListenStartElapsedMs = 0L;
    private String lastPartialTranscript = "";
    private boolean missingSetupMode = false;

    private VoiceAudioRecorder recorder;
    private SpeechRecognizer speechRecognizer;
    private MicPulseView micPulseView;
    private TextView titleView;
    private TextView subtitleView;
    private TextView retryBadgeView;

    /**
     * Pure-Java helper that strips optional leading Vietnamese search command prefixes
     * ("tìm kiếm ", "tìm video ", "mở video ") ONLY when non-empty query text remains.
     */
    public static String cleanSearchTranscript(String rawTranscript) {
        if (rawTranscript == null) {
            return "";
        }
        String trimmed = rawTranscript.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String lower = trimmed.toLowerCase(VI_LOCALE);
        for (String prefix : SEARCH_PREFIXES) {
            if (lower.startsWith(prefix)) {
                String remainder = trimmed.substring(prefix.length()).trim();
                if (!remainder.isEmpty()) {
                    return remainder;
                }
            }
        }
        return trimmed;
    }

    /**
     * Static deterministic intent builder for quick app commands and SmartTube video search.
     */
    public static Intent buildSmartTubeSearchIntent(Context context, String rawTranscript) {
        String trimmed = rawTranscript == null ? "" : rawTranscript.trim();
        String normalizedCmd = trimmed.replaceAll("\\s+", " ").toLowerCase(VI_LOCALE);

        // Quick app-launch commands
        if (normalizedCmd.startsWith("mở ")) {
            String targetName = normalizedCmd.substring("mở ".length()).trim();
            String quickPkg = mapQuickLaunchPackage(targetName);
            if (quickPkg != null) {
                Intent appIntent = resolveInstalledLaunchIntent(context, quickPkg);
                if (appIntent != null) {
                    return appIntent;
                }
            }
        }

        if ("mở cài đặt giao diện".equals(normalizedCmd) || "tùy chỉnh giao diện".equals(normalizedCmd)) {
            if (context != null) {
                return new Intent(context, LauncherSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
        }

        if ("mở cài đặt".equals(normalizedCmd)
                || "cài đặt tivi".equals(normalizedCmd)
                || "mở cài đặt tivi".equals(normalizedCmd)) {
            Intent settings = TvHardwareProfiler.resolveSystemSettingsIntent(context, Settings.ACTION_SETTINGS);
            return settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        String cleanQuery = cleanSearchTranscript(trimmed);
        Uri uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(cleanQuery));
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP;
        String voiceSearchApp = readVoicePrefStatic(context, "voice_search_app", "auto");

        PackageManager pm = context != null ? context.getPackageManager() : null;
        if ("com.google.android.youtube.tv".equals(voiceSearchApp)
                && (pm == null || isPackageInstalled(pm, PKG_YOUTUBE_TV))) {
            return new Intent(Intent.ACTION_VIEW, uri)
                    .setPackage(PKG_YOUTUBE_TV)
                    .putExtra(SearchManager.QUERY, cleanQuery)
                    .addFlags(flags);
        }
        if ("org.smarttube.stable".equals(voiceSearchApp)
                && (pm == null || isPackageInstalled(pm, PKG_SMARTTUBE_STABLE))) {
            return buildSmartTubeStableIntent(uri, cleanQuery, flags);
        }

        if (context == null || pm == null || isPackageInstalled(pm, PKG_SMARTTUBE_STABLE)) {
            return buildSmartTubeStableIntent(uri, cleanQuery, flags);
        }
        String[] autoFallbacks = new String[]{PKG_SMARTTUBE_BETA, PKG_SMARTTUBE_LEGACY, PKG_YOUTUBE_TV};
        for (String pkg : autoFallbacks) {
            if (isPackageInstalled(pm, pkg)) {
                return new Intent(Intent.ACTION_VIEW, uri)
                        .setPackage(pkg)
                        .putExtra(SearchManager.QUERY, cleanQuery)
                        .addFlags(flags);
            }
        }

        return new Intent(Intent.ACTION_VIEW, uri)
                .putExtra(SearchManager.QUERY, cleanQuery)
                .addFlags(flags);
    }

    private static Intent buildSmartTubeStableIntent(Uri uri, String cleanQuery, int flags) {
        return new Intent(Intent.ACTION_VIEW, uri)
                .setComponent(new ComponentName(PKG_SMARTTUBE_STABLE, CLS_SMARTTUBE_SPLASH))
                .putExtra(SearchManager.QUERY, cleanQuery)
                .addFlags(flags);
    }

    private static String mapQuickLaunchPackage(String targetName) {
        if ("vtv".equals(targetName) || "vtv go".equals(targetName) || "vtvgo".equals(targetName)) {
            return PKG_VTV_GO;
        }
        if ("netflix".equals(targetName)) {
            return PKG_NETFLIX;
        }
        if ("fpt".equals(targetName) || "fpt play".equals(targetName)) {
            return PKG_FPT_PLAY;
        }
        if ("vieon".equals(targetName) || "vie on".equals(targetName)) {
            return PKG_VIEON;
        }
        if ("tv360".equals(targetName) || "tv 360".equals(targetName)) {
            return PKG_TV360;
        }
        return null;
    }

    private static String readVoicePrefStatic(Context context, String key, String fallback) {
        if (context == null) {
            return fallback;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(TvHardwareProfiler.PREFS_NAME, Context.MODE_PRIVATE);
            if (prefs == null) {
                return fallback;
            }
            return Objects.toString(prefs.getString(key, fallback), fallback).trim();
        } catch (Throwable t) {
            Log.d(TAG, "readVoicePrefStatic fallback: " + t.getMessage());
            return fallback;
        }
    }

    private static Intent resolveInstalledLaunchIntent(Context context, String pkg) {
        if (context == null || pkg == null) {
            return null;
        }
        PackageManager pm = context.getPackageManager();
        if (pm == null) {
            return null;
        }
        Intent leanback = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (leanback != null) {
            return leanback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        Intent standard = pm.getLaunchIntentForPackage(pkg);
        if (standard != null) {
            return standard.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return null;
    }

    private static boolean isPackageInstalled(PackageManager pm, String pkg) {
        if (pm == null || pkg == null) {
            return false;
        }
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException notFound) {
            return false;
        } catch (Throwable t) {
            return pm.getLeanbackLaunchIntentForPackage(pkg) != null
                    || pm.getLaunchIntentForPackage(pkg) != null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Locale.setDefault(VI_LOCALE);
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        } catch (Throwable t) {
            Log.w(TAG, "requestWindowFeature skipped: " + t.getMessage());
        }
        super.onCreate(savedInstanceState);

        activeOverlay = this;
        configureTranslucentWindow();
        launchMode = resolveMode(getIntent());
        buildOverlayUi();
        ensurePermissionAndListen();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        activeOverlay = this;
        setIntent(intent);
        launchMode = resolveMode(intent);
        if (uiState == STATE_ERROR || uiState == STATE_IDLE) {
            ensurePermissionAndListen();
        } else if (uiState == STATE_LISTENING
                && !VoiceAudioRecorder.isPhysicalVoiceKeyHeld()
                && speechStartedFlag) {
            if (recorder != null) {
                recorder.finishEarly();
            } else if (speechRecognizer != null && speechRecognizerListening) {
                stopSpeechRecognizerListeningEarly();
            }
        }
    }

    private int resolveMode(Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_IME_DICTATE.equals(action)) {
            return MODE_IME_DICTATE;
        }
        if (RecognizerIntent.ACTION_RECOGNIZE_SPEECH.equals(action) || getCallingActivity() != null) {
            return MODE_RECOGNIZE_RESULT;
        }
        return MODE_GLOBAL_SEARCH;
    }

    private void configureTranslucentWindow() {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        window.setBackgroundDrawable(new ColorDrawable(0x4D000000));
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void buildOverlayUi() {
        DisplayMetrics dm = getResources().getDisplayMetrics();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x33000000);
        root.setClickable(true);
        root.setFocusable(false);
        root.setOnClickListener(v -> cancelAndFinish());

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        int padH = dp(dm, 26);
        int padV = dp(dm, 22);
        card.setPadding(padH, padV, padH, padV);

        GradientDrawable glassBg = new GradientDrawable();
        glassBg.setShape(GradientDrawable.RECTANGLE);
        glassBg.setColor(0xEB141824);
        glassBg.setCornerRadius(dpF(dm, 28f));
        glassBg.setStroke(Math.max(1, dp(dm, 1)), 0x33FFFFFF);
        card.setBackground(glassBg);
        card.setElevation(dpF(dm, 20f));
        card.setClickable(true);

        micPulseView = new MicPulseView(this, dm);
        int micBoxSize = dp(dm, 68);
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(micBoxSize, micBoxSize);
        micLp.rightMargin = dp(dm, 20);
        card.addView(micPulseView, micLp);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL);

        titleView = new TextView(this);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        subtitleView = new TextView(this);
        subtitleView.setTextColor(0xB8FFFFFF);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        subtitleView.setMaxLines(2);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(dm, 5);
        textCol.addView(subtitleView, subLp);

        updateListeningPrompt(VoiceAudioRecorder.isPhysicalVoiceKeyHeld(), false);

        card.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        retryBadgeView = new TextView(this);
        retryBadgeView.setText("[Thử lại]");
        retryBadgeView.setTextColor(0xFFFFFFFF);
        retryBadgeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        retryBadgeView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        int badgePadH = dp(dm, 14);
        int badgePadV = dp(dm, 8);
        retryBadgeView.setPadding(badgePadH, badgePadV, badgePadH, badgePadV);
        GradientDrawable retryBg = new GradientDrawable();
        retryBg.setShape(GradientDrawable.RECTANGLE);
        retryBg.setColor(0x38FFFFFF);
        retryBg.setCornerRadius(dpF(dm, 16f));
        retryBg.setStroke(Math.max(1, dp(dm, 1)), 0x66FFFFFF);
        retryBadgeView.setBackground(retryBg);
        retryBadgeView.setVisibility(View.GONE);
        retryBadgeView.setOnClickListener(v -> {
            if (missingSetupMode) {
                openLauncherSettingsAndFinish();
            } else {
                ensurePermissionAndListen();
            }
        });
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.leftMargin = dp(dm, 14);
        card.addView(retryBadgeView, badgeLp);

        int maxCardWidth = Math.min(dp(dm, 680), dm.widthPixels - dp(dm, 64));
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                maxCardWidth, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cardLp.bottomMargin = dp(dm, 44);
        root.addView(card, cardLp);

        setContentView(root);
    }

    private void updateListeningPrompt(boolean keyHeld, boolean speechStarted) {
        if (titleView == null || subtitleView == null) {
            return;
        }
        if (speechStarted) {
            titleView.setText("🎤 Đang thu giọng nói Tiếng Việt...");
            subtitleView.setText(keyHeld
                    ? "Hãy nói tiếp... Thả nút Micro để tìm kiếm ngay"
                    : "Đang thu giọng nói... Nhấn OK để chốt ngay");
        } else if (keyHeld) {
            titleView.setText("🎤 Đang nghe Tiếng Việt...");
            if (launchMode == MODE_IME_DICTATE) {
                subtitleView.setText("Đang giữ nút Micro: hãy nói nội dung · Thả nút để điền chữ");
            } else if (launchMode == MODE_GLOBAL_SEARCH) {
                int remMins = TvVoiceCommandRouter.getRemainingSleepMinutes();
                String sub = "Đang giữ Micro: nói tên video hoặc lệnh (âm lượng, hẹn giờ, tắt tivi)";
                if (remMins > 0) {
                    sub += " · ⏰ Còn " + remMins + "p";
                }
                subtitleView.setText(sub);
            } else {
                subtitleView.setText("Đang giữ nút Micro: hãy nói tên video · Thả nút để tìm trên SmartTube");
            }
        } else {
            titleView.setText("🎤 Nhấn và GIỮ nút Micro để nói...");
            if (launchMode == MODE_IME_DICTATE) {
                subtitleView.setText("Giữ nút Micro trên điều khiển khi nói, thả ra để nhập văn bản");
            } else if (launchMode == MODE_GLOBAL_SEARCH) {
                int remMins = TvVoiceCommandRouter.getRemainingSleepMinutes();
                String sub = "Giữ nút Micro để tìm SmartTube hoặc điều khiển (âm lượng, hẹn giờ, tắt tivi)";
                if (remMins > 0) {
                    sub += " · ⏰ Còn " + remMins + "p";
                }
                subtitleView.setText(sub);
            } else {
                subtitleView.setText("Giữ nút Micro trên điều khiển khi nói, thả ra để tìm trên SmartTube");
            }
        }
    }

    private void ensurePermissionAndListen() {
        mainHandler.removeCallbacks(errorAutoFinishRunnable);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            return;
        }
        startVoiceCapture();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults != null && grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceCapture();
            } else {
                showErrorWithRetry("Chưa cấp quyền Micro để tìm kiếm giọng nói.");
            }
        }
    }

    private void startVoiceCapture() {
        stopActiveRecorder();
        final int sessionId = ++activeSessionId;

        speechStartedFlag = false;
        lastPartialTranscript = "";
        missingSetupMode = false;
        retryBadgeView.setText("[Thử lại]");
        retryBadgeView.setVisibility(View.GONE);

        String voiceMode = readVoicePrefStatic(this, "voice_mode", "auto");
        ComponentName extService = "cloud_key".equals(voiceMode)
                ? null
                : TvHardwareProfiler.findExternalRecognitionService(this);

        if (extService != null) {
            startExternalSpeechCapture(sessionId, extService, voiceMode);
            return;
        }
        if (!"on_tv".equals(voiceMode) && VoiceSttClient.hasConfiguredCloudStt(this)) {
            startCloudRecorderCapture(sessionId);
            return;
        }
        showMissingVoiceSetupCard();
    }

    private void startExternalSpeechCapture(
            final int sessionId,
            final ComponentName extService,
            final String voiceMode) {
        uiState = STATE_LISTENING;
        speechRecognizerListening = true;
        speechListenStartElapsedMs = SystemClock.elapsedRealtime();
        updateListeningPrompt(VoiceAudioRecorder.isPhysicalVoiceKeyHeld(), false);
        micPulseView.setVisualMode(STATE_LISTENING);

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this, extService);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    if (sessionId == activeSessionId && uiState == STATE_LISTENING) {
                        micPulseView.setVisualMode(STATE_LISTENING);
                    }
                }

                @Override
                public void onBeginningOfSpeech() {
                    if (sessionId != activeSessionId || uiState != STATE_LISTENING) {
                        return;
                    }
                    speechStartedFlag = true;
                    updateListeningPrompt(VoiceAudioRecorder.isPhysicalVoiceKeyHeld(), true);
                }

                @Override
                public void onRmsChanged(float rmsdB) {
                    if (sessionId != activeSessionId || uiState != STATE_LISTENING) {
                        return;
                    }
                    float norm = Math.max(0.0f, Math.min(1.0f, (rmsdB + 2.0f) / 10.0f));
                    micPulseView.setRmsLevel(norm);
                }

                @Override
                public void onBufferReceived(byte[] buffer) {
                }

                @Override
                public void onEndOfSpeech() {
                    if (sessionId != activeSessionId || uiState != STATE_LISTENING) {
                        return;
                    }
                    speechRecognizerListening = false;
                    uiState = STATE_TRANSCRIBING;
                    titleView.setText("⏳ Đang nhận diện Tiếng Việt...");
                    micPulseView.setRmsLevel(0.0f);
                    micPulseView.setVisualMode(STATE_TRANSCRIBING);
                }

                @Override
                public void onError(int error) {
                    if (sessionId != activeSessionId || isFinishing() || isDestroyed()) {
                        return;
                    }
                    long elapsed = SystemClock.elapsedRealtime() - speechListenStartElapsedMs;
                    destroySpeechRecognizer();
                    boolean allowCloudFallback = !"on_tv".equals(voiceMode)
                            && VoiceSttClient.hasConfiguredCloudStt(VoiceSearchActivity.this)
                            && ((error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                                    && error != SpeechRecognizer.ERROR_NO_MATCH)
                                || elapsed < 400L);
                    if (allowCloudFallback) {
                        startCloudRecorderCapture(sessionId);
                        return;
                    }
                    if (!lastPartialTranscript.isEmpty() && error == SpeechRecognizer.ERROR_NO_MATCH) {
                        dispatchRecognizedTranscript(lastPartialTranscript);
                        return;
                    }
                    showErrorWithRetry(mapSpeechErrorMessage(error));
                }

                @Override
                public void onResults(Bundle results) {
                    if (sessionId != activeSessionId || isFinishing() || isDestroyed()) {
                        return;
                    }
                    destroySpeechRecognizer();
                    String best = extractBestSpeechMatch(results);
                    if (best.isEmpty()) {
                        best = lastPartialTranscript;
                    }
                    if (best.isEmpty()) {
                        showErrorWithRetry("Không nhận diện được câu nói. Hãy thử nói rõ hơn.");
                    } else {
                        dispatchRecognizedTranscript(best);
                    }
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    if (sessionId != activeSessionId
                            || (uiState != STATE_LISTENING && uiState != STATE_TRANSCRIBING)) {
                        return;
                    }
                    String partial = extractBestSpeechMatch(partialResults);
                    if (!partial.isEmpty()) {
                        speechStartedFlag = true;
                        lastPartialTranscript = partial;
                        titleView.setText("🎤 Đang thu giọng nói Tiếng Việt...");
                        subtitleView.setText("“" + partial + "”");
                    }
                }

                @Override
                public void onEvent(int eventType, Bundle params) {
                }
            });

            Intent recIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            recIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            recIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN");
            recIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN");
            recIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            recIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            speechRecognizer.startListening(recIntent);
        } catch (Throwable t) {
            Log.w(TAG, "SpeechRecognizer start failed: " + t.getMessage());
            destroySpeechRecognizer();
            if (!"on_tv".equals(voiceMode) && VoiceSttClient.hasConfiguredCloudStt(this)) {
                startCloudRecorderCapture(sessionId);
            } else {
                showErrorWithRetry("Không khởi động được dịch vụ giọng nói trên Tivi.");
            }
        }
    }

    private static String extractBestSpeechMatch(Bundle bundle) {
        if (bundle == null) {
            return "";
        }
        ArrayList<String> matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null) {
            return "";
        }
        for (String item : matches) {
            if (item != null && !item.trim().isEmpty()) {
                return item.trim();
            }
        }
        return "";
    }

    private static String mapSpeechErrorMessage(int error) {
        if (error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
            return "Lỗi kết nối mạng khi nhận diện giọng nói.";
        }
        if (error == SpeechRecognizer.ERROR_AUDIO) {
            return "Không mở được micro. Hãy thử lại.";
        }
        if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            return "Chưa nghe thấy giọng nói. Hãy nhấn giữ nút Micro khi nói.";
        }
        return "Không nhận diện được câu nói. Hãy thử nói rõ hơn.";
    }

    private void startCloudRecorderCapture(final int sessionId) {
        speechStartedFlag = false;
        uiState = STATE_LISTENING;
        updateListeningPrompt(VoiceAudioRecorder.isPhysicalVoiceKeyHeld(), false);
        retryBadgeView.setVisibility(View.GONE);
        micPulseView.setVisualMode(STATE_LISTENING);

        recorder = new VoiceAudioRecorder(this, mainHandler, new VoiceAudioRecorder.Listener() {
            @Override
            public void onRmsChanged(float normalizedRms) {
                if (sessionId != activeSessionId || uiState != STATE_LISTENING) {
                    return;
                }
                micPulseView.setRmsLevel(normalizedRms);
            }

            @Override
            public void onSpeechStart() {
                if (sessionId != activeSessionId || uiState != STATE_LISTENING) {
                    return;
                }
                speechStartedFlag = true;
                updateListeningPrompt(VoiceAudioRecorder.isPhysicalVoiceKeyHeld(), true);
            }

            @Override
            public void onKeyHoldStateChanged(boolean held) {
                if (sessionId != activeSessionId || uiState != STATE_LISTENING) {
                    return;
                }
                updateListeningPrompt(held, speechStartedFlag);
            }

            @Override
            public void onRecordingComplete(byte[] pcm16kBytes, int durationMs) {
                if (sessionId != activeSessionId) {
                    return;
                }
                recorder = null;
                transcribeCapturedAudio(sessionId, pcm16kBytes);
            }

            @Override
            public void onError(String message) {
                if (sessionId != activeSessionId) {
                    return;
                }
                recorder = null;
                showErrorWithRetry(message != null && !message.isEmpty()
                        ? message
                        : "Không nghe thấy giọng nói.");
            }
        });
        recorder.start();
    }

    private void showMissingVoiceSetupCard() {
        uiState = STATE_ERROR;
        missingSetupMode = true;
        micPulseView.setRmsLevel(0.0f);
        micPulseView.setVisualMode(STATE_ERROR);
        titleView.setText("🎤 Cần bật Bộ nhận giọng nói Tiếng Việt miễn phí");
        subtitleView.setText("Bấm OK mở Cài đặt để nhập khóa Groq miễn phí, hoặc chạy lệnh cài gói Giọng nói Offline CPU trên máy tính");
        retryBadgeView.setText("[Mở Cài đặt]");
        retryBadgeView.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(errorAutoFinishRunnable);
        mainHandler.postDelayed(errorAutoFinishRunnable, SETUP_GUIDE_DISMISS_DELAY_MS);
    }

    private void openLauncherSettingsAndFinish() {
        mainHandler.removeCallbacks(errorAutoFinishRunnable);
        try {
            startActivity(new Intent(this, LauncherSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Log.w(TAG, "Failed to open LauncherSettingsActivity: " + t.getMessage());
        }
        finish();
    }

    private void transcribeCapturedAudio(final int sessionId, final byte[] pcm16kBytes) {
        uiState = STATE_TRANSCRIBING;
        titleView.setText("⏳ Đang nhận diện Tiếng Việt...");
        subtitleView.setText("Đang phân tích giọng nói...");
        retryBadgeView.setVisibility(View.GONE);
        micPulseView.setRmsLevel(0.0f);
        micPulseView.setVisualMode(STATE_TRANSCRIBING);

        Thread sttWorker = new Thread(() -> {
            try {
                final String transcript = VoiceSttClient.transcribeWithUserConfig(VoiceSearchActivity.this, pcm16kBytes);
                mainHandler.post(() -> {
                    if (sessionId != activeSessionId || isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (transcript == null || transcript.trim().isEmpty()) {
                        showErrorWithRetry("Không nhận diện được câu nói. Hãy thử nói rõ hơn.");
                    } else {
                        dispatchRecognizedTranscript(transcript.trim());
                    }
                });
            } catch (IOException ioe) {
                Log.w(TAG, "STT network failure: " + ioe.getMessage());
                mainHandler.post(() -> {
                    if (sessionId != activeSessionId || isFinishing() || isDestroyed()) {
                        return;
                    }
                    if ("NO_STT_KEY_CONFIGURED".equals(ioe.getMessage())) {
                        showMissingVoiceSetupCard();
                    } else {
                        showErrorWithRetry("Lỗi kết nối mạng hoặc khóa API giọng nói không hợp lệ.");
                    }
                });
            } catch (Throwable t) {
                Log.e(TAG, "Unexpected STT failure: " + t.getMessage());
                mainHandler.post(() -> {
                    if (sessionId != activeSessionId || isFinishing() || isDestroyed()) {
                        return;
                    }
                    showErrorWithRetry("Không thể xử lý giọng nói. Vui lòng thử lại.");
                });
            }
        }, "TvHome-SttWorker");
        sttWorker.start();
    }

    private void dispatchRecognizedTranscript(String transcript) {
        uiState = STATE_DONE;
        micPulseView.setVisualMode(STATE_DONE);
        retryBadgeView.setVisibility(View.GONE);

        if (launchMode == MODE_IME_DICTATE) {
            Intent imeBroadcast = new Intent(VnTvImeService.ACTION_IME_VOICE_RESULT)
                    .setPackage(getPackageName())
                    .putExtra("text", transcript);
            sendBroadcast(imeBroadcast, VnTvImeService.PERMISSION_INTERNAL_IPC);
            titleView.setText("✓ Đã nhập: " + transcript);
            subtitleView.setText("Đã chèn văn bản vào bàn phím");
            mainHandler.postDelayed(this::finish, 320L);
            return;
        }

        if (launchMode == MODE_RECOGNIZE_RESULT) {
            Intent res = new Intent();
            ArrayList<String> list = new ArrayList<>(1);
            list.add(transcript);
            res.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, list);
            res.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[]{0.95f});

            Intent srcIntent = getIntent();
            if (srcIntent != null) {
                @SuppressWarnings("deprecation")
                PendingIntent pi = srcIntent.getParcelableExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT);
                if (pi != null) {
                    Bundle extraBundle = srcIntent.getBundleExtra(
                            RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE);
                    if (extraBundle != null) {
                        res.putExtras(extraBundle);
                    }
                    try {
                        pi.send(this, RESULT_OK, res);
                    } catch (PendingIntent.CanceledException ce) {
                        Log.w(TAG, "Caller PendingIntent canceled: " + ce.getMessage());
                    }
                }
            }
            setResult(RESULT_OK, res);
            finish();
            return;
        }

        // Mode 3: Global Voice Search -> SmartTube / Quick App Launch
        TvVoiceCommandRouter.ParsedCommand voiceCmd = TvVoiceCommandRouter.parseCommand(transcript);
        if (voiceCmd != null) {
            TvVoiceCommandRouter.CommandResult res = TvVoiceCommandRouter.executeCommand(this, voiceCmd);
            if (res != null && res.handled) {
                titleView.setText(res.title);
                subtitleView.setText(res.subtitle);
                if (res.dismissDelayMs > 0L) {
                    mainHandler.removeCallbacks(errorAutoFinishRunnable);
                    mainHandler.postDelayed(this::finish, res.dismissDelayMs);
                } else {
                    finish();
                }
                return;
            }
        }

        titleView.setText("✓ " + transcript);
        Intent target = buildSmartTubeSearchIntent(this, transcript);
        launchSearchOrAppSafely(target, transcript);
        finish();
    }

    private void launchSearchOrAppSafely(Intent primary, String rawTranscript) {
        try {
            startActivity(primary);
            return;
        } catch (ActivityNotFoundException firstErr) {
            Log.w(TAG, "Primary target failed (" + firstErr.getMessage() + "), trying fallback.");
        }

        String cleanQuery = cleanSearchTranscript(rawTranscript);
        Uri uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(cleanQuery));
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP;

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri)
                    .setPackage(PKG_SMARTTUBE_STABLE)
                    .putExtra(SearchManager.QUERY, cleanQuery)
                    .addFlags(flags));
            return;
        } catch (ActivityNotFoundException secondErr) {
            Log.w(TAG, "SmartTube package fallback failed: " + secondErr.getMessage());
        }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri)
                    .setPackage(PKG_YOUTUBE_TV)
                    .putExtra(SearchManager.QUERY, cleanQuery)
                    .addFlags(flags));
            return;
        } catch (ActivityNotFoundException ytErr) {
            Log.w(TAG, "YouTube TV package fallback failed: " + ytErr.getMessage());
        }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri)
                    .putExtra(SearchManager.QUERY, cleanQuery)
                    .addFlags(flags));
        } catch (ActivityNotFoundException finalErr) {
            Toast.makeText(this, "Chưa cài đặt YouTube / SmartTube để mở: " + cleanQuery, Toast.LENGTH_SHORT).show();
        }
    }

    private void showErrorWithRetry(String vietnameseError) {
        uiState = STATE_ERROR;
        missingSetupMode = false;
        micPulseView.setRmsLevel(0.0f);
        micPulseView.setVisualMode(STATE_ERROR);
        titleView.setText(vietnameseError);
        subtitleView.setText("Nhấn giữ nút Micro hoặc bấm OK để thử lại");
        retryBadgeView.setText("[Thử lại]");
        retryBadgeView.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(errorAutoFinishRunnable);
        mainHandler.postDelayed(errorAutoFinishRunnable, ERROR_DISMISS_DELAY_MS);
    }

    private void handlePhysicalVoiceKeyChanged(boolean held) {
        if (uiState != STATE_LISTENING) {
            return;
        }
        updateListeningPrompt(held, speechStartedFlag);
        if (!held && speechRecognizer != null && speechRecognizerListening) {
            long heldElapsed = SystemClock.elapsedRealtime() - speechListenStartElapsedMs;
            if (speechStartedFlag || heldElapsed >= 450L) {
                stopSpeechRecognizerListeningEarly();
            }
        }
    }

    private void stopSpeechRecognizerListeningEarly() {
        if (speechRecognizer != null && speechRecognizerListening) {
            speechRecognizerListening = false;
            try {
                speechRecognizer.stopListening();
            } catch (Throwable t) {
                Log.w(TAG, "speechRecognizer.stopListening warning: " + t.getMessage());
            }
        }
    }

    private void destroySpeechRecognizer() {
        speechRecognizerListening = false;
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (Throwable t) {
                Log.d(TAG, "speechRecognizer.cancel ignored: " + t.getMessage());
            }
            try {
                speechRecognizer.destroy();
            } catch (Throwable t) {
                Log.d(TAG, "speechRecognizer.destroy ignored: " + t.getMessage());
            }
            speechRecognizer = null;
        }
    }

    private static boolean isOverlayVoiceKeyEvent(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || keyCode == KeyEvent.KEYCODE_SEARCH) {
            return true;
        }
        return event != null
                && TvHardwareProfiler.isXiaomiMitvRom()
                && event.getScanCode() == VoiceAudioRecorder.XIAOMI_VOICE_SCANCODE;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isOverlayVoiceKeyEvent(keyCode, event)) {
            VoiceAudioRecorder.notifyPhysicalVoiceKeyState(true);
            handlePhysicalVoiceKeyChanged(true);
            if (uiState == STATE_ERROR && !missingSetupMode && event != null && event.getRepeatCount() == 0) {
                mainHandler.removeCallbacks(errorAutoFinishRunnable);
                ensurePermissionAndListen();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (uiState == STATE_LISTENING) {
                if (recorder != null) {
                    recorder.finishEarly();
                } else if (speechRecognizer != null && speechRecognizerListening) {
                    stopSpeechRecognizerListeningEarly();
                }
                return true;
            }
            if (uiState == STATE_ERROR) {
                mainHandler.removeCallbacks(errorAutoFinishRunnable);
                if (missingSetupMode) {
                    openLauncherSettingsAndFinish();
                } else {
                    ensurePermissionAndListen();
                }
                return true;
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            cancelAndFinish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isOverlayVoiceKeyEvent(keyCode, event)) {
            VoiceAudioRecorder.notifyPhysicalVoiceKeyState(false);
            handlePhysicalVoiceKeyChanged(false);
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void cancelAndFinish() {
        activeSessionId++;
        mainHandler.removeCallbacks(errorAutoFinishRunnable);
        stopActiveRecorder();
        if (launchMode == MODE_RECOGNIZE_RESULT) {
            setResult(RESULT_CANCELED);
        }
        finish();
    }

    private void stopActiveRecorder() {
        destroySpeechRecognizer();
        if (recorder != null) {
            recorder.cancel();
            recorder = null;
        }
    }

    @Override
    protected void onStop() {
        if (activeOverlay == this) {
            activeOverlay = null;
        }
        super.onStop();
        activeSessionId++;
        mainHandler.removeCallbacks(errorAutoFinishRunnable);
        stopActiveRecorder();
        if (!isFinishing()) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (activeOverlay == this) {
            activeOverlay = null;
        }
        activeSessionId++;
        mainHandler.removeCallbacksAndMessages(null);
        stopActiveRecorder();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void finish() {
        if (activeOverlay == this) {
            activeOverlay = null;
        }
        super.finish();
        overridePendingTransition(0, 0);
    }

    private static int dp(DisplayMetrics dm, int dpVal) {
        return Math.round(dpVal * dm.density);
    }

    private static float dpF(DisplayMetrics dm, float dpVal) {
        return dpVal * dm.density;
    }

    /**
     * Custom 60fps Apple TV-inspired circular microphone pulse indicator reactive to RMS audio energy.
     */
    private static final class MicPulseView extends View {
        private final Paint haloOuterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint haloInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tempRect = new RectF();

        private float targetRms = 0.0f;
        private float smoothedRms = 0.0f;
        private int mode = STATE_LISTENING;

        MicPulseView(Context context, DisplayMetrics dm) {
            super(context);
            haloOuterPaint.setStyle(Paint.Style.FILL);
            haloInnerPaint.setStyle(Paint.Style.FILL);
            corePaint.setStyle(Paint.Style.FILL);

            iconFillPaint.setStyle(Paint.Style.FILL);
            iconFillPaint.setColor(0xFFFFFFFF);

            iconStrokePaint.setStyle(Paint.Style.STROKE);
            iconStrokePaint.setColor(0xFFFFFFFF);
            iconStrokePaint.setStrokeWidth(dpF(dm, 2.2f));
            iconStrokePaint.setStrokeCap(Paint.Cap.ROUND);
        }

        void setRmsLevel(float rms) {
            targetRms = Math.max(0.0f, Math.min(1.0f, rms));
            postInvalidateOnAnimation();
        }

        void setVisualMode(int visualMode) {
            this.mode = visualMode;
            postInvalidateOnAnimation();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float cx = w * 0.5f;
            float cy = h * 0.5f;
            float maxR = Math.min(w, h) * 0.5f;

            smoothedRms += (targetRms - smoothedRms) * 0.35f;
            long now = SystemClock.uptimeMillis();
            float idleWave = (float) (0.5 + 0.5 * Math.sin(now * 0.0055));

            int accentColor;
            int outerAlpha;
            int innerAlpha;
            if (mode == STATE_ERROR) {
                accentColor = 0xFFEF4444;
                outerAlpha = 0x26;
                innerAlpha = 0x44;
            } else if (mode == STATE_DONE) {
                accentColor = 0xFF22C55E;
                outerAlpha = 0x2E;
                innerAlpha = 0x50;
            } else if (mode == STATE_TRANSCRIBING) {
                accentColor = 0xFFF59E0B;
                outerAlpha = (int) (0x24 + 0x28 * idleWave);
                innerAlpha = (int) (0x44 + 0x28 * idleWave);
            } else {
                accentColor = 0xFF38BDF8;
                outerAlpha = (int) (0x22 + 0x45 * smoothedRms);
                innerAlpha = (int) (0x3A + 0x55 * smoothedRms);
            }

            float dynamicBoost = (mode == STATE_LISTENING)
                    ? (0.06f * idleWave + 0.36f * smoothedRms)
                    : (0.08f * idleWave);

            float outerR = maxR * Math.min(1.0f, 0.62f + dynamicBoost);
            float innerR = maxR * Math.min(0.90f, 0.54f + dynamicBoost * 0.65f);
            float coreR = maxR * 0.48f;

            haloOuterPaint.setColor((accentColor & 0x00FFFFFF) | (Math.min(255, outerAlpha) << 24));
            haloInnerPaint.setColor((accentColor & 0x00FFFFFF) | (Math.min(255, innerAlpha) << 24));
            corePaint.setColor(accentColor);

            canvas.drawCircle(cx, cy, outerR, haloOuterPaint);
            canvas.drawCircle(cx, cy, innerR, haloInnerPaint);
            canvas.drawCircle(cx, cy, coreR, corePaint);

            // Draw crisp vector microphone glyph inside core circle
            float capHalfW = coreR * 0.22f;
            float capTop = cy - coreR * 0.42f;
            float capBottom = cy + coreR * 0.08f;
            tempRect.set(cx - capHalfW, capTop, cx + capHalfW, capBottom);
            canvas.drawRoundRect(tempRect, capHalfW, capHalfW, iconFillPaint);

            float arcR = coreR * 0.36f;
            tempRect.set(cx - arcR, cy - coreR * 0.28f, cx + arcR, cy + coreR * 0.24f);
            canvas.drawArc(tempRect, 0f, 180f, false, iconStrokePaint);

            float stemTop = cy + coreR * 0.24f;
            float stemBottom = cy + coreR * 0.44f;
            canvas.drawLine(cx, stemTop, cx, stemBottom, iconStrokePaint);
            canvas.drawLine(cx - coreR * 0.20f, stemBottom, cx + coreR * 0.20f, stemBottom, iconStrokePaint);

            if (mode == STATE_LISTENING || mode == STATE_TRANSCRIBING) {
                postInvalidateOnAnimation();
            }
        }
    }
}
