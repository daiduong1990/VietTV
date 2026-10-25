package vn.viettv.launcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure Java 17 Vietnamese Telex input engine and TV search phrase suggester.
 * Zero Android SDK dependencies so it can be unit-tested standalone on JVM and used inside Android IME.
 */
public final class TelexEngine {

    private static final int BASE_A = 0;       // a
    private static final int BASE_A_BREVE = 1; // ă
    private static final int BASE_A_CIRC = 2;  // â
    private static final int BASE_E = 3;       // e
    private static final int BASE_E_CIRC = 4;  // ê
    private static final int BASE_I = 5;       // i
    private static final int BASE_O = 6;       // o
    private static final int BASE_O_CIRC = 7;  // ô
    private static final int BASE_O_HORN = 8;  // ơ
    private static final int BASE_U = 9;       // u
    private static final int BASE_U_HORN = 10; // ư
    private static final int BASE_Y = 11;      // y

    private static final char[][] LOWER_VOWELS = {
            // 0: ngang, 1: sắc (s), 2: huyền (f), 3: hỏi (r), 4: ngã (x), 5: nặng (j)
            {'a', '\u00E1', '\u00E0', '\u1EA3', '\u00E3', '\u1EA1'}, // a, á, à, ả, ã, ạ
            {'\u0103', '\u1EAF', '\u1EB1', '\u1EB3', '\u1EB5', '\u1EB7'}, // ă, ắ, ằ, ẳ, ẵ, ặ
            {'\u00E2', '\u1EA5', '\u1EA7', '\u1EA9', '\u1EAB', '\u1EAD'}, // â, ấ, ầ, ẩ, ẫ, ậ
            {'e', '\u00E9', '\u00E8', '\u1EBB', '\u1EBD', '\u1EB9'}, // e, é, è, ẻ, ẽ, ẹ
            {'\u00EA', '\u1EBF', '\u1EC1', '\u1EC3', '\u1EC5', '\u1EC7'}, // ê, ế, ề, ể, ễ, ệ
            {'i', '\u00ED', '\u00EC', '\u1EC9', '\u0129', '\u1ECB'}, // i, í, ì, ỉ, ĩ, ị
            {'o', '\u00F3', '\u00F2', '\u1ECF', '\u00F5', '\u1ECD'}, // o, ó, ò, ỏ, õ, ọ
            {'\u00F4', '\u1ED1', '\u1ED3', '\u1ED5', '\u1ED7', '\u1ED9'}, // ô, ố, ồ, ổ, ỗ, ộ
            {'\u01A1', '\u1EDB', '\u1EDD', '\u1EDF', '\u1EE1', '\u1EE3'}, // ơ, ớ, ờ, ở, ỡ, ợ
            {'u', '\u00FA', '\u00F9', '\u1EE7', '\u0169', '\u1EE5'}, // u, ú, ù, ủ, ũ, ụ
            {'\u01B0', '\u1EE9', '\u1EEB', '\u1EED', '\u1EEF', '\u1EF1'}, // ư, ứ, ừ, ử, ữ, ự
            {'y', '\u00FD', '\u1EF3', '\u1EF7', '\u1EF9', '\u1EF5'}  // y, ý, ỳ, ỷ, ỹ, ỵ
    };

    private static final char[][] UPPER_VOWELS = {
            {'A', '\u00C1', '\u00C0', '\u1EA2', '\u00C3', '\u1EA0'}, // A, Á, À, Ả, Ã, Ạ
            {'\u0102', '\u1EAE', '\u1EB0', '\u1EB2', '\u1EB4', '\u1EB6'}, // Ă, Ắ, Ằ, Ẳ, Ẵ, Ặ
            {'\u00C2', '\u1EA4', '\u1EA6', '\u1EA8', '\u1EAA', '\u1EAC'}, // Â, Ấ, Ầ, Ẩ, Ẫ, Ậ
            {'E', '\u00C9', '\u00C8', '\u1EBA', '\u1EBC', '\u1EB8'}, // E, É, È, Ẻ, Ẽ, Ẹ
            {'\u00CA', '\u1EBE', '\u1EC0', '\u1EC2', '\u1EC4', '\u1EC6'}, // Ê, Ế, Ề, Ể, Ễ, Ệ
            {'I', '\u00CD', '\u00CC', '\u1EC8', '\u0128', '\u1ECA'}, // I, Í, Ì, Ỉ, Ĩ, Ị
            {'O', '\u00D3', '\u00D2', '\u1ECE', '\u00D5', '\u1ECC'}, // O, Ó, Ò, Ỏ, Õ, Ọ
            {'\u00D4', '\u1ED0', '\u1ED2', '\u1ED4', '\u1ED6', '\u1ED8'}, // Ô, Ố, Ồ, Ổ, Ỗ, Ộ
            {'\u01A0', '\u1EDA', '\u1EDC', '\u1EDE', '\u1EE0', '\u1EE2'}, // Ơ, Ớ, Ờ, Ở, Ỡ, Ợ
            {'U', '\u00DA', '\u00D9', '\u1EE6', '\u0168', '\u1EE4'}, // U, Ú, Ù, Ủ, Ũ, Ụ
            {'\u01AF', '\u1EE8', '\u1EEA', '\u1EEC', '\u1EEE', '\u1EF0'}, // Ư, Ứ, Ừ, Ử, Ữ, Ự
            {'Y', '\u00DD', '\u1EF2', '\u1EF6', '\u1EF8', '\u1EF4'}  // Y, Ý, Ỳ, Ỷ, Ỹ, Ỵ
    };

