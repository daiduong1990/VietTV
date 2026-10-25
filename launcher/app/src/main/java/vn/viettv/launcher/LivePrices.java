package vn.viettv.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live gold (PNJ / SJC) and fuel (Petrolimex Vùng 1 & Vùng 2) prices engine ported from xyz-tvhome
 * (PriceBoard.kt & LivePrices.kt) with real HTTP fetching and SharedPreferences persistence.
 */
public final class LivePrices {

    private static final String TAG = "TvHome";

    public static final String PNJ_API_URL =
            "https://edge-api.pnj.io/ecom-frontend/v1/get-gold-price?zone=11";
    public static final String WEBGIA_GOLD_URL =
            "https://webgia.com/gia-vang/pnj/";
    public static final String WEBGIA_FUEL_URL =
            "https://webgia.com/gia-xang-dau/petrolimex/";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    private static final long CLOCK_SET_MS = 1_767_225_600_000L; // 1 Jan 2026
    private static final long MINUTE_MS = 60_000L;
    private static final long HOUR_MS = 60L * MINUTE_MS;

    private static final long DEFAULT_SJC_BUY = 138_700_000L;
    private static final long DEFAULT_SJC_SELL = 141_700_000L;
    private static final long DEFAULT_RING_BUY = 138_000_000L;
    private static final long DEFAULT_RING_SELL = 141_000_000L;
    private static final int DEFAULT_E10 = 28250;
    private static final int DEFAULT_E5 = 27700;
    private static final int DEFAULT_DO = 29120;
    public static final int DEFAULT_VUNG2_DELTA = 560;

