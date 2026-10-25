package vn.viettv.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Vietnamese Telex & Voice InputMethodService tailored for Android TV 5-way D-Pad remotes.
 * Built 100% programmatically with Apple TV Dark Glass styling and deterministic grid focus.
 */
public class VnTvImeService extends InputMethodService {

    private static final String TAG = "VnTvImeService";

    public static final String ACTION_IME_VOICE_RESULT = "vn.viettv.launcher.ACTION_IME_VOICE_RESULT";
    public static final String ACTION_IME_DICTATE = "vn.viettv.launcher.ACTION_IME_DICTATE";
    public static final String PERMISSION_INTERNAL_IPC = "vn.viettv.launcher.permission.INTERNAL_IPC";
    public static final int XIAOMI_VOICE_SCANCODE = 63;

    private static volatile VnTvImeService activeVisibleInstance = null;

    private static final int COLOR_PANEL_BG = 0xF214161C;
    private static final int COLOR_PANEL_STROKE = 0x33FFFFFF;
    private static final int COLOR_PREVIEW_BG = 0x22FFFFFF;
    private static final int COLOR_KEY_BG = 0x26FFFFFF;
    private static final int COLOR_KEY_STROKE = 0x2EFFFFFF;
    private static final int COLOR_VOICE_BG = 0x2E30D158;
    private static final int COLOR_VOICE_STROKE = 0x5530D158;
    private static final int COLOR_SEARCH_BG = 0x2E0A84FF;
    private static final int COLOR_SEARCH_STROKE = 0x550A84FF;
    private static final int COLOR_SHIFT_ON_BG = 0x44FF9F0A;
    private static final int COLOR_SHIFT_ON_STROKE = 0x88FF9F0A;
    private static final int COLOR_CHIP_BG = 0x2B5E5CE6;
    private static final int COLOR_CHIP_STROKE = 0x557D7AFF;
    private static final int COLOR_FOCUS_FILL = 0xFF0A84FF;
    private static final int COLOR_FOCUS_BORDER = 0xFF66B2FF;
    private static final int COLOR_TEXT_PRIMARY = 0xFFF5F5F7;
    private static final int COLOR_TEXT_SECONDARY = 0xFF98989D;
    private static final int COLOR_BADGE_VI = 0xFF30D158;

    private static final int KIND_REGULAR = 0;
    private static final int KIND_VOICE = 1;
    private static final int KIND_MODE = 2;
    private static final int KIND_SEARCH = 3;
    private static final int KIND_SHIFT = 4;
    private static final int KIND_CHIP = 5;

    private static final class KeyCell {
        final TextView view;
        final Runnable action;
        final char letterChar;
        final int kind;

        KeyCell(TextView view, Runnable action, char letterChar, int kind) {
            this.view = view;
            this.action = action;
            this.letterChar = letterChar;
            this.kind = kind;
        }
    }

    private final StringBuilder composingWord = new StringBuilder();
    private boolean telexEnabled = true;
    private boolean shiftOn = false;

    private final List<List<KeyCell>> rows = new ArrayList<>(6);
    private int focusRow = 2;
    private int focusCol = 4;
    private volatile boolean windowVisible = false;
    private boolean sawCenterDownWhileVisible = false;
    private boolean consumePendingBackUp = false;
    private boolean receiverRegistered = false;

    public static boolean isImeWindowVisible() {
        VnTvImeService inst = activeVisibleInstance;
        return inst != null && inst.windowVisible;
    }

    private LinearLayout rootLayout;
    private LinearLayout suggestionRowView;
    private TextView previewTextView;
    private TextView modeBadgeView;
    private KeyCell modeToggleCell;
    private KeyCell shiftKeyCell;

