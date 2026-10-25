package vn.viettv.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Calendar;

/**
 * Builds and updates the TV Dashboard (Lunar-Solar Month Calendar, Gold Prices, Fuel Prices)
 * with independent card toggles and Petrolimex Vùng 1 / Vùng 2 support.
 */
final class DashboardBinder {

    private static final int COLOR_CARD_BG = 0xE616181D;
    private static final int COLOR_CARD_BORDER = 0xFF262A31;
    private static final int COLOR_TEXT_PRIMARY = 0xFFF4F5F7;
    private static final int COLOR_TEXT_SECONDARY = 0xFFC9CDD4;
    private static final int COLOR_TEXT_MUTED = 0xFF9BA1AC;
    private static final int COLOR_TEXT_SUB = 0xFF8A909B;
    private static final int COLOR_SUNDAY_LUNAR = 0xFFFF8A7A;
    private static final int COLOR_TODAY_BG = 0xFF7CF29A;
    private static final int COLOR_TODAY_FG = 0xFF0B0C0F;
    private static final int COLOR_GOLD_ICON_BG = 0xFF2A2416;
    private static final int COLOR_GOLD_ICON_FG = 0xFFF5C451;
    private static final int COLOR_FUEL_ICON_BG = 0xFF16242E;
    private static final int COLOR_FUEL_ICON_FG = 0xFF7DC4F5;

    private static final String[] WEEKDAYS = {"T2", "T3", "T4", "T5", "T6", "T7", "CN"};

    private final Context context;
    private final float density;
    private final FrameLayout container;

    private final Typeface fontLight;
    private final Typeface fontRegular;
    private final Typeface fontMedium;
    private final Typeface fontBold;

    private final boolean showCardCalendar;
    private final boolean showCardGold;
    private final boolean showCardFuel;
    private final int fuelRegion;

    private TextView calTitleView;
    private TextView calSpanView;
    private LinearLayout calGridView;

    private TextView sjcSellView;
    private TextView ringSellView;
    private TextView goldNoteView;

    private TextView e10View;
    private TextView e5View;
    private TextView doView;

    private final LivePrices livePrices;

    private int lastDay = -1;
    private int lastMonth = -1;
    private int lastYear = -1;
    private boolean visibleOnDock = true;

    DashboardBinder(@NonNull Context context, @NonNull View rootView) {
        this(context, rootView, Config.load(context));
    }

    DashboardBinder(@NonNull Context context, @NonNull View rootView, @NonNull Config config) {
        this.context = context;
        this.density = context.getResources().getDisplayMetrics().density;
        this.container = rootView.findViewById(R.id.dashboardContainer);
        this.showCardCalendar = config.showCardCalendar;
        this.showCardGold = config.showCardGold;
        this.showCardFuel = config.showCardFuel;
        this.fuelRegion = Math.max(1, Math.min(2, config.fuelRegion));

        Typeface light = Typeface.DEFAULT;
        Typeface regular = Typeface.DEFAULT;
        Typeface medium = Typeface.DEFAULT_BOLD;
        try {
            light = context.getResources().getFont(R.font.outfit_light);
            regular = context.getResources().getFont(R.font.outfit_regular);
            medium = context.getResources().getFont(R.font.outfit_medium);
        } catch (RuntimeException e) {
            light = Typeface.DEFAULT;
        }
        this.fontLight = light;
        this.fontRegular = regular;
        this.fontMedium = medium;
        this.fontBold = Typeface.create(medium, Typeface.BOLD);

        if (this.container != null) {
            buildCards(this.container);
            if (this.showCardCalendar) {
                refreshCalendarIfNeeded(true);
            }
        }

        if (this.showCardGold || this.showCardFuel) {
            this.livePrices = new LivePrices(context.getApplicationContext(), this.fuelRegion, this::bindPrices);
            bindPrices(this.livePrices.board());
        } else {
            this.livePrices = null;
        }
    }

    private boolean hasAnyCard() {
        return showCardCalendar || showCardGold || showCardFuel;
    }

