package vn.viettv.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private static final String TAG = "TvHome.Settings";
    private static final String VN_IME_ID = "vn.viettv.launcher/.VnTvImeService";
    private static final String VOICE_SEARCH_APP_KEY = "voice_search_app";
    private static final String VOICE_MODE_KEY = "voice_mode";
    private static final String VOICE_API_KEY = "voice_api_key";
    private static final String VOICE_API_URL_KEY = "voice_api_url";

    private static final class AppOption {
        final String pkg;
        final String label;

        AppOption(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private TextView wallpaperChoice;
    private TextView dockAppsChoice;
    private TextView dashboardCardsChoice;
    private TextView fuelRegionChoice;
    private TextView greetingNameChoice;
    private TextView voiceSearchAppChoice;
    private TextView voiceEngineChoice;
    private TextView imePickerChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private int columns;
    private int rowsBelowDock;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        Locale viLocale = new Locale("vi", "VN");
        Locale.setDefault(viLocale);
        android.content.res.Configuration localeCfg = getResources().getConfiguration();
        localeCfg.setLocale(viLocale);
        getResources().updateConfiguration(localeCfg, getResources().getDisplayMetrics());
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                commitSettingsChange(choices.edit().putInt("columns", value));
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                commitSettingsChange(choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value));
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());

        dockAppsChoice = findViewById(R.id.dockAppsChoice);
        dockAppsChoice.setOnClickListener(v -> showDockAppsPicker());

        dashboardCardsChoice = findViewById(R.id.dashboardCardsChoice);
        dashboardCardsChoice.setOnClickListener(v -> showDashboardCardsPicker());

        fuelRegionChoice = findViewById(R.id.fuelRegionChoice);
        fuelRegionChoice.setOnClickListener(v -> showFuelRegionPicker());

        greetingNameChoice = findViewById(R.id.greetingNameChoice);
        greetingNameChoice.setOnClickListener(v -> showGreetingNameDialog());

        voiceSearchAppChoice = findViewById(R.id.voiceSearchAppChoice);
        voiceSearchAppChoice.setOnClickListener(v -> showVoiceSearchAppPicker());

        voiceEngineChoice = findViewById(R.id.voiceEngineChoice);
        voiceEngineChoice.setOnClickListener(v -> showVoiceEngineDialog());

        imePickerChoice = findViewById(R.id.imePickerChoice);
        imePickerChoice.setOnClickListener(v -> openImePickerOrSettings());

        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            commitSettingsChange(choices.edit()
                    .putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects));
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            long nextRev = choices.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L;
            choices.edit()
                    .remove("columns")
                    .remove(Config.ROWS_BELOW_DOCK_KEY)
                    .remove("animations")
                    .remove("advanced_effects")
                    .remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.CUSTOM_DOCK_APPS_KEY)
                    .remove(Config.CUSTOM_GREETING_NAME_KEY)
                    .remove(Config.SHOW_CARD_CALENDAR_KEY)
                    .remove(Config.SHOW_CARD_GOLD_KEY)
                    .remove(Config.SHOW_CARD_FUEL_KEY)
                    .remove(Config.FUEL_REGION_KEY)
                    .remove(VOICE_SEARCH_APP_KEY)
                    .remove(VOICE_MODE_KEY)
                    .putLong(Config.SETTINGS_REVISION_KEY, nextRev)
                    .commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            setResult(RESULT_OK);
            Toast.makeText(this, "Đã khôi phục cài đặt mặc định", Toast.LENGTH_SHORT).show();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(BootReceiver.resolveXiaomiSettingsIntent(this, Settings.ACTION_SETTINGS)));
        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (choices != null) {
            refresh();
        }
    }

    private void commitSettingsChange(SharedPreferences.Editor editor) {
        long nextRev = choices.getLong(Config.SETTINGS_REVISION_KEY, 0L) + 1L;
        editor.putLong(Config.SETTINGS_REVISION_KEY, nextRev).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private List<AppOption> queryInstallableTvApps(Config cfg) {
        PackageManager pm = getPackageManager();
        String selfPkg = getPackageName();
        Set<String> seen = new HashSet<>();
        List<AppOption> result = new ArrayList<>();

        Intent leanbackIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Intent mainIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> combined = new ArrayList<>();
        combined.addAll(pm.queryIntentActivities(leanbackIntent, 0));
        combined.addAll(pm.queryIntentActivities(mainIntent, 0));

        for (ResolveInfo ri : combined) {
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.isEmpty() || pkg.equals(selfPkg)) continue;
            if (!seen.add(pkg)) continue;
            String customLabel = cfg.appLabels.get(pkg);
            CharSequence sysLabel = customLabel != null ? customLabel : ri.loadLabel(pm);
            String label = (sysLabel != null && sysLabel.length() > 0) ? sysLabel.toString().trim() : pkg;
            result.add(new AppOption(pkg, label));
        }

        Collator collator = Collator.getInstance(new Locale("vi", "VN"));
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(result, (a, b) -> collator.compare(a.label, b.label));
        return result;
    }

    private void showDockAppsPicker() {
        Config cfg = Config.load(this);
        List<AppOption> apps = queryInstallableTvApps(cfg);
        if (apps.isEmpty()) {
            Toast.makeText(this, R.string.library_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        Set<String> installedSet = new HashSet<>();
        for (AppOption opt : apps) installedSet.add(opt.pkg);

        final List<String> orderedSelected = new ArrayList<>();
        for (String pkg : cfg.dockApps) {
            if (installedSet.contains(pkg) && !orderedSelected.contains(pkg)) {
                orderedSelected.add(pkg);
            }
        }

        String[] labels = new String[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            AppOption opt = apps.get(i);
            labels[i] = opt.label;
            checked[i] = orderedSelected.contains(opt.pkg);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.dock_apps_title)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    String pkg = apps.get(which).pkg;
                    if (isChecked) {
                        if (!orderedSelected.contains(pkg)) orderedSelected.add(pkg);
                    } else {
                        orderedSelected.remove(pkg);
                    }
                })
                .setPositiveButton(R.string.settings_save, (dialog, which) -> {
                    SharedPreferences.Editor ed = choices.edit();
                    if (orderedSelected.isEmpty()) {
                        ed.remove(Config.CUSTOM_DOCK_APPS_KEY);
                        Toast.makeText(this, "Đã đặt thanh Dock về mặc định tự động", Toast.LENGTH_SHORT).show();
                    } else {
                        ed.putString(Config.CUSTOM_DOCK_APPS_KEY, String.join(",", orderedSelected));
                    }
                    commitSettingsChange(ed);
                })
                .setNeutralButton(R.string.settings_reset_auto, (dialog, which) -> {
                    commitSettingsChange(choices.edit().remove(Config.CUSTOM_DOCK_APPS_KEY));
                    Toast.makeText(this, "Đã khôi phục thanh Dock mặc định tự động", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDashboardCardsPicker() {
        Config cfg = Config.load(this);
        String[] labels = new String[]{
                "Lịch Vạn Niên Âm - Dương",
                "Giá Vàng SJC & Nhẫn 999.9",
                "Giá Xăng Dầu Petrolimex"
        };
        final boolean[] selected = new boolean[]{
                cfg.showCardCalendar,
                cfg.showCardGold,
                cfg.showCardFuel
        };

        new AlertDialog.Builder(this)
                .setTitle(R.string.dashboard_cards_title)
                .setMultiChoiceItems(labels, selected, (dialog, which, isChecked) -> selected[which] = isChecked)
                .setPositiveButton(R.string.settings_save, (dialog, which) -> commitSettingsChange(
                        choices.edit()
                                .putBoolean(Config.SHOW_CARD_CALENDAR_KEY, selected[0])
                                .putBoolean(Config.SHOW_CARD_GOLD_KEY, selected[1])
                                .putBoolean(Config.SHOW_CARD_FUEL_KEY, selected[2])))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showFuelRegionPicker() {
        Config cfg = Config.load(this);
        String[] items = new String[]{
                "Vùng 1 (Hà Nội, TP.HCM, thành phố lớn, gần cảng)",
                "Vùng 2 (Miền núi, trung du, Tây Nguyên, xa cảng)"
        };
        int checked = (cfg.fuelRegion == 2) ? 1 : 0;

        new AlertDialog.Builder(this)
                .setTitle(R.string.fuel_region_title)
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    dialog.dismiss();
                    int chosenRegion = (which == 1) ? 2 : 1;
                    commitSettingsChange(choices.edit().putInt(Config.FUEL_REGION_KEY, chosenRegion));
                    Toast.makeText(this,
                            "Đã chọn giá xăng Petrolimex Vùng " + chosenRegion,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private EditText createDarkDialogEditText(String initialValue, String hint, int inputType) {
        float density = getResources().getDisplayMetrics().density;
        EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setInputType(inputType);
        editText.setTextColor(0xFFFFFFFF);
        editText.setHintTextColor(0xFF9BA1AC);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        editText.setHint(hint);
        if (initialValue != null && !initialValue.isEmpty()) {
            editText.setText(initialValue);
            editText.setSelection(initialValue.length());
        }
        int padH = Math.round(16f * density);
        int padV = Math.round(12f * density);
        editText.setPadding(padH, padV, padH, padV);
        return editText;
    }

    private FrameLayout wrapDialogInput(EditText editText) {
        float density = getResources().getDisplayMetrics().density;
        FrameLayout wrapper = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        int marginH = Math.round(24f * density);
        int marginTop = Math.round(12f * density);
        int marginBottom = Math.round(4f * density);
        lp.setMargins(marginH, marginTop, marginH, marginBottom);
        wrapper.addView(editText, lp);
        return wrapper;
    }

    private void showGreetingNameDialog() {
        Config cfg = Config.load(this);
        final EditText input = createDarkDialogEditText(
                cfg.greetingName,
                "Ví dụ: Gia đình Minh Anh (để trống nếu không dùng)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);

        new AlertDialog.Builder(this)
                .setTitle(R.string.greeting_name_title)
                .setView(wrapDialogInput(input))
                .setPositiveButton(R.string.settings_save, (dialog, which) -> {
                    String value = input.getText() == null ? "" : input.getText().toString().trim();
                    commitSettingsChange(choices.edit().putString(Config.CUSTOM_GREETING_NAME_KEY, value));
                })
                .setNeutralButton(R.string.settings_reset_auto, (dialog, which) ->
                        commitSettingsChange(choices.edit().remove(Config.CUSTOM_GREETING_NAME_KEY)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showVoiceSearchAppPicker() {
        final String[] values = new String[]{
                "auto",
                "org.smarttube.stable",
                "com.google.android.youtube.tv"
        };
        final String[] labels = new String[]{
                "Tự động (Ưu tiên SmartTube, dự phòng YouTube)",
                "SmartTube (Không quảng cáo)",
                "YouTube chính thức"
        };
        String current = choices.getString(VOICE_SEARCH_APP_KEY, "auto");
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(current)) {
                checked = i;
                break;
            }
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.voice_search_app_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    commitSettingsChange(choices.edit().putString(VOICE_SEARCH_APP_KEY, values[which]));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showVoiceEngineDialog() {
        String[] options = new String[]{
                "Tự động dùng bộ giọng nói sẵn có trên Tivi / Offline CPU (Khuyên dùng - Miễn phí)",
                "Nhập khóa API miễn phí cá nhân (Groq Whisper hoặc khóa Google Speech)",
                "Nhập máy chủ Whisper nội bộ trong mạng LAN (http://IP:8000/v1/audio/transcriptions)",
                "Xóa khóa API cá nhân"
        };

        new AlertDialog.Builder(this)
                .setTitle(R.string.voice_engine_title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        commitSettingsChange(choices.edit().putString(VOICE_MODE_KEY, "auto"));
                        Toast.makeText(this, "Đã bật chế độ giọng nói tự động trên Tivi", Toast.LENGTH_SHORT).show();
                    } else if (which == 1) {
                        showVoiceApiKeyDialog();
                    } else if (which == 2) {
                        showVoiceLanUrlDialog();
                    } else if (which == 3) {
                        commitSettingsChange(choices.edit()
                                .remove(VOICE_API_KEY)
                                .remove(VOICE_API_URL_KEY)
                                .putString(VOICE_MODE_KEY, "auto"));
                        Toast.makeText(this, "Đã xóa khóa API cá nhân", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showVoiceApiKeyDialog() {
        String currentKey = choices.getString(VOICE_API_KEY, "");
        final EditText input = createDarkDialogEditText(
                currentKey,
                "Ví dụ: gsk_... (Groq Whisper) hoặc khóa Google Speech",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);

        new AlertDialog.Builder(this)
                .setTitle("Nhập khóa API cá nhân")
                .setView(wrapDialogInput(input))
                .setPositiveButton("Lưu (Tự động dự phòng)", (dialog, which) -> {
                    String key = input.getText() == null ? "" : input.getText().toString().trim();
                    SharedPreferences.Editor ed = choices.edit();
                    if (key.isEmpty()) {
                        ed.remove(VOICE_API_KEY).putString(VOICE_MODE_KEY, "auto");
                    } else {
                        ed.putString(VOICE_API_KEY, key).putString(VOICE_MODE_KEY, "auto");
                    }
                    commitSettingsChange(ed);
                    Toast.makeText(this, "Đã lưu khóa API (Tự động dự phòng)", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Luôn dùng Cloud Key", (dialog, which) -> {
                    String key = input.getText() == null ? "" : input.getText().toString().trim();
                    SharedPreferences.Editor ed = choices.edit();
                    if (key.isEmpty()) {
                        ed.remove(VOICE_API_KEY).putString(VOICE_MODE_KEY, "auto");
                    } else {
                        ed.putString(VOICE_API_KEY, key).putString(VOICE_MODE_KEY, "cloud_key");
                    }
                    commitSettingsChange(ed);
                    Toast.makeText(this, "Đã bật chế độ ưu tiên khóa API cá nhân", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showVoiceLanUrlDialog() {
        String currentUrl = choices.getString(VOICE_API_URL_KEY, "");
        final EditText input = createDarkDialogEditText(
                currentUrl,
                "http://192.168.1.10:8000/v1/audio/transcriptions",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        new AlertDialog.Builder(this)
                .setTitle("Máy chủ Whisper nội bộ (LAN)")
                .setView(wrapDialogInput(input))
                .setPositiveButton(R.string.settings_save, (dialog, which) -> {
                    String url = input.getText() == null ? "" : input.getText().toString().trim();
                    SharedPreferences.Editor ed = choices.edit();
                    if (url.isEmpty()) {
                        ed.remove(VOICE_API_URL_KEY);
                    } else {
                        ed.putString(VOICE_API_URL_KEY, url).putString(VOICE_MODE_KEY, "auto");
                    }
                    commitSettingsChange(ed);
                    Toast.makeText(this, "Đã cập nhật địa chỉ Whisper LAN", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean isVietTvImeDefault() {
        try {
            String defIme = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            return VN_IME_ID.equals(defIme);
        } catch (Throwable t) {
            Log.d(TAG, "Cannot read default IME: " + t.getMessage());
            return false;
        }
    }

    private boolean isVietTvImeEnabled() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                List<InputMethodInfo> list = imm.getEnabledInputMethodList();
                if (list != null) {
                    for (InputMethodInfo info : list) {
                        if (info != null && (VN_IME_ID.equals(info.getId())
                                || getPackageName().equals(info.getPackageName()))) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            Log.d(TAG, "Cannot inspect enabled IME list: " + t.getMessage());
        }
        return false;
    }

    private void openImePickerOrSettings() {
        BootReceiver.enforcePowerAndHomeGuard(this);
        if (isVietTvImeDefault()) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            Toast.makeText(this,
                    "Bàn phím Tiếng Việt Telex đang là bàn phím mặc định!",
                    Toast.LENGTH_SHORT).show();
            if (imm != null) {
                imm.showInputMethodPicker();
            }
            refresh();
            return;
        }

        if (isVietTvImeEnabled()) {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                Toast.makeText(this,
                        "Hãy chọn Bàn phím Tiếng Việt Telex trong bảng chọn bàn phím",
                        Toast.LENGTH_LONG).show();
                imm.showInputMethodPicker();
                return;
            }
        }

        Toast.makeText(this,
                "Bước 1: Bật Bàn phím Tiếng Việt Telex · Bước 2: Quay lại nhấn lần nữa để chọn!",
                Toast.LENGTH_LONG).show();
        Intent imeSettings = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
        try {
            startActivity(imeSettings);
        } catch (ActivityNotFoundException | SecurityException e) {
            open(BootReceiver.resolveXiaomiSettingsIntent(this, Settings.ACTION_SETTINGS));
        }
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        String[] labels = new String[images.size() + 1];
        for (int i = 0; i < images.size(); i++) labels[i] = images.get(i).getName();
        labels[images.size()] = getString(R.string.wallpaper_none);
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = "none".equals(current) ? images.size()
                : indexOf(images, current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    SharedPreferences.Editor ed = choices.edit();
                    if (which == images.size()) {
                        ed.putString(Config.WALLPAPER_CHOICE_KEY, "none");
                    } else {
                        ed.putString(Config.WALLPAPER_CHOICE_KEY, images.get(which).getName());
                    }
                    commitSettingsChange(ed);
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) ->
                        commitSettingsChange(choices.edit().remove(Config.WALLPAPER_CHOICE_KEY)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static int indexOf(List<File> images, String name) {
        if (name == null) return -1;
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).getName().equals(name)) return i;
        }
        return -1;
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        commitSettingsChange(choices.edit().putBoolean("advanced_effects", enabled));
    }

    private String resolveAppName(Config cfg, String pkg, boolean requireInstalled) {
        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (!ai.enabled && requireInstalled) return null;
            String custom = cfg.appLabels.get(pkg);
            if (custom != null && !custom.isEmpty()) return custom;
            CharSequence label = pm.getApplicationLabel(ai);
            return (label != null && label.length() > 0) ? label.toString() : pkg;
        } catch (PackageManager.NameNotFoundException notInstalled) {
            if (requireInstalled) return null;
            String custom = cfg.appLabels.get(pkg);
            return (custom != null && !custom.isEmpty()) ? custom : pkg;
        }
    }

    private String describeCurrentDock(Config cfg) {
        boolean isCustom = choices.contains(Config.CUSTOM_DOCK_APPS_KEY);
        List<String> names = new ArrayList<>();
        for (String pkg : cfg.dockApps) {
            String name = resolveAppName(cfg, pkg, !isCustom);
            if (name != null && !names.contains(name)) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            return "Mặc định tự động";
        }
        String joined = String.join(", ", names);
        return isCustom ? joined : ("Tự động (" + joined + ")");
    }

    private String describeDashboardCards(Config cfg) {
        List<String> enabled = new ArrayList<>(3);
        if (cfg.showCardCalendar) enabled.add("Lịch Âm");
        if (cfg.showCardGold) enabled.add("Giá Vàng");
        if (cfg.showCardFuel) enabled.add("Giá Xăng");
        if (enabled.isEmpty()) {
            return "Đã ẩn toàn bộ (Tối giản)";
        }
        return String.join(" · ", enabled);
    }

    private String describeVoiceSearchApp() {
        String val = choices.getString(VOICE_SEARCH_APP_KEY, "auto");
        if ("org.smarttube.stable".equals(val)) {
            return "SmartTube (Không quảng cáo)";
        }
        if ("com.google.android.youtube.tv".equals(val)) {
            return "YouTube chính thức";
        }
        return "Tự động (Ưu tiên SmartTube, dự phòng YouTube)";
    }

    private void refresh() {
        Config cfg = Config.load(this);
        columns = cfg.columns;
        rowsBelowDock = cfg.rowsBelowDock;
        animations = cfg.animations;
        advancedEffects = cfg.animations && cfg.advancedEffects;

        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }

        dockAppsChoice.setText("📌 Ứng dụng trên thanh Dock: " + describeCurrentDock(cfg));
        dashboardCardsChoice.setText("📊 Bảng thông tin: " + describeDashboardCards(cfg));
        fuelRegionChoice.setText("⛽ Khu vực giá xăng Petrolimex: "
                + (cfg.fuelRegion == 2
                ? "Vùng 2 (Miền núi, trung du, Tây Nguyên)"
                : "Vùng 1 (Hà Nội, TP.HCM, thành phố lớn)"));
        greetingNameChoice.setText("👋 Lời chào tên riêng: "
                + (cfg.greetingName.isEmpty() ? "Mặc định (Không gắn tên)" : cfg.greetingName));
        voiceSearchAppChoice.setText("🎬 Ứng dụng mở khi tìm video giọng nói: " + describeVoiceSearchApp());

        String backendStatus = TvHardwareProfiler.describeVoiceBackend(this);
        if (backendStatus == null || backendStatus.trim().isEmpty()) {
            backendStatus = "Tự động";
        }
        voiceEngineChoice.setText("🎤 Nhận diện Giọng nói Tiếng Việt: " + backendStatus);

        if (isVietTvImeDefault()) {
            imePickerChoice.setText("⌨️ Bật / Chọn Bàn phím Tiếng Việt Telex: Đang dùng (Mặc định)");
        } else if (isVietTvImeEnabled()) {
            imePickerChoice.setText("⌨️ Bật / Chọn Bàn phím Tiếng Việt Telex: Đã bật (Nhấn để chọn)");
        } else {
            imePickerChoice.setText("⌨️ Bật / Chọn Bàn phím Tiếng Việt Telex");
        }

        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state,
                "none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                        : wallpaperCurrent != null ? wallpaperCurrent
                        : getString(R.string.wallpaper_theme_default)));
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns")
                || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations")
                || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY)
                || choices.contains(Config.CUSTOM_DOCK_APPS_KEY)
                || choices.contains(Config.CUSTOM_GREETING_NAME_KEY)
                || choices.contains(Config.SHOW_CARD_CALENDAR_KEY)
                || choices.contains(Config.SHOW_CARD_GOLD_KEY)
                || choices.contains(Config.SHOW_CARD_FUEL_KEY)
                || choices.contains(Config.FUEL_REGION_KEY)
                || choices.contains(VOICE_SEARCH_APP_KEY)
                || choices.contains(VOICE_MODE_KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