    private final BroadcastReceiver voiceResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_IME_VOICE_RESULT.equals(intent.getAction())) {
                return;
            }
            String dictated = intent.getStringExtra("text");
            if (dictated == null) {
                return;
            }
            String trimmed = dictated.trim();
            if (trimmed.isEmpty()) {
                return;
            }
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) {
                return;
            }
            ic.beginBatchEdit();
            try {
                commitComposingWord(ic);
                CharSequence before = ic.getTextBeforeCursor(1, 0);
                boolean needsSpace = before != null
                        && before.length() > 0
                        && !Character.isWhitespace(before.charAt(0));
                ic.commitText(needsSpace ? (" " + trimmed) : trimmed, 1);
            } finally {
                ic.endBatchEdit();
            }
            refreshPreviewAndSuggestions();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(ACTION_IME_VOICE_RESULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(voiceResultReceiver, filter, PERMISSION_INTERNAL_IPC, null, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(voiceResultReceiver, filter, PERMISSION_INTERNAL_IPC, null);
        }
        receiverRegistered = true;
    }

    @Override
    public void onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(voiceResultReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Voice receiver already unregistered", e);
            }
            receiverRegistered = false;
        }
        windowVisible = false;
        sawCenterDownWhileVisible = false;
        if (activeVisibleInstance == this) {
            activeVisibleInstance = null;
        }
        super.onDestroy();
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    @Override
    public boolean onShowInputRequested(int flags, boolean configChange) {
        return true;
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        windowVisible = true;
        activeVisibleInstance = this;
        consumePendingBackUp = false;
        sawCenterDownWhileVisible = false;
        if (rootLayout != null && !rows.isEmpty()) {
            updateModeViews();
            updateKeyCaseLabels();
            refreshPreviewAndSuggestions();
            setFocusCell(2, 4);
        }
    }

    @Override
    public void onWindowHidden() {
        windowVisible = false;
        sawCenterDownWhileVisible = false;
        if (activeVisibleInstance == this) {
            activeVisibleInstance = null;
        }
        super.onWindowHidden();
    }

    public boolean shouldInterceptKeyEvents() {
        return windowVisible
                && isInputViewShown()
                && rootLayout != null
                && rootLayout.isShown()
                && !rows.isEmpty();
    }

    @Override
    public View onCreateInputView() {
        rows.clear();
        for (int i = 0; i < 6; i++) {
            rows.add(new ArrayList<>());
        }

        rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        rootLayout.setPadding(dp(18), dp(8), dp(18), dp(10));

        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setShape(GradientDrawable.RECTANGLE);
        float topR = dpF(20f);
        panelBg.setCornerRadii(new float[]{topR, topR, topR, topR, 0f, 0f, 0f, 0f});
        panelBg.setColor(COLOR_PANEL_BG);
        panelBg.setStroke(dp(1), COLOR_PANEL_STROKE);
        rootLayout.setBackground(panelBg);

        // Top preview & status bar
        LinearLayout headerBar = new LinearLayout(this);
        headerBar.setOrientation(LinearLayout.HORIZONTAL);
        headerBar.setGravity(Gravity.CENTER_VERTICAL);
        headerBar.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable previewBg = new GradientDrawable();
        previewBg.setCornerRadius(dpF(10f));
        previewBg.setColor(COLOR_PREVIEW_BG);
        headerBar.setBackground(previewBg);

        modeBadgeView = new TextView(this);
        modeBadgeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        modeBadgeView.setTypeface(Typeface.DEFAULT_BOLD);
        modeBadgeView.setPadding(0, 0, dp(12), 0);
        headerBar.addView(modeBadgeView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        previewTextView = new TextView(this);
        previewTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        previewTextView.setSingleLine(true);
        previewTextView.setEllipsize(TextUtils.TruncateAt.START);
        previewTextView.setTextColor(COLOR_TEXT_PRIMARY);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        headerBar.addView(previewTextView, previewLp);

        TextView hintView = new TextView(this);
        hintView.setText("OK: Ch\u1ECDn \u2022 Back: \u1EA8n");
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        hintView.setTextColor(COLOR_TEXT_SECONDARY);
        hintView.setPadding(dp(10), 0, 0, 0);
        headerBar.addView(hintView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.bottomMargin = dp(5);
        rootLayout.addView(headerBar, headerLp);

        // Row 0: Dynamic suggestion chips
        suggestionRowView = new LinearLayout(this);
        suggestionRowView.setOrientation(LinearLayout.HORIZONTAL);
        suggestionRowView.setGravity(Gravity.CENTER_VERTICAL);
        suggestionRowView.setVisibility(View.GONE);
        LinearLayout.LayoutParams sugLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sugLp.bottomMargin = dp(5);
        rootLayout.addView(suggestionRowView, sugLp);

        // Row 1: Digits + hyphen
        buildCharRow(1, new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-"});
        // Row 2: QWERTY top row (row=2, col=4 -> 't')
        buildCharRow(2, new String[]{"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"});
        // Row 3: Home row + Backspace (row=3, col=4 -> 'g')
        buildCharRow(3, new String[]{"a", "s", "d", "f", "g", "h", "j", "k", "l", "\u232B"});
        // Row 4: Shift + bottom letters + punctuation
        buildCharRow(4, new String[]{"\u21E7", "z", "x", "c", "v", "b", "n", "m", ".", "@"});
        // Row 5: Bottom action bar
        buildBottomActionRow();

        updateModeViews();
        updateKeyCaseLabels();
        refreshPreviewAndSuggestions();
        setFocusCell(2, 4);
        return rootLayout;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        composingWord.setLength(0);
        shiftOn = false;
        consumePendingBackUp = false;
        sawCenterDownWhileVisible = false;
        if (rootLayout != null && !rows.isEmpty()) {
            updateModeViews();
            updateKeyCaseLabels();
            refreshPreviewAndSuggestions();
            setFocusCell(2, 4);
        }
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
                                  int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        if (newSelStart == 0 && newSelEnd == 0 && composingWord.length() > 0) {
            String fieldText = readCurrentFieldText();
            if (fieldText.isEmpty()) {
                composingWord.setLength(0);
                refreshPreviewAndSuggestions();
            }
        }
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        sawCenterDownWhileVisible = false;
        InputConnection ic = getCurrentInputConnection();
        if (ic != null && composingWord.length() > 0) {
            commitComposingWord(ic);
        } else {
            composingWord.setLength(0);
        }
        super.onFinishInputView(finishingInput);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!shouldInterceptKeyEvents()) {
            return super.onKeyDown(keyCode, event);
        }
        if (event != null && event.getScanCode() == XIAOMI_VOICE_SCANCODE) {
            if (event.getRepeatCount() == 0) {
                handleVoiceDictate();
            }
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                moveFocusVertical(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveFocusVertical(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveFocusHorizontal(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveFocusHorizontal(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                sawCenterDownWhileVisible = true;
                return true;
            case KeyEvent.KEYCODE_DEL:
                handleBackspace();
                return true;
            case KeyEvent.KEYCODE_SPACE:
                handleSpace();
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                consumePendingBackUp = true;
                hideKeyboardCleanly();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) && consumePendingBackUp) {
            consumePendingBackUp = false;
            return true;
        }
        if (!shouldInterceptKeyEvents()) {
            return super.onKeyUp(keyCode, event);
        }
        if (event != null && event.getScanCode() == XIAOMI_VOICE_SCANCODE) {
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_SPACE:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                boolean shouldTrigger = sawCenterDownWhileVisible && (event == null || !event.isCanceled());
                sawCenterDownWhileVisible = false;
                if (shouldTrigger) {
                    triggerFocusedCell();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                return true;
            default:
                return super.onKeyUp(keyCode, event);
        }
    }

    private void buildCharRow(int rowIndex, String[] specs) {
        LinearLayout rowLayout = new LinearLayout(this);
        rowLayout.setOrientation(LinearLayout.HORIZONTAL);
        rowLayout.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(4);

        List<KeyCell> rowCells = rows.get(rowIndex);
        rowCells.clear();

        for (int c = 0; c < specs.length; c++) {
            String spec = specs[c];
            final int cellRow = rowIndex;
            final int cellCol = c;
            KeyCell cell;

            if ("\u232B".equals(spec)) {
                TextView tv = createKeyTextView("\u232B", 15.5f);
                cell = new KeyCell(tv, this::handleBackspace, '\0', KIND_REGULAR);
            } else if ("\u21E7".equals(spec)) {
                TextView tv = createKeyTextView("\u21E7", 15.5f);
                cell = new KeyCell(tv, this::toggleShift, '\0', KIND_SHIFT);
                shiftKeyCell = cell;
            } else if (spec.length() == 1 && spec.charAt(0) >= 'a' && spec.charAt(0) <= 'z') {
                final char baseLetter = spec.charAt(0);
                TextView tv = createKeyTextView(spec, 16f);
                cell = new KeyCell(tv, () -> handleLetterKey(baseLetter), baseLetter, KIND_REGULAR);
            } else {
                final String literal = spec;
                TextView tv = createKeyTextView(spec, 15f);
                cell = new KeyCell(tv, () -> handleLiteralCommit(literal), '\0', KIND_REGULAR);
            }

            attachClickHandler(cell, cellRow, cellCol);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
            lp.leftMargin = dp(3);
            lp.rightMargin = dp(3);
            rowLayout.addView(cell.view, lp);
            rowCells.add(cell);
        }

        rootLayout.addView(rowLayout, rowLp);
    }

    private void buildBottomActionRow() {
        LinearLayout rowLayout = new LinearLayout(this);
        rowLayout.setOrientation(LinearLayout.HORIZONTAL);
        rowLayout.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(2);

        List<KeyCell> actionCells = rows.get(5);
        actionCells.clear();

        // 0: [🎤 Nói Tiếng Việt]
        KeyCell voiceCell = new KeyCell(
                createKeyTextView("\uD83C\uDFA4 N\u00F3i Ti\u1EBFng Vi\u1EC7t", 13.5f),
                this::handleVoiceDictate, '\0', KIND_VOICE);
        addActionCell(rowLayout, actionCells, voiceCell, 0, 1.55f);

        // 1: [🇻🇳 Telex / EN]
        modeToggleCell = new KeyCell(
                createKeyTextView("\uD83C\uDDFB\uD83C\uDDF3 Telex", 13.5f),
                this::toggleTelexMode, '\0', KIND_MODE);
        addActionCell(rowLayout, actionCells, modeToggleCell, 1, 1.2f);

        // 2: [Dấu cách]
        KeyCell spaceCell = new KeyCell(
                createKeyTextView("D\u1EA5u c\u00E1ch", 14f),
                this::handleSpace, '\0', KIND_REGULAR);
        addActionCell(rowLayout, actionCells, spaceCell, 2, 2.25f);

        // 3: [🔍 Tìm kiếm]
        KeyCell searchCell = new KeyCell(
                createKeyTextView("\uD83D\uDD0D T\u00ECm ki\u1EBFm", 14f),
                this::handleSearchAction, '\0', KIND_SEARCH);
        addActionCell(rowLayout, actionCells, searchCell, 3, 1.45f);

        // 4: [Ẩn ▾]
        KeyCell hideCell = new KeyCell(
                createKeyTextView("\u1EA8n \u25BE", 13.5f),
                this::hideKeyboardCleanly, '\0', KIND_REGULAR);
        addActionCell(rowLayout, actionCells, hideCell, 4, 0.95f);

        rootLayout.addView(rowLayout, rowLp);
    }

    private void addActionCell(LinearLayout rowLayout, List<KeyCell> actionCells,
                               KeyCell cell, int colIndex, float weight) {
        attachClickHandler(cell, 5, colIndex);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), weight);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        rowLayout.addView(cell.view, lp);
        actionCells.add(cell);
    }

    private TextView createKeyTextView(String label, float textSp) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        tv.setTextColor(COLOR_TEXT_PRIMARY);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setFocusable(false);
        tv.setFocusableInTouchMode(false);
        tv.setClickable(true);
        tv.setPadding(dp(6), dp(2), dp(6), dp(2));
        return tv;
    }

    private void attachClickHandler(KeyCell cell, int rowIdx, int colIdx) {
        cell.view.setOnClickListener(v -> {
            setFocusCell(rowIdx, colIdx);
            cell.action.run();
        });
    }

    private void handleLetterKey(char baseLetter) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return;
        }
        char raw = shiftOn ? Character.toUpperCase(baseLetter) : baseLetter;
        if (shiftOn) {
            shiftOn = false;
            updateKeyCaseLabels();
        }
        String nextWord = TelexEngine.applyKey(composingWord.toString(), raw, telexEnabled);
        composingWord.setLength(0);
        composingWord.append(nextWord);
        ic.setComposingText(nextWord, 1);
        refreshPreviewAndSuggestions();
    }

    private void handleLiteralCommit(String literal) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return;
        }
        ic.beginBatchEdit();
        try {
            commitComposingWord(ic);
            ic.commitText(literal, 1);
        } finally {
            ic.endBatchEdit();
        }
        refreshPreviewAndSuggestions();
    }

    private void handleSpace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return;
        }
        ic.beginBatchEdit();
        try {
            if (composingWord.length() > 0) {
                String committed = composingWord.toString() + " ";
                composingWord.setLength(0);
                ic.commitText(committed, 1);
            } else {
                ic.commitText(" ", 1);
            }
        } finally {
            ic.endBatchEdit();
        }
        refreshPreviewAndSuggestions();
    }

    private void handleBackspace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return;
        }
        if (composingWord.length() > 0) {
            String nextWord = TelexEngine.applyBackspace(composingWord.toString());
            composingWord.setLength(0);
            composingWord.append(nextWord);
            if (nextWord.isEmpty()) {
                ic.commitText("", 1);
            } else {
                ic.setComposingText(nextWord, 1);
            }
        } else {
            CharSequence selected = ic.getSelectedText(0);
            if (selected != null && selected.length() > 0) {
                ic.commitText("", 1);
            } else {
                ic.deleteSurroundingText(1, 0);
            }
        }
        refreshPreviewAndSuggestions();
    }

    private void toggleShift() {
        shiftOn = !shiftOn;
        updateKeyCaseLabels();
        applyVisualFocus();
    }

    private void toggleTelexMode() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            commitComposingWord(ic);
        } else {
            composingWord.setLength(0);
        }
        telexEnabled = !telexEnabled;
        updateModeViews();
        refreshPreviewAndSuggestions();
        applyVisualFocus();
    }

    private void handleVoiceDictate() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            commitComposingWord(ic);
        } else {
            composingWord.setLength(0);
        }
        refreshPreviewAndSuggestions();
        try {
            Intent intent = new Intent(ACTION_IME_DICTATE)
                    .setClassName(getPackageName(), "vn.viettv.launcher.VoiceSearchActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Unable to launch VoiceSearchActivity for dictation", e);
        }
    }

    private void handleSearchAction() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            commitComposingWord(ic);
        } else {
            composingWord.setLength(0);
        }
        boolean handled = sendDefaultEditorAction(true);
        if (!handled && ic != null) {
            handled = ic.performEditorAction(EditorInfo.IME_ACTION_SEARCH);
        }
        if (!handled) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        }
        requestHideSelf(0);
    }

    private void hideKeyboardCleanly() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            commitComposingWord(ic);
        } else {
            composingWord.setLength(0);
        }
        requestHideSelf(0);
    }

    private void applySuggestionPhrase(String phrase) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return;
        }
        ic.beginBatchEdit();
        try {
            composingWord.setLength(0);
            ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
            if (et != null && et.text != null) {
                ic.setSelection(0, et.text.length());
                ic.commitText(phrase, 1);
            } else {
                CharSequence before = ic.getTextBeforeCursor(200, 0);
                CharSequence after = ic.getTextAfterCursor(200, 0);
                ic.deleteSurroundingText(
                        before != null ? before.length() : 0,
                        after != null ? after.length() : 0);
                ic.commitText(phrase, 1);
            }
        } finally {
            ic.endBatchEdit();
        }
        refreshPreviewAndSuggestions();
    }

    private void commitComposingWord(InputConnection ic) {
        if (composingWord.length() > 0) {
            String word = composingWord.toString();
            composingWord.setLength(0);
            ic.commitText(word, 1);
        }
    }

    private void updateModeViews() {
        if (modeBadgeView != null) {
            if (telexEnabled) {
                modeBadgeView.setText("\uD83C\uDDFB\uD83C\uDDF3 Telex");
                modeBadgeView.setTextColor(COLOR_BADGE_VI);
            } else {
                modeBadgeView.setText("\uD83C\uDDEC\uD83C\uDDE7 EN");
                modeBadgeView.setTextColor(COLOR_TEXT_SECONDARY);
            }
        }
        if (modeToggleCell != null) {
            modeToggleCell.view.setText(telexEnabled
                    ? "\uD83C\uDDFB\uD83C\uDDF3 Telex"
                    : "\uD83C\uDDEC\uD83C\uDDE7 EN");
        }
    }

    private void updateKeyCaseLabels() {
        for (int r = 1; r <= 4 && r < rows.size(); r++) {
            for (KeyCell cell : rows.get(r)) {
                if (cell.letterChar != '\0') {
                    String label = shiftOn
                            ? String.valueOf(Character.toUpperCase(cell.letterChar))
                            : String.valueOf(cell.letterChar);
                    cell.view.setText(label);
                }
            }
        }
        if (shiftKeyCell != null) {
            shiftKeyCell.view.setText(shiftOn ? "\u2B06" : "\u21E7");
        }
    }

    private void refreshPreviewAndSuggestions() {
        if (rootLayout == null || rows.isEmpty()) {
            return;
        }
        String fullText = readCurrentFieldText();
        if (previewTextView != null) {
            if (fullText.isEmpty()) {
                previewTextView.setText("Nh\u1EADp t\u1EEB kh\u00F3a ho\u1EB7c ch\u1ECDn \uD83C\uDFA4 N\u00F3i Ti\u1EBFng Vi\u1EC7t...");
                previewTextView.setTextColor(COLOR_TEXT_SECONDARY);
            } else {
                previewTextView.setText(fullText);
                previewTextView.setTextColor(COLOR_TEXT_PRIMARY);
            }
        }

        List<String> suggestions = TelexEngine.suggestTvPhrases(fullText);
        rebuildSuggestionChips(suggestions);
    }

    private String readCurrentFieldText() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return composingWord.toString();
        }
        ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
        if (et != null && et.text != null) {
            return et.text.toString();
        }
        CharSequence before = ic.getTextBeforeCursor(200, 0);
        CharSequence after = ic.getTextAfterCursor(200, 0);
        StringBuilder sb = new StringBuilder();
        if (before != null) {
            sb.append(before);
        }
        if (sb.length() == 0 && composingWord.length() > 0) {
            sb.append(composingWord);
        }
        if (after != null) {
            sb.append(after);
        }
        return sb.toString();
    }

    private void rebuildSuggestionChips(List<String> suggestions) {
        if (suggestionRowView == null || rows.isEmpty()) {
            return;
        }
        List<KeyCell> chipCells = rows.get(0);
        chipCells.clear();
        suggestionRowView.removeAllViews();

        if (suggestions == null || suggestions.isEmpty()) {
            suggestionRowView.setVisibility(View.GONE);
            if (focusRow == 0) {
                focusRow = 2;
                focusCol = 4;
            }
            applyVisualFocus();
            return;
        }

        suggestionRowView.setVisibility(View.VISIBLE);
        int count = Math.min(5, suggestions.size());
        for (int i = 0; i < count; i++) {
            final String phrase = suggestions.get(i);
            final int colIdx = i;
            TextView chip = createKeyTextView(phrase, 12.5f);
            chip.setPadding(dp(10), dp(3), dp(10), dp(3));
            KeyCell cell = new KeyCell(chip, () -> applySuggestionPhrase(phrase), '\0', KIND_CHIP);
            attachClickHandler(cell, 0, colIdx);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            lp.leftMargin = dp(3);
            lp.rightMargin = dp(3);
            suggestionRowView.addView(chip, lp);
            chipCells.add(cell);
        }

        if (focusRow == 0 && focusCol >= chipCells.size()) {
            focusCol = chipCells.size() - 1;
        }
        applyVisualFocus();
    }

    private void moveFocusHorizontal(int delta) {
        if (focusRow < 0 || focusRow >= rows.size()) {
            setFocusCell(2, 4);
            return;
        }
        List<KeyCell> row = rows.get(focusRow);
        if (row.isEmpty()) {
            setFocusCell(2, 4);
            return;
        }
        int size = row.size();
        int nextCol = (focusCol + delta) % size;
        if (nextCol < 0) {
            nextCol += size;
        }
        setFocusCell(focusRow, nextCol);
    }

    private void moveFocusVertical(int rowDelta) {
        if (focusRow < 0 || focusRow >= rows.size()) {
            setFocusCell(2, 4);
            return;
        }
        List<KeyCell> currentRow = rows.get(focusRow);
        int currentSize = Math.max(1, currentRow.size());

        int candidateRow = focusRow + rowDelta;
        while (candidateRow >= 0 && candidateRow < rows.size()) {
            List<KeyCell> targetRow = rows.get(candidateRow);
            if (!targetRow.isEmpty()) {
                int targetSize = targetRow.size();
                int mappedCol;
                if (currentSize <= 1 || targetSize <= 1) {
                    mappedCol = 0;
                } else if (currentSize == targetSize) {
                    mappedCol = Math.min(focusCol, targetSize - 1);
                } else {
                    mappedCol = Math.round((float) focusCol * (targetSize - 1) / (float) (currentSize - 1));
                }
                mappedCol = Math.max(0, Math.min(mappedCol, targetSize - 1));
                setFocusCell(candidateRow, mappedCol);
                return;
            }
            candidateRow += rowDelta;
        }
    }

    private void setFocusCell(int row, int col) {
        if (rows.isEmpty()) {
            return;
        }
        int clampedRow = Math.max(0, Math.min(row, rows.size() - 1));
        if (rows.get(clampedRow).isEmpty()) {
            clampedRow = 2;
        }
        List<KeyCell> targetRow = rows.get(clampedRow);
        if (targetRow.isEmpty()) {
            return;
        }
        focusRow = clampedRow;
        focusCol = Math.max(0, Math.min(col, targetRow.size() - 1));
        applyVisualFocus();
    }

    private void triggerFocusedCell() {
        if (focusRow < 0 || focusRow >= rows.size()) {
            return;
        }
        List<KeyCell> row = rows.get(focusRow);
        if (focusCol < 0 || focusCol >= row.size()) {
            return;
        }
        row.get(focusCol).action.run();
    }

    private void applyVisualFocus() {
        for (int r = 0; r < rows.size(); r++) {
            List<KeyCell> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                KeyCell cell = row.get(c);
                boolean focused = (r == focusRow && c == focusCol);
                styleCell(cell, focused);
            }
        }
    }

    private void styleCell(KeyCell cell, boolean focused) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(cell.kind == KIND_CHIP ? dpF(17f) : dpF(10f));

        if (focused) {
            bg.setColor(COLOR_FOCUS_FILL);
            bg.setStroke(dp(2), COLOR_FOCUS_BORDER);
            cell.view.setTextColor(Color.WHITE);
            cell.view.setTypeface(Typeface.DEFAULT_BOLD);
            cell.view.setScaleX(1.04f);
            cell.view.setScaleY(1.04f);
            cell.view.setElevation(dpF(6f));
        } else {
            switch (cell.kind) {
                case KIND_VOICE:
                    bg.setColor(COLOR_VOICE_BG);
                    bg.setStroke(dp(1), COLOR_VOICE_STROKE);
                    break;
                case KIND_SEARCH:
                    bg.setColor(COLOR_SEARCH_BG);
                    bg.setStroke(dp(1), COLOR_SEARCH_STROKE);
                    break;
                case KIND_SHIFT:
                    if (shiftOn) {
                        bg.setColor(COLOR_SHIFT_ON_BG);
                        bg.setStroke(dp(1), COLOR_SHIFT_ON_STROKE);
                    } else {
                        bg.setColor(COLOR_KEY_BG);
                        bg.setStroke(dp(1), COLOR_KEY_STROKE);
                    }
                    break;
                case KIND_CHIP:
                    bg.setColor(COLOR_CHIP_BG);
                    bg.setStroke(dp(1), COLOR_CHIP_STROKE);
                    break;
                case KIND_MODE:
                case KIND_REGULAR:
                default:
                    bg.setColor(COLOR_KEY_BG);
                    bg.setStroke(dp(1), COLOR_KEY_STROKE);
                    break;
            }
            cell.view.setTextColor(COLOR_TEXT_PRIMARY);
            cell.view.setTypeface(cell.kind == KIND_SEARCH || cell.kind == KIND_VOICE
                    ? Typeface.DEFAULT_BOLD
                    : Typeface.DEFAULT);
            cell.view.setScaleX(1f);
            cell.view.setScaleY(1f);
            cell.view.setElevation(0f);
        }

        cell.view.setBackground(bg);
    }

    private float compactScale() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float screenHeightDp = dm.heightPixels / Math.max(1f, dm.density);
        return screenHeightDp <= 560f ? 0.84f : 1.0f;
    }

    private int dp(int value) {
        if (value == 0) {
            return 0;
        }
        float density = getResources().getDisplayMetrics().density;
        int px = Math.round(value * density * compactScale());
        return value > 0 ? Math.max(1, px) : px;
    }

    private float dpF(float value) {
        return value * getResources().getDisplayMetrics().density * compactScale();
    }
}