    private void buildCards(@NonNull FrameLayout host) {
        host.removeAllViews();
        calTitleView = null;
        calSpanView = null;
        calGridView = null;
        sjcSellView = null;
        ringSellView = null;
        goldNoteView = null;
        e10View = null;
        e5View = null;
        doView = null;

        if (!hasAnyCard()) {
            host.setVisibility(View.GONE);
            return;
        }
        host.setVisibility(View.VISIBLE);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setFocusable(false);
        host.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        int visibleCards = 0;
        if (showCardCalendar) {
            LinearLayout calCard = createCardContainer(1.32f, visibleCards > 0 ? dp(12) : 0);
            buildCalendarCard(calCard);
            row.addView(calCard);
            visibleCards++;
        }
        if (showCardGold) {
            LinearLayout goldCard = createCardContainer(1.0f, visibleCards > 0 ? dp(12) : 0);
            buildGoldCard(goldCard);
            row.addView(goldCard);
            visibleCards++;
        }
        if (showCardFuel) {
            LinearLayout fuelCard = createCardContainer(1.0f, visibleCards > 0 ? dp(12) : 0);
            buildFuelCard(fuelCard);
            row.addView(fuelCard);
            visibleCards++;
        }
    }

    private LinearLayout createCardContainer(float weight, int marginStartPx) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(false);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(COLOR_CARD_BG);
        bg.setCornerRadius(dpF(16f));
        bg.setStroke(Math.max(1, dp(1)), COLOR_CARD_BORDER);
        card.setBackground(bg);