    private static final class VowelInfo {
        final int base;
        final int tone;
        final boolean upper;

        VowelInfo(int base, int tone, boolean upper) {
            this.base = base;
            this.tone = tone;
            this.upper = upper;
        }
    }

    private static final Map<Character, VowelInfo> CHAR_TO_VOWEL;

    private static final Set<String> VALID_ONSETS = Set.of(
            "", "b", "c", "ch", "d", "\u0111", "g", "gh", "gi", "h", "k", "kh",
            "l", "m", "n", "ng", "ngh", "nh", "p", "ph", "qu", "r", "s", "t",
            "th", "tr", "v", "x"
    );

    private static final Set<String> VALID_CODAS = Set.of(
            "", "c", "ch", "m", "n", "ng", "nh", "p", "t"
    );

    private static final Set<String> STOP_CODAS = Set.of(
            "c", "ch", "p", "t"
    );

    private static final List<String> TV_PHRASES = List.of(
            "nh\u1EA1c v\u00E0ng bolero",
            "nh\u1EA1c tr\u1EEF t\u00ECnh ch\u1ECDn l\u1ECDc",
            "nh\u1EA1c thi\u1EBFu nhi s\u00F4i \u0111\u1ED9ng",
            "nh\u1EA1c kh\u00F4ng l\u1EDDi th\u01B0 gi\u00E3n",
            "nh\u1EA1c tr\u1EBB remix m\u1EDBi nh\u1EA5t",
            "nh\u1EA1c xu\u00E2n s\u00F4i \u0111\u1ED9ng",
            "nh\u1EA1c lofi chill nh\u1EB9 nh\u00E0ng",
            "karaoke nh\u1EA1c tr\u1EBB",
            "karaoke bolero tone nam",
            "karaoke bolero tone n\u1EEF",
            "karaoke nh\u1EA1c s\u1ED1ng tr\u1EEF t\u00ECnh",
            "karaoke song ca hay nh\u1EA5t",
            "th\u1EDDi s\u1EF1 vtv1 h\u00F4m nay",
            "tin t\u1EE9c 24h",
            "tin nhanh vi\u1EC7t nam h\u00F4m nay",
            "vtv3 tr\u1EF1c ti\u1EBFp",
            "phim h\u00E0nh \u0111\u1ED9ng thuy\u1EBFt minh",
            "phim ki\u1EBFm hi\u1EC7p hay",
            "phim l\u1EBB m\u1EDBi nh\u1EA5t",
            "phim c\u1ED5 trang trung qu\u1ED1c thuy\u1EBFt minh",
            "phim ho\u1EA1t h\u00ECnh doraemon ti\u1EBFng vi\u1EC7t",
            "phim vi\u1EC7t nam chi\u1EBFu r\u1EA1p",
            "phim ma kinh d\u1ECB thuy\u1EBFt minh",
            "review phim t\u00F3m t\u1EAFt",
            "h\u00E0i k\u1ECBch tr\u01B0\u1EDDng giang",
            "h\u00E0i tr\u1EA5n th\u00E0nh",
            "h\u00E0i ho\u00E0i linh xu\u00E2n hinh",
            "t\u00E1o qu\u00E2n g\u1EB7p nhau cu\u1ED1i n\u0103m",
            "tr\u1EF1c ti\u1EBFp b\u00F3ng \u0111\u00E1",
            "ngo\u1EA1i h\u1EA1ng anh",
            "highlight b\u00F3ng \u0111\u00E1 h\u00F4m nay",
            "\u0111\u1ED9i tuy\u1EC3n vi\u1EC7t nam",
            "c\u1EA3i l\u01B0\u01A1ng x\u01B0a",
            "t\u00E2n c\u1ED5 giao duy\u00EAn",
            "l\u1EC7 quy\u00EAn bolero",
            "\u0111an nguy\u00EAn nh\u1EA1c v\u00E0ng",
            "nh\u01B0 qu\u1EF3nh tr\u01B0\u1EDDng v\u0169",
            "m\u1EF9 t\u00E2m acoustic",
            "h\u00E0 anh tu\u1EA5n live concert",
            "s\u01A1n t\u00F9ng mtp",
            "rap vi\u1EC7t m\u1EDBi nh\u1EA5t",
            "2 ng\u00E0y 1 \u0111\u00EAm m\u1EDBi nh\u1EA5t",
            "anh trai say hi",
            "\u1EA9m th\u1EF1c mi\u1EC1n t\u00E2y",
            "du l\u1ECBch kh\u00E1m ph\u00E1 vi\u1EC7t nam"
    );