    private static final Pattern JSON_OBJ_PATTERN = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern MASP_PATTERN = Pattern.compile("\"masp\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern GIAMUA_PATTERN = Pattern.compile("\"giamua\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern GIABAN_PATTERN = Pattern.compile("\"giaban\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern TR_PATTERN = Pattern.compile("<tr[^>]*>([\\s\\S]*?)</tr>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TD_PRICE_PATTERN = Pattern.compile("<td[^>]*>\\s*([0-9]{1,3}(?:\\.[0-9]{3})+)\\s*</td>", Pattern.CASE_INSENSITIVE);

    public interface Listener {
        void onPricesUpdated(PriceBoard board);
    }

    public static final class GoldPrice {
        public final long buy;
        public final long sell;
        public final long readAt;

        public GoldPrice(long buy, long sell, long readAt) {
            this.buy = buy;
            this.sell = sell;
            this.readAt = readAt;
        }

        public boolean isPlausible() {
            return buy >= 10_000_000L && buy <= 2_000_000_000L
                    && sell >= 10_000_000L && sell <= 2_000_000_000L
                    && buy <= sell
                    && (sell - buy) <= sell / 5L;
        }
    }

    public static final class FuelPrice {
        public final int perLitre;
        public final long readAt;

        public FuelPrice(int perLitre, long readAt) {
            this.perLitre = perLitre;
            this.readAt = readAt;
        }

        public boolean isPlausible() {
            return perLitre >= 10_000 && perLitre <= 80_000;
        }
    }

    public static final class PriceBoard {
        public final GoldPrice sjc;
        public final GoldPrice ring;
        public final FuelPrice e10;
        public final FuelPrice e5;
        public final FuelPrice diesel;

        public PriceBoard(GoldPrice sjc, GoldPrice ring, FuelPrice e10, FuelPrice e5, FuelPrice diesel) {
            this.sjc = sjc;
            this.ring = ring;
            this.e10 = e10;
            this.e5 = e5;
            this.diesel = diesel;
        }
    }

    private final SharedPreferences prefs;
    private final SharedPreferences settingsPrefs;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::fetch;
    private volatile int fuelRegionOverride;
    private boolean fetching = false;
    private boolean shown = false;

    public LivePrices(Context context, Listener listener) {
        this(context, 0, listener);
    }

    public LivePrices(Context context, int fuelRegion, Listener listener) {
        this.prefs = context.getSharedPreferences("prices", Context.MODE_PRIVATE);
        this.settingsPrefs = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        this.fuelRegionOverride = (fuelRegion == 1 || fuelRegion == 2) ? fuelRegion : 0;
        this.listener = listener;
    }

    public void setFuelRegion(int fuelRegion) {
        this.fuelRegionOverride = (fuelRegion == 1 || fuelRegion == 2) ? fuelRegion : 0;
        if (listener != null) {
            listener.onPricesUpdated(board());
        }
        if (shown && untilDue() == 0L) {
            handler.removeCallbacks(tick);
            fetch();
        }
    }

    private int resolveFuelRegion() {
        int fallback = (fuelRegionOverride == 2) ? 2 : 1;
        if (settingsPrefs != null && settingsPrefs.contains(Config.FUEL_REGION_KEY)) {
            int saved = settingsPrefs.getInt(Config.FUEL_REGION_KEY, fallback);
            return Math.max(1, Math.min(2, saved));
        }
        return fallback;
    }

    public static String formatGoldMillions(long v) {
        if (v <= 0L) {
            return "–";
        }
        return String.format(Locale.US, "%.1f", v / 1_000_000.0).replace('.', ',');
    }

    public static String formatFuelLitre(int v) {
        if (v <= 0) {
            return "–";
        }
        String raw = String.valueOf(v);
        int len = raw.length();
        StringBuilder sb = new StringBuilder(len + 4);
        for (int i = 0; i < len; i++) {
            if (i > 0 && (len - i) % 3 == 0) {
                sb.append('.');
            }
            sb.append(raw.charAt(i));
        }
        return sb.toString();
    }

    public static String formatUpdatedNote(PriceBoard board, long nowMs) {
        long sjcBuyVal = (board != null && board.sjc != null && board.sjc.isPlausible()) ? board.sjc.buy : 0L;
        long ringBuyVal = (board != null && board.ring != null && board.ring.isPlausible()) ? board.ring.buy : 0L;
        String sjcBuy = formatGoldMillions(sjcBuyVal);
        String ringBuy = formatGoldMillions(ringBuyVal);
        long readAt = Math.max(
                board != null && board.sjc != null ? board.sjc.readAt : 0L,
                board != null && board.ring != null ? board.ring.readAt : 0L
        );
        String timePart = "";
        if (readAt > 0L) {
            ZoneId zone = ZoneId.systemDefault();
            ZonedDateTime readZdt = Instant.ofEpochMilli(readAt).atZone(zone);
            ZonedDateTime nowZdt = Instant.ofEpochMilli(nowMs > 0L ? nowMs : System.currentTimeMillis()).atZone(zone);
            String hm = String.format(Locale.US, "%02d:%02d", readZdt.getHour(), readZdt.getMinute());
            if (readZdt.toLocalDate().equals(nowZdt.toLocalDate())) {
                timePart = hm;
            } else {
                timePart = String.format(
                        Locale.US,
                        "%02d/%02d %s",
                        readZdt.getDayOfMonth(),
                        readZdt.getMonthValue(),
                        hm
                );
            }
        }
        return "MUA VÀO " + sjcBuy + " / " + ringBuy + (timePart.isEmpty() ? "" : " · CẬP NHẬT " + timePart);
    }

    private static long normalizeGoldToVndPerLuong(long raw) {
        if (raw <= 0L) {
            return 0L;
        }
        if (raw < 1_000_000L) {
            return raw * 10_000L;
        }
        if (raw < 50_000_000L) {
            return raw * 10L;
        }
        return raw;
    }

    private static long parseLongSafe(String raw) {
        if (raw == null || raw.isEmpty()) {
            return 0L;
        }
        try {
            String clean = raw.replace(".", "").trim();
            return Math.round(Double.parseDouble(clean));
        } catch (NumberFormatException e) {
            String err = String.valueOf(e);
            return err.isEmpty() ? 0L : 0L;
        }
    }

    public static GoldPrice[] parsePnjJson(String body, long nowMs) {
        GoldPrice sjc = null;
        GoldPrice ring = null;
        GoldPrice fallbackRing = null;
        if (body == null || body.isEmpty()) {
            return new GoldPrice[]{null, null};
        }
        Matcher objMatcher = JSON_OBJ_PATTERN.matcher(body);
        while (objMatcher.find()) {
            String obj = objMatcher.group();
            Matcher mCode = MASP_PATTERN.matcher(obj);
            Matcher mBuy = GIAMUA_PATTERN.matcher(obj);
            Matcher mSell = GIABAN_PATTERN.matcher(obj);
            if (!mCode.find() || !mBuy.find() || !mSell.find()) {
                continue;
            }
            String code = mCode.group(1).trim();
            long buy = normalizeGoldToVndPerLuong(parseLongSafe(mBuy.group(1)));
            long sell = normalizeGoldToVndPerLuong(parseLongSafe(mSell.group(1)));
            GoldPrice gp = new GoldPrice(buy, sell, nowMs);
            if (!gp.isPlausible()) {
                continue;
            }
            if ("SJC".equalsIgnoreCase(code) && sjc == null) {
                sjc = gp;
            } else if ("N24K".equalsIgnoreCase(code) && ring == null) {
                ring = gp;
            } else if (("PNJ".equalsIgnoreCase(code) || "KB".equalsIgnoreCase(code)) && fallbackRing == null) {
                fallbackRing = gp;
            }
        }
        return new GoldPrice[]{sjc, ring != null ? ring : fallbackRing};
    }

    public static GoldPrice[] parseWebgiaGoldHtml(String html, long nowMs) {
        GoldPrice sjc = null;
        GoldPrice ring = null;
        if (html == null || html.isEmpty()) {
            return new GoldPrice[]{null, null};
        }
        Matcher trMatcher = TR_PATTERN.matcher(html);
        while (trMatcher.find()) {
            String row = trMatcher.group(1);
            boolean isSjc = row.contains(">SJC<");
            boolean isPnj = row.contains(">PNJ<") || row.contains("Nhẫn Trơn PNJ");
            if (!isSjc && !isPnj) {
                continue;
            }
            Matcher tdMatcher = TD_PRICE_PATTERN.matcher(row);
            if (!tdMatcher.find()) {
                continue;
            }
            long buy = normalizeGoldToVndPerLuong(parseLongSafe(tdMatcher.group(1)));
            if (!tdMatcher.find()) {
                continue;
            }
            long sell = normalizeGoldToVndPerLuong(parseLongSafe(tdMatcher.group(1)));
            GoldPrice gp = new GoldPrice(buy, sell, nowMs);
            if (!gp.isPlausible()) {
                continue;
            }
            if (isSjc && sjc == null) {
                sjc = gp;
            } else if (isPnj && ring == null) {
                ring = gp;
            }
            if (sjc != null && ring != null) {
                break;
            }
        }
        return new GoldPrice[]{sjc, ring};
    }

    public static int[] parseWebgiaPetrolimexHtml(String html) {
        return parseWebgiaPetrolimexHtml(html, 1);
    }

    public static int[] parseWebgiaPetrolimexHtml(String html, int region) {
        int e10 = 0;
        int e10Fallback = 0;
        int e5 = 0;
        int diesel = 0;
        if (html == null || html.isEmpty()) {
            return new int[]{0, 0, 0};
        }
        boolean wantRegion2 = (region == 2);
        Matcher trMatcher = TR_PATTERN.matcher(html);
        while (trMatcher.find()) {
            String row = trMatcher.group(1);
            Matcher tdMatcher = TD_PRICE_PATTERN.matcher(row);
            int firstPrice = 0;
            int secondPrice = 0;
            while (tdMatcher.find()) {
                int candidate = (int) parseLongSafe(tdMatcher.group(1));
                if (candidate >= 10_000 && candidate <= 80_000) {
                    if (firstPrice == 0) {
                        firstPrice = candidate;
                    } else if (secondPrice == 0) {
                        secondPrice = candidate;
                        break;
                    }
                }
            }
            if (firstPrice == 0) {
                continue;
            }
            int price = (wantRegion2 && secondPrice > 0) ? secondPrice : firstPrice;
            if (row.contains("E10 RON 95 mức 3") && e10 == 0) {
                e10 = price;
            } else if ((row.contains("E10 RON 95") || row.contains("RON 95-III")) && e10Fallback == 0) {
                e10Fallback = price;
            } else if (row.contains("E5 RON 92") && e5 == 0) {
                e5 = price;
            } else if ((row.contains("Dầu DO 0,05S") || row.contains("DO 0,05S")) && diesel == 0) {
                diesel = price;
            }
        }
        return new int[]{e10 != 0 ? e10 : e10Fallback, e5, diesel};
    }

    public PriceBoard board() {
        ensureSeeded();
        int region = resolveFuelRegion();
        return new PriceBoard(
                readGold("sjc"),
                readGold("ring"),
                readFuel("e10", region),
                readFuel("e5", region),
                readFuel("do", region)
        );
    }

    private void ensureSeeded() {
        long now = System.currentTimeMillis();
        boolean missingBase = prefs.getLong("sjc_time", 0L) == 0L;
        boolean missingRegion2 = prefs.getLong("e10_r2_time", 0L) == 0L;
        if (!missingBase && !missingRegion2) {
            return;
        }
        SharedPreferences.Editor ed = prefs.edit();
        if (missingBase) {
            ed.putLong("sjc_buy", DEFAULT_SJC_BUY)
                    .putLong("sjc_sell", DEFAULT_SJC_SELL)
                    .putLong("sjc_time", now)
                    .putLong("ring_buy", DEFAULT_RING_BUY)
                    .putLong("ring_sell", DEFAULT_RING_SELL)
                    .putLong("ring_time", now)
                    .putInt("e10", DEFAULT_E10)
                    .putLong("e10_time", now)
                    .putInt("e5", DEFAULT_E5)
                    .putLong("e5_time", now)
                    .putInt("do", DEFAULT_DO)
                    .putLong("do_time", now);
        }
        if (missingRegion2) {
            int baseE10 = missingBase ? DEFAULT_E10 : prefs.getInt("e10", DEFAULT_E10);
            int baseE5 = missingBase ? DEFAULT_E5 : prefs.getInt("e5", DEFAULT_E5);
            int baseDo = missingBase ? DEFAULT_DO : prefs.getInt("do", DEFAULT_DO);
            ed.putInt("e10_r2", baseE10 + DEFAULT_VUNG2_DELTA)
                    .putLong("e10_r2_time", now)
                    .putInt("e5_r2", baseE5 + DEFAULT_VUNG2_DELTA)
                    .putLong("e5_r2_time", now)
                    .putInt("do_r2", baseDo + DEFAULT_VUNG2_DELTA)
                    .putLong("do_r2_time", now);
        }
        ed.apply();
    }

    private GoldPrice readGold(String key) {
        long t = prefs.getLong(key + "_time", 0L);
        if (t == 0L) {
            return null;
        }
        return new GoldPrice(prefs.getLong(key + "_buy", 0L), prefs.getLong(key + "_sell", 0L), t);
    }

    private FuelPrice readFuel(String key, int region) {
        if (region == 2) {
            long t2 = prefs.getLong(key + "_r2_time", 0L);
            int v2 = prefs.getInt(key + "_r2", 0);
            if (t2 != 0L && v2 > 0) {
                return new FuelPrice(v2, t2);
            }
            long t1 = prefs.getLong(key + "_time", 0L);
            int v1 = prefs.getInt(key, 0);
            if (t1 != 0L && v1 > 0) {
                return new FuelPrice(v1 + DEFAULT_VUNG2_DELTA, t1);
            }
            return null;
        }
        long t = prefs.getLong(key + "_time", 0L);
        if (t == 0L) {
            return null;
        }
        return new FuelPrice(prefs.getInt(key, 0), t);
    }

    public void start() {
        shown = true;
        if (listener != null) {
            listener.onPricesUpdated(board());
        }
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, untilDue());
    }