        int padH = dp(16);
        int padV = dp(13);
        card.setPadding(padH, padV, padH, padV);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMarginStart(marginStartPx);
        card.setLayoutParams(lp);
        return card;
    }

    private void buildCalendarCard(@NonNull LinearLayout card) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        calTitleView = new TextView(context);
        calTitleView.setTextColor(COLOR_TEXT_PRIMARY);
        calTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        calTitleView.setTypeface(fontBold);
        calTitleView.setSingleLine(true);
        calTitleView.setIncludeFontPadding(false);
        header.addView(calTitleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        calSpanView = new TextView(context);
        calSpanView.setTextColor(COLOR_TEXT_MUTED);
        calSpanView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        calSpanView.setTypeface(fontRegular);
        calSpanView.setSingleLine(true);
        calSpanView.setIncludeFontPadding(false);
        header.addView(calSpanView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout weekRow = new LinearLayout(context);
        weekRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams weekLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        weekLp.topMargin = dp(6);
        for (int i = 0; i < WEEKDAYS.length; i++) {
            TextView wk = new TextView(context);
            wk.setText(WEEKDAYS[i]);
            wk.setGravity(Gravity.CENTER);
            wk.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f);
            wk.setTypeface(fontBold);
            wk.setIncludeFontPadding(false);
            wk.setTextColor(i == 6 ? COLOR_SUNDAY_LUNAR : COLOR_TEXT_MUTED);
            weekRow.addView(wk, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        card.addView(weekRow, weekLp);

        calGridView = new LinearLayout(context);
        calGridView.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        gridLp.topMargin = dp(4);
        card.addView(calGridView, gridLp);
    }

    private void buildGoldCard(@NonNull LinearLayout card) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView iconView = createIconBadge(COLOR_GOLD_ICON_BG, new GoldBarsDrawable(density));
        header.addView(iconView, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView title = new TextView(context);
        title.setText("Giá vàng");
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        title.setTypeface(fontBold);
        title.setSingleLine(true);
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.setMarginStart(dp(8));
        header.addView(title, titleLp);

        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(context);
        sub.setText("triệu đồng / lượng · bán ra");
        sub.setTextColor(COLOR_TEXT_MUTED);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        sub.setTypeface(fontRegular);
        sub.setSingleLine(true);
        sub.setIncludeFontPadding(false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(3);
        card.addView(sub, subLp);

        View spacer = new View(context);
        card.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        sjcSellView = addPriceRow(card, "SJC", 12.5f, 32f);
        addDivider(card, dp(6));
        ringSellView = addPriceRow(card, "Nhẫn 999.9", 12.5f, 32f);

        goldNoteView = new TextView(context);
        goldNoteView.setText("MUA VÀO – / – · ĐANG CẬP NHẬT");
        goldNoteView.setTextColor(COLOR_TEXT_MUTED);
        goldNoteView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f);
        goldNoteView.setTypeface(Typeface.MONOSPACE);
        goldNoteView.setSingleLine(true);
        goldNoteView.setEllipsize(TextUtils.TruncateAt.END);
        goldNoteView.setIncludeFontPadding(false);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = dp(6);
        card.addView(goldNoteView, noteLp);
    }

    private void buildFuelCard(@NonNull LinearLayout card) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView iconView = createIconBadge(COLOR_FUEL_ICON_BG, new FuelPumpDrawable(density));
        header.addView(iconView, new LinearLayout.LayoutParams(dp(24), dp(24)));

        TextView title = new TextView(context);
        title.setText("Xăng dầu");
        title.setTextColor(COLOR_TEXT_PRIMARY);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        title.setTypeface(fontBold);
        title.setSingleLine(true);
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.setMarginStart(dp(8));
        header.addView(title, titleLp);

        card.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sub = new TextView(context);
        sub.setText("đồng / lít · Petrolimex vùng " + fuelRegion);
        sub.setTextColor(COLOR_TEXT_MUTED);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        sub.setTypeface(fontRegular);
        sub.setSingleLine(true);
        sub.setIncludeFontPadding(false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(3);
        card.addView(sub, subLp);

        View spacer = new View(context);
        card.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        e10View = addPriceRow(card, "E10 RON95", 11.5f, 24f);
        addDivider(card, dp(5));
        e5View = addPriceRow(card, "E5 RON92", 11.5f, 24f);
        addDivider(card, dp(5));
        doView = addPriceRow(card, "Dầu DO", 11.5f, 24f);
    }

    private ImageView createIconBadge(int bgColor, @NonNull Drawable iconDrawable) {
        ImageView iv = new ImageView(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dpF(7f));
        iv.setBackground(bg);
        int pad = dp(5);
        iv.setPadding(pad, pad, pad, pad);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageDrawable(iconDrawable);
        return iv;
    }

    private TextView addPriceRow(@NonNull LinearLayout card, @NonNull String labelText,
                                 float labelSp, float valueSp) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(context);
        label.setText(labelText);
        label.setTextColor(COLOR_TEXT_SECONDARY);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSp);
        label.setTypeface(fontMedium);
        label.setSingleLine(true);
        label.setIncludeFontPadding(false);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(context);
        value.setText("–");
        value.setTextColor(COLOR_TEXT_PRIMARY);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, valueSp);
        value.setTypeface(fontLight);
        value.setSingleLine(true);
        value.setIncludeFontPadding(false);
        value.setLetterSpacing(-0.03f);
        row.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return value;
    }

    private void addDivider(@NonNull LinearLayout card, int verticalMarginPx) {
        View div = new View(context);
        div.setBackgroundColor(COLOR_CARD_BORDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lp.topMargin = verticalMarginPx;
        lp.bottomMargin = verticalMarginPx;
        card.addView(div, lp);
    }

    private void refreshCalendarIfNeeded(boolean force) {
        if (calGridView == null || calTitleView == null || calSpanView == null) return;
        Calendar cal = Calendar.getInstance();
        int day = cal.get(Calendar.DAY_OF_MONTH);
        int month = cal.get(Calendar.MONTH) + 1;
        int year = cal.get(Calendar.YEAR);
        if (!force && day == lastDay && month == lastMonth && year == lastYear) {
            return;
        }
        lastDay = day;
        lastMonth = month;
        lastYear = year;

        calTitleView.setText(LunarCalendar.monthTitle(month));
        calSpanView.setText(LunarCalendar.monthSpanLabel(month, year));

        int offset = LunarCalendar.mondayBasedOffset(month, year);
        int daysInMonth = LunarCalendar.daysInMonth(month, year);
        int totalCells = offset + daysInMonth;
        int numRows = totalCells > 35 ? 6 : 5;

        calGridView.removeAllViews();
        for (int r = 0; r < numRows; r++) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            for (int c = 0; c < 7; c++) {
                int idx = r * 7 + c;
                int dd = idx - offset + 1;
                LinearLayout cell = new LinearLayout(context);
                cell.setOrientation(LinearLayout.HORIZONTAL);
                cell.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                cellLp.setMargins(dp(1), dp(1), dp(1), dp(1));

                if (dd >= 1 && dd <= daysInMonth) {
                    boolean isToday = (dd == day);
                    boolean isSunday = (c == 6);
                    LunarCalendar.LunarDate ld = LunarCalendar.of(dd, month, year);
                    boolean isSpecialLunar = (ld.day == 1 || ld.day == 15);

                    if (isToday) {
                        GradientDrawable pill = new GradientDrawable();
                        pill.setColor(COLOR_TODAY_BG);
                        pill.setCornerRadius(dpF(6f));
                        cell.setBackground(pill);
                    }

                    TextView solarTv = new TextView(context);
                    solarTv.setText(String.valueOf(dd));
                    solarTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
                    solarTv.setTypeface(fontBold);
                    solarTv.setSingleLine(true);
                    solarTv.setIncludeFontPadding(false);
                    solarTv.setTextColor(isToday ? COLOR_TODAY_FG
                            : (isSunday ? COLOR_SUNDAY_LUNAR : COLOR_TEXT_PRIMARY));
                    cell.addView(solarTv, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                    TextView lunarTv = new TextView(context);
                    String lunarText = (ld.day == 1) ? ("1/" + ld.month) : String.valueOf(ld.day);
                    lunarTv.setText(lunarText);
                    lunarTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 7.5f);
                    lunarTv.setTypeface((isSpecialLunar || isToday) ? fontBold : fontRegular);
                    lunarTv.setSingleLine(true);
                    lunarTv.setIncludeFontPadding(false);
                    lunarTv.setTextColor(isToday ? COLOR_TODAY_FG
                            : (isSpecialLunar ? COLOR_SUNDAY_LUNAR : COLOR_TEXT_SUB));
                    LinearLayout.LayoutParams lunarLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lunarLp.setMarginStart(dp(2));
                    lunarLp.topMargin = dp(1);
                    cell.addView(lunarTv, lunarLp);
                }

                row.addView(cell, cellLp);
            }
            calGridView.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
    }

    private void bindPrices(@Nullable LivePrices.PriceBoard board) {
        if (board == null) return;

        if (sjcSellView != null && ringSellView != null && goldNoteView != null) {
            long sjcSell = (board.sjc != null && board.sjc.isPlausible()) ? board.sjc.sell : 0L;
            long ringSell = (board.ring != null && board.ring.isPlausible()) ? board.ring.sell : 0L;
            sjcSellView.setText(LivePrices.formatGoldMillions(sjcSell));
            ringSellView.setText(LivePrices.formatGoldMillions(ringSell));
            goldNoteView.setText(LivePrices.formatUpdatedNote(board, System.currentTimeMillis()));
        }

        if (e10View != null && e5View != null && doView != null) {
            int e10 = (board.e10 != null && board.e10.isPlausible()) ? board.e10.perLitre : 0;
            int e5 = (board.e5 != null && board.e5.isPlausible()) ? board.e5.perLitre : 0;
            int diesel = (board.diesel != null && board.diesel.isPlausible()) ? board.diesel.perLitre : 0;
            e10View.setText(LivePrices.formatFuelLitre(e10));
            e5View.setText(LivePrices.formatFuelLitre(e5));
            doView.setText(LivePrices.formatFuelLitre(diesel));
        }
    }

    void onResume() {
        if (!hasAnyCard()) return;
        refreshCalendarIfNeeded(false);
        if (livePrices != null) {
            bindPrices(livePrices.board());
            livePrices.start();
        }
    }

    void onPause() {
        if (livePrices != null) {
            livePrices.stop();
        }
    }

    void onMinuteTick() {
        if (!hasAnyCard()) return;
        refreshCalendarIfNeeded(false);
        if (livePrices != null) {
            bindPrices(livePrices.board());
            livePrices.refreshIfNeeded();
        }
    }

    void onNetworkAvailable() {
        if (livePrices != null) {
            livePrices.backOnline();
        }
    }

    void onTileFocused(boolean onDock) {
        if (!hasAnyCard()) return;
        if (visibleOnDock == onDock) return;
        visibleOnDock = onDock;
        float target = onDock ? 1f : 0f;
        if (container != null) {
            container.animate().cancel();
            container.animate().alpha(target).setDuration(160).start();
        }
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private float dpF(float v) {
        return v * density;
    }

    /**
     * Draws the 3 stacked gold ingots icon from xyz-tvhome:
     * <path d="M3 18h8l-1.5-5h-5z"/><path d="M13 18h8l-1.5-5h-5z"/><path d="M8 11h8l-1.5-5h-5z"/>
     */
    private static final class GoldBarsDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        GoldBarsDrawable(float density) {
            paint.setColor(COLOR_GOLD_ICON_FG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, 1.5f * density));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            int w = b.width();
            int h = b.height();
            if (w <= 0 || h <= 0) return;
            float sx = w / 24f;
            float sy = h / 24f;

            path.reset();
            addTrapezoid(path, 3f, 18f, 8f, 1.5f, 5f, sx, sy, b.left, b.top);
            addTrapezoid(path, 13f, 18f, 8f, 1.5f, 5f, sx, sy, b.left, b.top);
            addTrapezoid(path, 8f, 11f, 8f, 1.5f, 5f, sx, sy, b.left, b.top);

            canvas.drawPath(path, paint);
        }

        private static void addTrapezoid(Path p, float x, float y, float baseW, float inset, float barH,
                                         float sx, float sy, int ox, int oy) {
            p.moveTo(ox + x * sx, oy + y * sy);
            p.lineTo(ox + (x + baseW) * sx, oy + y * sy);
            p.lineTo(ox + (x + baseW - inset) * sx, oy + (y - barH) * sy);
            p.lineTo(ox + (x + inset) * sx, oy + (y - barH) * sy);
            p.close();
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * Draws the fuel pump icon from xyz-tvhome on a 24x24 viewport.
     */
    private static final class FuelPumpDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF arcRect = new RectF();

        FuelPumpDrawable(float density) {
            paint.setColor(COLOR_FUEL_ICON_FG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, 1.5f * density));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            int w = b.width();
            int h = b.height();
            if (w <= 0 || h <= 0) return;
            float sx = w / 24f;
            float sy = h / 24f;
            int ox = b.left;
            int oy = b.top;

            path.reset();
            path.moveTo(ox + 3f * sx, oy + 22f * sy);
            path.lineTo(ox + 15f * sx, oy + 22f * sy);
            path.moveTo(ox + 4f * sx, oy + 9f * sy);
            path.lineTo(ox + 14f * sx, oy + 9f * sy);
            arcRect.set(ox + 4f * sx, oy + 2.5f * sy, ox + 14f * sx, oy + 22f * sy);
            path.addRoundRect(arcRect, 2f * sx, 2f * sy, Path.Direction.CW);
            path.moveTo(ox + 14f * sx, oy + 13f * sy);
            path.lineTo(ox + 17f * sx, oy + 13f * sy);
            path.lineTo(ox + 17f * sx, oy + 18.5f * sy);
            path.lineTo(ox + 20.5f * sx, oy + 18.5f * sy);
            path.lineTo(ox + 20.5f * sx, oy + 9.5f * sy);
            path.lineTo(ox + 18f * sx, oy + 5.5f * sy);

            canvas.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