    private static final List<String> TV_PHRASES_UNACCENTED;

    static {
        Map<Character, VowelInfo> map = new HashMap<>(256);
        for (int b = 0; b < LOWER_VOWELS.length; b++) {
            for (int t = 0; t < 6; t++) {
                map.put(LOWER_VOWELS[b][t], new VowelInfo(b, t, false));
                map.put(UPPER_VOWELS[b][t], new VowelInfo(b, t, true));
            }
        }
        CHAR_TO_VOWEL = Collections.unmodifiableMap(map);

        List<String> unaccented = new ArrayList<>(TV_PHRASES.size());
        for (String phrase : TV_PHRASES) {
            unaccented.add(stripAccents(phrase).toLowerCase(Locale.ROOT));
        }
        TV_PHRASES_UNACCENTED = Collections.unmodifiableList(unaccented);
    }

    private TelexEngine() {
    }

    private static final class ParsedSyllable {
        final String onsetLower;
        final int preGlideIndex;
        final int nucleusStart;
        final int nucleusEnd;
        final String codaLower;

        ParsedSyllable(String onsetLower, int preGlideIndex, int nucleusStart, int nucleusEnd, String codaLower) {
            this.onsetLower = onsetLower;
            this.preGlideIndex = preGlideIndex;
            this.nucleusStart = nucleusStart;
            this.nucleusEnd = nucleusEnd;
            this.codaLower = codaLower;
        }
    }

    public static String applyBackspace(String currentWord) {
        if (currentWord == null || currentWord.isEmpty()) {
            return "";
        }
        return currentWord.substring(0, currentWord.length() - 1);
    }

    public static String applyKey(String currentWord, char rawChar, boolean telexEnabled) {
        String base = (currentWord == null) ? "" : currentWord;
        if (!telexEnabled || base.isEmpty() || !isLetter(rawChar)) {
            return base + rawChar;
        }

        int splitIdx = base.length();
        while (splitIdx > 0 && isLetter(base.charAt(splitIdx - 1))) {
            splitIdx--;
        }
        String prefix = base.substring(0, splitIdx);
        String word = base.substring(splitIdx);
        if (word.isEmpty()) {
            return base + rawChar;
        }

        char lowerKey = Character.toLowerCase(rawChar);

        if (lowerKey == 'd') {
            return prefix + handleDKey(word, rawChar);
        }

        if (lowerKey == 'a' || lowerKey == 'e' || lowerKey == 'o') {
            String modified = handleDoubleVowelKey(word, rawChar, lowerKey);
            if (modified != null) {
                return prefix + modified;
            }
            return prefix + appendAndRebalance(word, rawChar);
        }

        if (lowerKey == 'w') {
            String modified = handleHornBreveKey(word, rawChar);
            if (modified != null) {
                return prefix + modified;
            }
            return prefix + appendAndRebalance(word, rawChar);
        }

        int toneKey = mapToneKey(lowerKey);
        if (toneKey >= 0) {
            String toned = handleToneKey(word, rawChar, toneKey);
            if (toned != null) {
                return prefix + toned;
            }
            return prefix + appendAndRebalance(word, rawChar);
        }

        return prefix + appendAndRebalance(word, rawChar);
    }