    public void stop() {
        handler.removeCallbacks(tick);
        shown = false;
    }

    public void backOnline() {
        if (shown && (prefs.getInt("failures", 0) > 0 || untilDue() == 0L)) {
            handler.removeCallbacks(tick);
            fetch();
        }
    }

    public void refreshIfNeeded() {
        if (untilDue() == 0L) {
            handler.removeCallbacks(tick);
            fetch();
        } else if (listener != null) {
            listener.onPricesUpdated(board());
        }
    }

    private long untilDue() {
        long checked = prefs.getLong("checked", 0L);
        if (checked == 0L) {
            return 0L;
        }
        if (resolveFuelRegion() == 2 && !prefs.getBoolean("r2_live_fetched", false)) {
            return 0L;
        }
        long since = System.currentTimeMillis() - checked;
        long wait = retryDelay(prefs.getInt("failures", 0));
        return (since >= wait || since < 0L) ? 0L : (wait - since);
    }

    private static long retryDelay(int failures) {
        switch (failures) {
            case 0:
                return HOUR_MS;
            case 1:
                return 5L * MINUTE_MS;
            case 2:
                return 15L * MINUTE_MS;
            case 3:
                return 30L * MINUTE_MS;
            default:
                return HOUR_MS;
        }
    }

    private void fetch() {
        if (fetching) {
            return;
        }
        if (System.currentTimeMillis() < CLOCK_SET_MS) {
            Log.w(TAG, "Prices: system clock is not set yet");
            return;
        }
        fetching = true;
        Thread worker = new Thread(() -> {
            long now = System.currentTimeMillis();
            SharedPreferences.Editor editor = prefs.edit();
            int count = fetchAllInto(now, editor);
            int failures = (count == 5) ? 0 : (prefs.getInt("failures", 0) + 1);
            if (failures > 0) {
                Log.w(TAG, "Prices: updated " + count + " of 5 products, failure #" + failures);
            }
            editor.putLong("checked", now).putInt("failures", failures).apply();
            handler.post(() -> {
                fetching = false;
                if (listener != null) {
                    listener.onPricesUpdated(board());
                }
                if (shown) {
                    handler.removeCallbacks(tick);
                    handler.postDelayed(tick, retryDelay(failures));
                }
            });
        }, "tvhome-prices");
        worker.setDaemon(true);
        worker.start();
    }

