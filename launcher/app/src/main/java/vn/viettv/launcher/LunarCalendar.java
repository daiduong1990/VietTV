package vn.viettv.launcher;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Vietnamese Lunar/Solar calendar (UTC+7) ported from Ho Ngoc Duc's algorithm in xyz-tvhome/themes/core.js.
 */
public final class LunarCalendar {

    private static final double TZ = 7.0;

    private static final String[] CAN = {
            "Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý"
    };

    private static final String[] CHI = {
            "Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi"
    };

    public static final class LunarDate {
        public final int day;
        public final int month;
        public final int year;
        public final boolean leap;
        public final int jd;

        public LunarDate(int day, int month, int year, boolean leap, int jd) {
            this.day = day;
            this.month = month;
            this.year = year;
            this.leap = leap;
            this.jd = jd;
        }

        public String monthLabel() {
            return month + (leap ? " nhuận" : "");
        }
    }

    private LunarCalendar() {
    }

    private static int floorInt(double d) {
        return (int) Math.floor(d);
    }

    public static int jdFromDate(int dd, int mm, int yy) {
        int a = floorInt((14.0 - mm) / 12.0);
        int y = yy + 4800 - a;
        int m = mm + 12 * a - 3;
        int jd = dd + floorInt((153.0 * m + 2.0) / 5.0) + 365 * y
                + floorInt(y / 4.0) - floorInt(y / 100.0) + floorInt(y / 400.0) - 32045;
        if (jd < 2299161) {
            jd = dd + floorInt((153.0 * m + 2.0) / 5.0) + 365 * y + floorInt(y / 4.0) - 32083;
        }
        return jd;
    }

    private static double newMoon(int k) {
        double T = k / 1236.85;
        double T2 = T * T;
        double T3 = T2 * T;
        double dr = Math.PI / 180.0;
        double Jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * T2 - 0.000000155 * T3;
        Jd1 += 0.00033 * Math.sin((166.56 + 132.87 * T - 0.009173 * T2) * dr);
        double M = 359.2242 + 29.10535608 * k - 0.0000333 * T2 - 0.00000347 * T3;
        double Mpr = 306.0253 + 385.81691806 * k + 0.0107306 * T2 + 0.00001236 * T3;
        double F = 21.2964 + 390.67050646 * k - 0.0016528 * T2 - 0.00000239 * T3;
        double C1 = (0.1734 - 0.000393 * T) * Math.sin(M * dr) + 0.0021 * Math.sin(2.0 * dr * M);
        C1 = C1 - 0.4068 * Math.sin(Mpr * dr) + 0.0161 * Math.sin(dr * 2.0 * Mpr) - 0.0004 * Math.sin(dr * 3.0 * Mpr);
        C1 = C1 + 0.0104 * Math.sin(dr * 2.0 * F) - 0.0051 * Math.sin(dr * (M + Mpr));
        C1 = C1 - 0.0074 * Math.sin(dr * (M - Mpr)) + 0.0004 * Math.sin(dr * (2.0 * F + M));
        C1 = C1 - 0.0004 * Math.sin(dr * (2.0 * F - M)) - 0.0006 * Math.sin(dr * (2.0 * F + Mpr));
        C1 = C1 + 0.0010 * Math.sin(dr * (2.0 * F - Mpr)) + 0.0005 * Math.sin(dr * (2.0 * Mpr + M));
        double deltat = T < -11.0
                ? 0.001 + 0.000839 * T + 0.0002261 * T2 - 0.00000845 * T3 - 0.000000081 * T * T3
                : -0.000278 + 0.000265 * T + 0.000262 * T2;
        return Jd1 + C1 - deltat;
    }

    private static int newMoonDay(int k) {
        return floorInt(newMoon(k) + 0.5 + TZ / 24.0);
    }

    private static int sunLong(int jdn) {
        double T = (jdn - 2451545.5 - TZ / 24.0) / 36525.0;
        double T2 = T * T;
        double dr = Math.PI / 180.0;
        double M = 357.52910 + 35999.05030 * T - 0.0001559 * T2 - 0.00000048 * T * T2;
        double L0 = 280.46645 + 36000.76983 * T + 0.0003032 * T2;
        double DL = (1.914600 - 0.004817 * T - 0.000014 * T2) * Math.sin(dr * M);
        DL += (0.019993 - 0.000101 * T) * Math.sin(dr * 2.0 * M) + 0.000290 * Math.sin(dr * 3.0 * M);
        double L = (L0 + DL) * dr;
        L = L - Math.PI * 2.0 * floorInt(L / (Math.PI * 2.0));
        return floorInt(L / Math.PI * 6.0);
    }

    private static int month11(int yy) {
        int k = floorInt((jdFromDate(31, 12, yy) - 2415021.0) / 29.530588853);
        int nm = newMoonDay(k);
        return sunLong(nm) >= 9 ? newMoonDay(k - 1) : nm;
    }

    private static int leapOffset(int a11) {
        int k = floorInt((a11 - 2415021.076998695) / 29.530588853 + 0.5);
        int last;
        int i = 1;
        int arc = sunLong(newMoonDay(k + i));
        do {
            last = arc;
            i++;
            arc = sunLong(newMoonDay(k + i));
        } while (arc != last && i < 14);
        return i - 1;
    }

    public static LunarDate of(int dd, int mm, int yy) {
        int dn = jdFromDate(dd, mm, yy);
        int k = floorInt((dn - 2415021.076998695) / 29.530588853);
        int start = newMoonDay(k + 1);
        if (start > dn) {
            start = newMoonDay(k);
        }
        int a11 = month11(yy);
        int b11 = a11;
        int ly;
        if (a11 >= start) {
            ly = yy;
            a11 = month11(yy - 1);
        } else {
            ly = yy + 1;
            b11 = month11(yy + 1);
        }
        int day = dn - start + 1;
        int diff = floorInt((start - a11) / 29.0);
        boolean leap = false;
        int mon = diff + 11;
        if (b11 - a11 > 365) {
            int ld = leapOffset(a11);
            if (diff >= ld) {
                mon = diff + 10;
                leap = (diff == ld);
            }
        }
        if (mon > 12) {
            mon -= 12;
        }
        if (mon >= 11 && diff < 4) {
            ly -= 1;
        }
        return new LunarDate(day, mon, ly, leap, dn);
    }

    public static int daysInMonth(int mm, int yy) {
        return YearMonth.of(yy, mm).lengthOfMonth();
    }

    public static int mondayBasedOffset(int mm, int yy) {
        return LocalDate.of(yy, mm, 1).getDayOfWeek().getValue() - 1;
    }

    public static String monthTitle(int mm) {
        return "Tháng " + mm;
    }

    public static String monthSpanLabel(int mm, int yy) {
        LunarDate a = of(1, mm, yy);
        LunarDate b = of(daysInMonth(mm, yy), mm, yy);
        String second = (a.month == b.month && a.leap == b.leap) ? "" : " và " + b.monthLabel();
        return "âm lịch tháng " + a.monthLabel() + second;
    }

    public static String canChiYear(int lunarYear) {
        return CAN[Math.floorMod(lunarYear + 6, 10)] + " " + CHI[Math.floorMod(lunarYear + 8, 12)];
    }
}