    public static List<String> suggestTvPhrases(String fullText) {
        if (fullText == null) {
            return Collections.emptyList();
        }
        String normalized = fullText.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return Collections.emptyList();
        }
        String unaccentedQuery = stripAccents(normalized);

        List<String> tier1 = new ArrayList<>(5);
        List<String> tier2 = new ArrayList<>(5);
        List<String> tier3 = new ArrayList<>(5);

        for (int i = 0; i < TV_PHRASES.size(); i++) {
            String phrase = TV_PHRASES.get(i);
            String plain = TV_PHRASES_UNACCENTED.get(i);
            if (phrase.startsWith(normalized)) {
                tier1.add(phrase);
            } else if (plain.startsWith(unaccentedQuery)) {
                tier2.add(phrase);
            } else if (phrase.contains(" " + normalized) || plain.contains(" " + unaccentedQuery)
                    || phrase.contains(normalized) || plain.contains(unaccentedQuery)) {
                tier3.add(phrase);
            }
        }

        List<String> result = new ArrayList<>(5);
        appendUpToFive(result, tier1);
        appendUpToFive(result, tier2);
        appendUpToFive(result, tier3);
        return Collections.unmodifiableList(result);
    }

    static String stripAccents(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u0111') {
                sb.append('d');
            } else if (c == '\u0110') {
                sb.append('D');
            } else {
                VowelInfo vi = CHAR_TO_VOWEL.get(c);
                if (vi != null) {
                    sb.append(plainAsciiVowel(vi.base, vi.upper));
                } else {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static void appendUpToFive(List<String> target, List<String> source) {
        for (String item : source) {
            if (target.size() >= 5) {
                return;
            }
            if (!target.contains(item)) {
                target.add(item);
            }
        }
    }

    private static char plainAsciiVowel(int base, boolean upper) {
        char ch;
        switch (base) {
            case BASE_A:
            case BASE_A_BREVE:
            case BASE_A_CIRC:
                ch = 'a';
                break;
            case BASE_E:
            case BASE_E_CIRC:
                ch = 'e';
                break;
            case BASE_I:
                ch = 'i';
                break;
            case BASE_O:
            case BASE_O_CIRC:
            case BASE_O_HORN:
                ch = 'o';
                break;
            case BASE_U:
            case BASE_U_HORN:
                ch = 'u';
                break;
            case BASE_Y:
            default:
                ch = 'y';
                break;
        }
        return upper ? Character.toUpperCase(ch) : ch;
    }

    private static char makeVowel(int base, int tone, boolean upper) {
        return upper ? UPPER_VOWELS[base][tone] : LOWER_VOWELS[base][tone];
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || c == '\u0111'
                || c == '\u0110'
                || CHAR_TO_VOWEL.containsKey(c);
    }

    private static int mapToneKey(char lowerKey) {
        switch (lowerKey) {
            case 'z':
                return 0;
            case 's':
                return 1;
            case 'f':
                return 2;
            case 'r':
                return 3;
            case 'x':
                return 4;
            case 'j':
                return 5;
            default:
                return -1;
        }
    }

    private static String handleDKey(String word, char rawChar) {
        int len = word.length();
        char last = word.charAt(len - 1);

        // Undo immediate đ/Đ at end -> dd / Dd / DD
        if (last == '\u0111' || last == '\u0110') {
            char reverted = (last == '\u0110') ? 'D' : 'd';
            return word.substring(0, len - 1) + reverted + rawChar;
        }

        // Immediate d/D at end -> đ/Đ (unless already undone "dd")
        if (last == 'd' || last == 'D') {
            boolean precededByD = len >= 2 && Character.toLowerCase(word.charAt(len - 2)) == 'd';
            if (!precededByD) {
                char converted = (last == 'D') ? '\u0110' : '\u0111';
                return word.substring(0, len - 1) + converted;
            }
            return word + rawChar;
        }

        // Delayed start-of-syllable d -> đ (e.g. "da" + 'd' -> "đa", "dương" + 'd' -> "đương")
        // and delayed undo ("đa" + 'd' -> "dad") without touching English words like "android".
        if (len >= 2) {
            ParsedSyllable parsed = parseSyllable(word);
            if (parsed != null) {
                char first = word.charAt(0);
                if ("d".equals(parsed.onsetLower)) {
                    char converted = (first == 'D') ? '\u0110' : '\u0111';
                    return converted + word.substring(1);
                }
                if ("\u0111".equals(parsed.onsetLower)) {
                    char reverted = (first == '\u0110') ? 'D' : 'd';
                    return reverted + word.substring(1) + rawChar;
                }
            }
        }

        return word + rawChar;
    }

    private static String handleDoubleVowelKey(String word, char rawChar, char lowerKey) {
        ParsedSyllable parsed = parseSyllable(word);
        if (parsed == null) {
            return null;
        }

        final int plainBase;
        final int circBase;
        if (lowerKey == 'a') {
            plainBase = BASE_A;
            circBase = BASE_A_CIRC;
        } else if (lowerKey == 'e') {
            plainBase = BASE_E;
            circBase = BASE_E_CIRC;
        } else {
            plainBase = BASE_O;
            circBase = BASE_O_CIRC;
        }

        // 1. Undo if nucleus already contains circumflex vowel (â/ê/ô)
        for (int i = parsed.nucleusStart; i < parsed.nucleusEnd; i++) {
            VowelInfo vi = CHAR_TO_VOWEL.get(word.charAt(i));
            if (vi != null && vi.base == circBase) {
                StringBuilder sb = new StringBuilder(word.length() + 1);
                sb.append(word, 0, i);
                sb.append(makeVowel(plainBase, vi.tone, vi.upper));
                sb.append(word, i + 1, word.length());
                sb.append(rawChar);
                return sb.toString();
            }
        }

        // 2. If already undone (two adjacent plain vowels of the same base, e.g. "oo"), do not re-trigger
        for (int i = parsed.nucleusStart; i + 1 < parsed.nucleusEnd; i++) {
            VowelInfo v1 = CHAR_TO_VOWEL.get(word.charAt(i));
            VowelInfo v2 = CHAR_TO_VOWEL.get(word.charAt(i + 1));
            if (v1 != null && v2 != null && v1.base == plainBase && v2.base == plainBase) {
                return null;
            }
        }

        // 3. Convert matching plain vowel to circumflex and rebalance tone
        for (int i = parsed.nucleusStart; i < parsed.nucleusEnd; i++) {
            VowelInfo vi = CHAR_TO_VOWEL.get(word.charAt(i));
            if (vi != null && vi.base == plainBase) {
                int syllableTone = extractSyllableTone(word, parsed);
                char[] chars = clearAllSyllableTones(word, parsed);
                chars[i] = makeVowel(circBase, 0, vi.upper);
                String updated = new String(chars);
                if (syllableTone > 0) {
                    ParsedSyllable reparsed = parseSyllable(updated);
                    if (reparsed != null) {
                        return applyToneToSyllable(updated, reparsed, syllableTone);
                    }
                }
                return updated;
            }
        }

        return null;
    }

    private static String handleHornBreveKey(String word, char rawChar) {
        ParsedSyllable parsed = parseSyllable(word);
        if (parsed == null) {
            return null;
        }

        int nStart = parsed.nucleusStart;
        int nEnd = parsed.nucleusEnd;
        int syllableTone = extractSyllableTone(word, parsed);

        // 1. Compound undo: ươ + w -> uow
        for (int i = nStart; i + 1 < nEnd; i++) {
            VowelInfo v1 = CHAR_TO_VOWEL.get(word.charAt(i));
            VowelInfo v2 = CHAR_TO_VOWEL.get(word.charAt(i + 1));
            if (v1 != null && v2 != null && v1.base == BASE_U_HORN && v2.base == BASE_O_HORN) {
                StringBuilder sb = new StringBuilder(word.length() + 1);
                sb.append(word, 0, i);
                sb.append(makeVowel(BASE_U, v1.tone, v1.upper));
                sb.append(makeVowel(BASE_O, v2.tone, v2.upper));
                sb.append(word, i + 2, word.length());
                sb.append(rawChar);
                return sb.toString();
            }
        }

        // 2. Compound forward: uo / ưo / uơ + w -> ươ
        for (int i = nStart; i + 1 < nEnd; i++) {
            VowelInfo v1 = CHAR_TO_VOWEL.get(word.charAt(i));
            VowelInfo v2 = CHAR_TO_VOWEL.get(word.charAt(i + 1));
            if (v1 != null && v2 != null
                    && (v1.base == BASE_U || v1.base == BASE_U_HORN)
                    && (v2.base == BASE_O || v2.base == BASE_O_HORN)) {
                char[] chars = clearAllSyllableTones(word, parsed);
                chars[i] = makeVowel(BASE_U_HORN, 0, v1.upper);
                chars[i + 1] = makeVowel(BASE_O_HORN, 0, v2.upper);
                return finalizeModifiedSyllable(new String(chars), syllableTone);
            }
        }

        // 3. Single undo: ă -> aw, ơ -> ow, ư -> uw
        for (int i = nStart; i < nEnd; i++) {
            VowelInfo vi = CHAR_TO_VOWEL.get(word.charAt(i));
            if (vi != null && (vi.base == BASE_A_BREVE || vi.base == BASE_O_HORN || vi.base == BASE_U_HORN)) {
                int revertedBase = (vi.base == BASE_A_BREVE) ? BASE_A
                        : (vi.base == BASE_O_HORN ? BASE_O : BASE_U);
                StringBuilder sb = new StringBuilder(word.length() + 1);
                sb.append(word, 0, i);
                sb.append(makeVowel(revertedBase, vi.tone, vi.upper));
                sb.append(word, i + 1, word.length());
                sb.append(rawChar);
                return sb.toString();
            }
        }

        // 4. Special vowel pairs: "oa" + w -> "oă" (xoăn, hoặc) and "ua" + w -> "ưa" (mưa, giữa, cửa)
        if (nEnd - nStart >= 2) {
            VowelInfo v0 = CHAR_TO_VOWEL.get(word.charAt(nStart));
            VowelInfo v1 = CHAR_TO_VOWEL.get(word.charAt(nStart + 1));
            if (v0 != null && v1 != null && v0.base == BASE_O && v1.base == BASE_A) {
                char[] chars = clearAllSyllableTones(word, parsed);
                chars[nStart + 1] = makeVowel(BASE_A_BREVE, 0, v1.upper);
                return finalizeModifiedSyllable(new String(chars), syllableTone);
            }
            if (v0 != null && v1 != null && v0.base == BASE_U && v1.base == BASE_A) {
                char[] chars = clearAllSyllableTones(word, parsed);
                chars[nStart] = makeVowel(BASE_U_HORN, 0, v0.upper);
                return finalizeModifiedSyllable(new String(chars), syllableTone);
            }
        }

        // 5. Single vowel forward: a -> ă, o -> ơ, u -> ư
        for (int i = nStart; i < nEnd; i++) {
            VowelInfo vi = CHAR_TO_VOWEL.get(word.charAt(i));
            if (vi != null && (vi.base == BASE_A || vi.base == BASE_O || vi.base == BASE_U)) {
                int targetBase = (vi.base == BASE_A) ? BASE_A_BREVE
                        : (vi.base == BASE_O ? BASE_O_HORN : BASE_U_HORN);
                char[] chars = clearAllSyllableTones(word, parsed);
                chars[i] = makeVowel(targetBase, 0, vi.upper);
                return finalizeModifiedSyllable(new String(chars), syllableTone);
            }
        }

        return null;
    }

    private static String handleToneKey(String word, char rawChar, int newTone) {
        ParsedSyllable parsed = parseSyllable(word);
        if (parsed == null) {
            return null;
        }

        int currentTone = extractSyllableTone(word, parsed);

        // 'z' / 'Z': clear existing tone; if none, append literal 'z'/'Z'
        if (newTone == 0) {
            if (currentTone == 0) {
                return null;
            }
            return new String(clearAllSyllableTones(word, parsed));
        }

        // Same-tone undo: remove tone and append literal key (e.g. "as"+"s" -> "as", "of"+"f" -> "of")
        if (currentTone == newTone) {
            String cleared = new String(clearAllSyllableTones(word, parsed));
            return cleared + rawChar;
        }

        // Checked stop codas (-c, -ch, -p, -t) only take sắc (1) or nặng (5); keeps loanwords like "netflix" intact
        if (STOP_CODAS.contains(parsed.codaLower) && newTone != 1 && newTone != 5) {
            return null;
        }

        char[] chars = clearAllSyllableTones(word, parsed);
        upgradeImplicitCircumflexOnTone(chars, parsed, newTone);
        String normalized = new String(chars);
        ParsedSyllable reparsed = parseSyllable(normalized);
        if (reparsed == null) {
            return null;
        }
        return applyToneToSyllable(normalized, reparsed, newTone);
    }

    private static void upgradeImplicitCircumflexOnTone(char[] chars, ParsedSyllable parsed, int tone) {
        int nStart = parsed.nucleusStart;
        int nLen = parsed.nucleusEnd - nStart;

        if ("qu".equals(parsed.onsetLower) && "c".equals(parsed.codaLower) && nLen == 1 && tone == 1) {
            VowelInfo v = CHAR_TO_VOWEL.get(chars[nStart]);
            if (v != null && v.base == BASE_O) {
                chars[nStart] = makeVowel(BASE_O_CIRC, 0, v.upper);
                return;
            }
        }

        if (!parsed.codaLower.isEmpty()) {
            if (nLen == 2) {
                VowelInfo v0 = CHAR_TO_VOWEL.get(chars[nStart]);
                VowelInfo v1 = CHAR_TO_VOWEL.get(chars[nStart + 1]);
                if (v0 != null && v1 != null && (v0.base == BASE_I || v0.base == BASE_Y) && v1.base == BASE_E) {
                    chars[nStart + 1] = makeVowel(BASE_E_CIRC, 0, v1.upper);
                }
            } else if (nLen == 3) {
                VowelInfo v0 = CHAR_TO_VOWEL.get(chars[nStart]);
                VowelInfo v1 = CHAR_TO_VOWEL.get(chars[nStart + 1]);
                VowelInfo v2 = CHAR_TO_VOWEL.get(chars[nStart + 2]);
                if (v0 != null && v1 != null && v2 != null
                        && v0.base == BASE_U && v1.base == BASE_Y && v2.base == BASE_E) {
                    chars[nStart + 2] = makeVowel(BASE_E_CIRC, 0, v2.upper);
                }
            }
        }
    }

    private static String appendAndRebalance(String word, char rawChar) {
        String candidate = word + rawChar;
        ParsedSyllable parsed = parseSyllable(candidate);
        if (parsed == null) {
            return candidate;
        }
        int existingTone = extractSyllableTone(candidate, parsed);
        if (existingTone <= 0) {
            return candidate;
        }
        char[] chars = clearAllSyllableTones(candidate, parsed);
        upgradeImplicitCircumflexOnTone(chars, parsed, existingTone);
        String normalized = new String(chars);
        ParsedSyllable reparsed = parseSyllable(normalized);
        if (reparsed == null) {
            return candidate;
        }
        return applyToneToSyllable(normalized, reparsed, existingTone);
    }

    private static String finalizeModifiedSyllable(String tonelessWord, int tone) {
        if (tone <= 0) {
            return tonelessWord;
        }
        ParsedSyllable reparsed = parseSyllable(tonelessWord);
        if (reparsed == null) {
            return tonelessWord;
        }
        return applyToneToSyllable(tonelessWord, reparsed, tone);
    }

    private static int extractSyllableTone(String word, ParsedSyllable parsed) {
        if (parsed.preGlideIndex >= 0) {
            VowelInfo pre = CHAR_TO_VOWEL.get(word.charAt(parsed.preGlideIndex));
            if (pre != null && pre.tone > 0) {
                return pre.tone;
            }
        }
        for (int i = parsed.nucleusStart; i < parsed.nucleusEnd; i++) {
            VowelInfo vi = CHAR_TO_VOWEL.get(word.charAt(i));
            if (vi != null && vi.tone > 0) {
                return vi.tone;
            }
        }
        return 0;
    }

    private static char[] clearAllSyllableTones(String word, ParsedSyllable parsed) {
        char[] chars = word.toCharArray();
        if (parsed.preGlideIndex >= 0) {
            VowelInfo pre = CHAR_TO_VOWEL.get(chars[parsed.preGlideIndex]);
            if (pre != null && pre.tone > 0) {
                chars[parsed.preGlideIndex] = makeVowel(pre.base, 0, pre.upper);
            }
        }
        for (int i = parsed.nucleusStart; i < parsed.nucleusEnd; i++) {
            VowelInfo vi = CHAR_TO_VOWEL.get(chars[i]);
            if (vi != null && vi.tone > 0) {
                chars[i] = makeVowel(vi.base, 0, vi.upper);
            }
        }
        return chars;
    }

    private static String applyToneToSyllable(String tonelessWord, ParsedSyllable parsed, int tone) {
        char[] chars = tonelessWord.toCharArray();
        int targetIdx = selectToneTargetIndex(chars, parsed);
        VowelInfo target = CHAR_TO_VOWEL.get(chars[targetIdx]);
        if (target != null) {
            chars[targetIdx] = makeVowel(target.base, tone, target.upper);
        }
        return new String(chars);
    }

    private static int selectToneTargetIndex(char[] chars, ParsedSyllable parsed) {
        int start = parsed.nucleusStart;
        int end = parsed.nucleusEnd;
        int len = end - start;

        if (len <= 1) {
            return start;
        }

        // 1. If vowel cluster contains "ươ" (ư followed by ơ), place tone on 'ơ'
        for (int i = start; i + 1 < end; i++) {
            VowelInfo v1 = CHAR_TO_VOWEL.get(chars[i]);
            VowelInfo v2 = CHAR_TO_VOWEL.get(chars[i + 1]);
            if (v1 != null && v2 != null && v1.base == BASE_U_HORN && v2.base == BASE_O_HORN) {
                return i + 1;
            }
        }

        // 2. Else if vowel cluster contains a diacritic vowel (ă, â, ê, ô, ơ, ư), place tone on it
        for (int i = start; i < end; i++) {
            VowelInfo vi = CHAR_TO_VOWEL.get(chars[i]);
            if (vi != null && isDiacriticBase(vi.base)) {
                return i;
            }
        }

        // 3. Else if 3 vowels (oai, uya...), place on middle vowel
        if (len == 3) {
            return start + 1;
        }

        // 4. Else 2 vowels: closed syllable (has coda) -> 2nd vowel; open syllable (no coda) -> 1st vowel
        if (!parsed.codaLower.isEmpty()) {
            return start + 1;
        }
        return start;
    }

    private static boolean isDiacriticBase(int base) {
        return base == BASE_A_BREVE
                || base == BASE_A_CIRC
                || base == BASE_E_CIRC
                || base == BASE_O_CIRC
                || base == BASE_O_HORN
                || base == BASE_U_HORN;
    }

    private static ParsedSyllable parseSyllable(String word) {
        int len = word.length();
        int firstVowel = -1;
        for (int i = 0; i < len; i++) {
            if (CHAR_TO_VOWEL.containsKey(word.charAt(i))) {
                firstVowel = i;
                break;
            }
        }
        if (firstVowel < 0) {
            return null;
        }

        String onsetLower = word.substring(0, firstVowel).toLowerCase(Locale.ROOT);
        int preGlideIndex = -1;
        int nucleusStart = firstVowel;

        if ("q".equals(onsetLower)) {
            VowelInfo v0 = CHAR_TO_VOWEL.get(word.charAt(firstVowel));
            if (v0 != null && v0.base == BASE_U
                    && firstVowel + 1 < len
                    && CHAR_TO_VOWEL.containsKey(word.charAt(firstVowel + 1))) {
                onsetLower = "qu";
                preGlideIndex = firstVowel;
                nucleusStart = firstVowel + 1;
            } else {
                return null;
            }
        } else if ("g".equals(onsetLower)) {
            VowelInfo v0 = CHAR_TO_VOWEL.get(word.charAt(firstVowel));
            if (v0 != null && v0.base == BASE_I
                    && firstVowel + 1 < len
                    && CHAR_TO_VOWEL.containsKey(word.charAt(firstVowel + 1))) {
                onsetLower = "gi";
                preGlideIndex = firstVowel;
                nucleusStart = firstVowel + 1;
            }
        }

        if (!VALID_ONSETS.contains(onsetLower)) {
            return null;
        }

        int nucleusEnd = nucleusStart;
        while (nucleusEnd < len && CHAR_TO_VOWEL.containsKey(word.charAt(nucleusEnd))) {
            nucleusEnd++;
        }

        int nucleusLen = nucleusEnd - nucleusStart;
        if (nucleusLen < 1 || nucleusLen > 3) {
            return null;
        }

        if ("k".equals(onsetLower)) {
            VowelInfo firstNuc = CHAR_TO_VOWEL.get(word.charAt(nucleusStart));
            if (firstNuc == null || (firstNuc.base != BASE_E && firstNuc.base != BASE_E_CIRC
                    && firstNuc.base != BASE_I && firstNuc.base != BASE_Y)) {
                return null;
            }
        }

        String codaLower = word.substring(nucleusEnd).toLowerCase(Locale.ROOT);
        if (!VALID_CODAS.contains(codaLower)) {
            return null;
        }

        return new ParsedSyllable(onsetLower, preGlideIndex, nucleusStart, nucleusEnd, codaLower);
    }
}