    private int fetchAllInto(long now, SharedPreferences.Editor editor) {
        int count = 0;
        GoldPrice[] gold = new GoldPrice[]{null, null};
        try {
            gold = parsePnjJson(httpGet(PNJ_API_URL), now);
        } catch (Exception e) {
            Log.w(TAG, "PNJ API fetch failed: " + e);
        }
        if (gold[0] == null || gold[1] == null) {
            try {
                GoldPrice[] fallback = parseWebgiaGoldHtml(httpGet(WEBGIA_GOLD_URL), now);
                if (gold[0] == null) {
                    gold[0] = fallback[0];
                }
                if (gold[1] == null) {
                    gold[1] = fallback[1];
                }
            } catch (Exception e) {
                Log.w(TAG, "Webgia gold fallback failed: " + e);
            }
        }
        if (gold[0] != null && gold[0].isPlausible()) {
            editor.putLong("sjc_buy", gold[0].buy)
                    .putLong("sjc_sell", gold[0].sell)
                    .putLong("sjc_time", now);
            count++;
        }
        if (gold[1] != null && gold[1].isPlausible()) {
            editor.putLong("ring_buy", gold[1].buy)
                    .putLong("ring_sell", gold[1].sell)
                    .putLong("ring_time", now);
            count++;
        }

        try {
            String fuelHtml = httpGet(WEBGIA_FUEL_URL);
            int[] fuelsV1 = parseWebgiaPetrolimexHtml(fuelHtml, 1);
            int[] fuelsV2 = parseWebgiaPetrolimexHtml(fuelHtml, 2);
            String[] keys = new String[]{"e10", "e5", "do"};
            int r2Saved = 0;
            for (int i = 0; i < keys.length; i++) {
                FuelPrice fp1 = new FuelPrice(fuelsV1[i], now);
                if (fp1.isPlausible()) {
                    editor.putInt(keys[i], fp1.perLitre).putLong(keys[i] + "_time", now);
                    count++;
                }
                FuelPrice fp2 = new FuelPrice(fuelsV2[i], now);
                if (fp2.isPlausible()) {
                    editor.putInt(keys[i] + "_r2", fp2.perLitre).putLong(keys[i] + "_r2_time", now);
                    r2Saved++;
                }
            }
            if (r2Saved == keys.length) {
                editor.putBoolean("r2_live_fetched", true);
            }
        } catch (Exception e) {
            Log.w(TAG, "Webgia Petrolimex fetch failed: " + e);
        }
        return count;
    }

    public static String httpGet(String urlStr) throws Exception {
        if (urlStr == null || !urlStr.startsWith("https://")) {
            throw new IOException("HTTPS required");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }
}
