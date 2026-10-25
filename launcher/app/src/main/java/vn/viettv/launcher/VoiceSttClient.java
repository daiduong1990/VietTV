package vn.viettv.launcher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Standalone pure-Java HTTPS Speech-to-Text client for Vietnamese 16kHz 16-bit mono PCM audio.
 * Zero external dependencies and zero org.json / Android runtime calls on the transcription/parsing
 * paths so it runs identically on Android TV (API 26+) and plain JDK 17 JVM tests.
 */
public final class VoiceSttClient {

    public static final String ENDPOINT_BASE =
            "https://www.google.com/speech-api/v2/recognize?output=json&client=chromium&lang=vi-VN&pFilter=0&key=";
    public static final String GROQ_WHISPER_ENDPOINT =
            "https://api.groq.com/openai/v1/audio/transcriptions";
    public static final String DEFAULT_GROQ_MODEL = "whisper-large-v3-turbo";

    public static final String PREFS_NAME = "launcher_settings";
    public static final String PREF_VOICE_API_KEY = "voice_api_key";
    public static final String PREF_VOICE_API_URL = "voice_api_url";
    public static final String PREF_VOICE_API_MODEL = "voice_api_model";
    public static final String GLOBAL_STT_KEY = "viettv_stt_key";

    public static final String CONTENT_TYPE_PCM_16K = "audio/l16; rate=16000";
    public static final String CHROMIUM_USER_AGENT =
            "Mozilla/5.0 (X11; Linux armv7l) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36";

    public static final int SAMPLE_RATE_HZ = 16000;
    public static final int CONNECT_TIMEOUT_MS = 5000;
    public static final int READ_TIMEOUT_MS = 10000;

    private VoiceSttClient() {
    }

    public static boolean hasConfiguredCloudStt(Context ctx) {
        if (ctx == null) {
            return false;
        }
        return !resolveApiKey(ctx).isEmpty() || !readPrefTrimmed(ctx, PREF_VOICE_API_URL).isEmpty();
    }

    public static String resolveApiKey(Context ctx) {
        String prefKey = readPrefTrimmed(ctx, PREF_VOICE_API_KEY);
        if (!prefKey.isEmpty()) {
            return prefKey;
        }
        return readGlobalKeyTrimmed(ctx);
    }

    private static String readPrefTrimmed(Context ctx, String key) {
        if (ctx == null) {
            return "";
        }
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            if (prefs == null) {
                return "";
            }
            return Objects.toString(prefs.getString(key, ""), "").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String readGlobalKeyTrimmed(Context ctx) {
        if (ctx == null) {
            return "";
        }
        try {
            ContentResolver cr = ctx.getContentResolver();
            if (cr == null) {
                return "";
            }
            return Objects.toString(Settings.Global.getString(cr, GLOBAL_STT_KEY), "").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    public static String transcribeWithUserConfig(Context ctx, byte[] pcm16kBytes) throws IOException {
        String apiUrl = readPrefTrimmed(ctx, PREF_VOICE_API_URL);
        String apiKey = resolveApiKey(ctx);
        String modelPref = readPrefTrimmed(ctx, PREF_VOICE_API_MODEL);

        if (!apiUrl.isEmpty()) {
            String model = resolveModelForUrl(apiUrl, modelPref);
            return transcribeMultipartWhisper(pcm16kBytes, apiUrl, apiKey, model);
        }
        if (apiKey.startsWith("gsk_")) {
            String model = modelPref.isEmpty() ? DEFAULT_GROQ_MODEL : modelPref;
            return transcribeMultipartWhisper(pcm16kBytes, GROQ_WHISPER_ENDPOINT, apiKey, model);
        }
        if (!apiKey.isEmpty()) {
            return transcribeGoogleV2(pcm16kBytes, apiKey);
        }
        throw new IOException("NO_STT_KEY_CONFIGURED");
    }

    private static String resolveModelForUrl(String apiUrl, String modelPref) {
        if (!modelPref.isEmpty()) {
            return modelPref;
        }
        String lower = apiUrl.toLowerCase(Locale.ROOT);
        if (lower.contains("groq.com")) {
            return DEFAULT_GROQ_MODEL;
        }
        if (lower.contains("openai.com")) {
            return "whisper-1";
        }
        return "";
    }

    public static byte[] wrapPcm16kMonoAsWav(byte[] pcmBytes) {
        byte[] src = pcmBytes != null ? pcmBytes : new byte[0];
        int offset = findPcmPayloadOffset(src);
        int payloadLen = Math.max(0, (src.length - offset) & ~1);

        byte[] wav = new byte[44 + payloadLen];
        byte[] headerPrefix = new byte[]{
                'R', 'I', 'F', 'F', 0, 0, 0, 0,
                'W', 'A', 'V', 'E', 'f', 'm', 't', ' ',
                16, 0, 0, 0, 1, 0, 1, 0
        };
        System.arraycopy(headerPrefix, 0, wav, 0, headerPrefix.length);
        writeInt32Le(wav, 4, 36 + payloadLen);
        writeInt32Le(wav, 24, SAMPLE_RATE_HZ);
        writeInt32Le(wav, 28, SAMPLE_RATE_HZ * 2);
        wav[32] = 2;
        wav[34] = 16;
        wav[36] = 'd';
        wav[37] = 'a';
        wav[38] = 't';
        wav[39] = 'a';
        writeInt32Le(wav, 40, payloadLen);
        if (payloadLen > 0) {
            System.arraycopy(src, offset, wav, 44, payloadLen);
        }
        return wav;
    }

    private static void writeInt32Le(byte[] dst, int pos, int val) {
        dst[pos] = (byte) (val & 0xFF);
        dst[pos + 1] = (byte) ((val >>> 8) & 0xFF);
        dst[pos + 2] = (byte) ((val >>> 16) & 0xFF);
        dst[pos + 3] = (byte) ((val >>> 24) & 0xFF);
    }

    public static String transcribeMultipartWhisper(
            byte[] pcm16kBytes,
            String endpointUrl,
            String bearerKey,
            String modelName) throws IOException {
        String cleanUrl = Objects.toString(endpointUrl, "").trim();
        if (cleanUrl.isEmpty()) {
            throw new IOException("NO_STT_KEY_CONFIGURED");
        }
        byte[] wavBytes = wrapPcm16kMonoAsWav(pcm16kBytes);
        if (wavBytes.length <= 44) {
            return "";
        }
        String boundary = "----VietTvWhisperBoundary" + Long.toHexString(System.nanoTime());
        byte[] body = buildMultipartPayload(boundary, wavBytes, Objects.toString(modelName, "").trim());

        HttpURLConnection conn = (HttpURLConnection) new URL(cleanUrl).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", CHROMIUM_USER_AGENT);
            String token = Objects.toString(bearerKey, "").trim();
            if (!token.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
                out.flush();
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                String errBody = readUtf8Bounded(conn.getErrorStream(), 1024);
                throw new IOException("Whisper HTTP " + status + (errBody.isEmpty() ? "" : ": " + errBody));
            }
            return parseWhisperJsonTranscript(readUtf8Bounded(conn.getInputStream(), 256 * 1024));
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] buildMultipartPayload(String boundary, byte[] wavBytes, String modelName)
            throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(wavBytes.length + 512);
        writeUtf8(bos, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n");
        bos.write(wavBytes);
        writeUtf8(bos, "\r\n");
        if (!modelName.isEmpty()) {
            writeFormPart(bos, boundary, "model", modelName);
        }
        writeFormPart(bos, boundary, "language", "vi");
        writeFormPart(bos, boundary, "response_format", "json");
        writeUtf8(bos, "--" + boundary + "--\r\n");
        return bos.toByteArray();
    }

    private static void writeFormPart(ByteArrayOutputStream bos, String boundary, String name, String val)
            throws IOException {
        writeUtf8(bos, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + val + "\r\n");
    }

    private static void writeUtf8(ByteArrayOutputStream bos, String text) throws IOException {
        bos.write(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String transcribeGoogleV2(byte[] pcm16kBytes, String googleApiKey) throws IOException {
        String apiKey = Objects.toString(googleApiKey, "").trim();
        if (apiKey.isEmpty()) {
            throw new IOException("NO_STT_KEY_CONFIGURED");
        }
        if (pcm16kBytes == null || pcm16kBytes.length < 2) {
            return "";
        }
        int offset = findPcmPayloadOffset(pcm16kBytes);
        int payloadLen = (pcm16kBytes.length - offset) & ~1;
        if (payloadLen <= 0) {
            return "";
        }
        String encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name());
        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT_BASE + encodedKey).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoInput(true);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", CONTENT_TYPE_PCM_16K);
            conn.setRequestProperty("User-Agent", CHROMIUM_USER_AGENT);
            conn.setFixedLengthStreamingMode(payloadLen);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(pcm16kBytes, offset, payloadLen);
                out.flush();
            }
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                String errBody = readUtf8Bounded(conn.getErrorStream(), 1024);
                throw new IOException("STT HTTP " + status + (errBody.isEmpty() ? "" : ": " + errBody));
            }
            return parseTranscriptNdjson(readUtf8Bounded(conn.getInputStream(), 256 * 1024));
        } finally {
            conn.disconnect();
        }
    }

    public static String transcribePcm16kMono(byte[] pcmBytes, String customKeyOrNull) throws IOException {
        return transcribeGoogleV2(pcmBytes, customKeyOrNull);
    }

    public static String parseWhisperJsonTranscript(String jsonBody) {
        String trimmed = Objects.toString(jsonBody, "").trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        Object parsed = MiniJsonParser.parseSafe(trimmed);
        if (parsed instanceof Map) {
            Map<?, ?> root = (Map<?, ?>) parsed;
            Object textObj = root.get("text");
            if (textObj instanceof String) {
                return ((String) textObj).trim();
            }
            Object trObj = root.get("transcript");
            if (trObj instanceof String) {
                return ((String) trObj).trim();
            }
        }
        String fallback = extractQuotedFieldFallback(trimmed, "\"text\"");
        return !fallback.isEmpty() ? fallback : extractQuotedFieldFallback(trimmed, "\"transcript\"");
    }

    /**
     * If a caller inadvertently passes a full RIFF/WAVE byte array, advances past header chunks to
     * the raw 'data' payload; otherwise returns 0 for raw PCM buffers.
     */
    private static int findPcmPayloadOffset(byte[] buf) {
        if (buf.length >= 44
                && buf[0] == 'R' && buf[1] == 'I' && buf[2] == 'F' && buf[3] == 'F'
                && buf[8] == 'W' && buf[9] == 'A' && buf[10] == 'V' && buf[11] == 'E') {
            int pos = 12;
            while (pos + 8 <= buf.length) {
                int chunkSize = (buf[pos + 4] & 0xFF)
                        | ((buf[pos + 5] & 0xFF) << 8)
                        | ((buf[pos + 6] & 0xFF) << 16)
                        | ((buf[pos + 7] & 0xFF) << 24);
                if (buf[pos] == 'd' && buf[pos + 1] == 'a' && buf[pos + 2] == 't' && buf[pos + 3] == 'a') {
                    return pos + 8;
                }
                if (chunkSize < 0) {
                    return 44;
                }
                int next = pos + 8 + chunkSize + (chunkSize & 1);
                if (next <= pos || next > buf.length) {
                    return 44;
                }
                pos = next;
            }
            return 44;
        }
        return 0;
    }

    private static String readUtf8Bounded(InputStream in, int maxBytes) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream input = in; ByteArrayOutputStream bos = new ByteArrayOutputStream(1024)) {
            byte[] chunk = new byte[4096];
            int total = 0;
            int n;
            while ((n = input.read(chunk)) != -1) {
                int writable = Math.min(n, maxBytes - total);
                if (writable > 0) {
                    bos.write(chunk, 0, writable);
                    total += writable;
                }
                if (total >= maxBytes) {
                    break;
                }
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        }
    }

    /**
     * Pure-Java NDJSON parser for Google Speech v2 responses.
     * Supports full JSON string unescaping (quotes, backslashes, control chars, and 4-digit hex
     * unicode escapes), confidence ranking across alternatives, and multi-segment final utterances.
     */
    public static String parseTranscriptNdjson(String rawResponse) {
        if (rawResponse == null || rawResponse.isEmpty()) {
            return "";
        }

        Map<Integer, String> finalSegments = new LinkedHashMap<>();
        int syntheticSegIdx = 1000;
        String bestInterimTranscript = "";
        double bestInterimConfidence = -1.0;

        String[] lines = rawResponse.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            Object parsed = MiniJsonParser.parseSafe(line);
            if (!(parsed instanceof Map)) {
                String fallback = extractFirstTranscriptFallback(line);
                if (!fallback.isEmpty() && bestInterimTranscript.isEmpty()) {
                    bestInterimTranscript = fallback;
                }
                continue;
            }

            Map<?, ?> root = (Map<?, ?>) parsed;
            Object resultObj = root.get("result");
            if (!(resultObj instanceof List)) {
                continue;
            }
            List<?> results = (List<?>) resultObj;
            if (results.isEmpty()) {
                continue;
            }

            Integer baseResultIndex = null;
            Object resultIndexObj = root.get("result_index");
            if (resultIndexObj instanceof Number) {
                baseResultIndex = ((Number) resultIndexObj).intValue();
            }

            for (int i = 0; i < results.size(); i++) {
                Object item = results.get(i);
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<?, ?> resEntry = (Map<?, ?>) item;
                boolean isFinal = Boolean.TRUE.equals(resEntry.get("final"));
                BestAlt bestAlt = pickBestAlternative(resEntry.get("alternative"));
                if (bestAlt == null || bestAlt.transcript.isEmpty()) {
                    continue;
                }

                if (isFinal) {
                    int segKey = (baseResultIndex != null) ? (baseResultIndex + i) : (syntheticSegIdx++);
                    finalSegments.put(segKey, bestAlt.transcript);
                } else if (bestAlt.confidence > bestInterimConfidence || bestInterimTranscript.isEmpty()) {
                    bestInterimConfidence = bestAlt.confidence;
                    bestInterimTranscript = bestAlt.transcript;
                }
            }
        }

        if (!finalSegments.isEmpty()) {
            StringBuilder joined = new StringBuilder();
            for (String segment : finalSegments.values()) {
                String trimmed = segment.trim();
                if (!trimmed.isEmpty()) {
                    if (joined.length() > 0) {
                        joined.append(' ');
                    }
                    joined.append(trimmed);
                }
            }
            return joined.toString();
        }

        if (!bestInterimTranscript.isEmpty()) {
            return bestInterimTranscript.trim();
        }

        return extractFirstTranscriptFallback(rawResponse).trim();
    }

    private static BestAlt pickBestAlternative(Object altObj) {
        if (!(altObj instanceof List)) {
            return null;
        }
        List<?> alternatives = (List<?>) altObj;
        String chosenText = null;
        double chosenConfidence = -1.0;

        for (int i = 0; i < alternatives.size(); i++) {
            Object elem = alternatives.get(i);
            if (!(elem instanceof Map)) {
                continue;
            }
            Map<?, ?> alt = (Map<?, ?>) elem;
            Object trObj = alt.get("transcript");
            if (!(trObj instanceof String)) {
                continue;
            }
            String text = ((String) trObj).trim();
            if (text.isEmpty()) {
                continue;
            }
            double conf = -1.0;
            Object confObj = alt.get("confidence");
            if (confObj instanceof Number) {
                conf = ((Number) confObj).doubleValue();
            }
            if (chosenText == null || (conf > chosenConfidence && conf >= 0.0)) {
                chosenText = text;
                chosenConfidence = conf;
            }
        }
        return chosenText != null ? new BestAlt(chosenText, chosenConfidence) : null;
    }

    /**
     * Resilient char-scanner fallback if an NDJSON line contains non-standard wrapper characters.
     */
    private static String extractFirstTranscriptFallback(String src) {
        return extractQuotedFieldFallback(src, "\"transcript\"");
    }

    private static String extractQuotedFieldFallback(String src, String quotedKey) {
        int searchFrom = 0;
        while (searchFrom < src.length()) {
            int keyPos = src.indexOf(quotedKey, searchFrom);
            if (keyPos < 0) {
                return "";
            }
            int idx = keyPos + quotedKey.length();
            while (idx < src.length() && Character.isWhitespace(src.charAt(idx))) {
                idx++;
            }
            if (idx < src.length() && src.charAt(idx) == ':') {
                idx++;
                while (idx < src.length() && Character.isWhitespace(src.charAt(idx))) {
                    idx++;
                }
                if (idx < src.length() && src.charAt(idx) == '"') {
                    MiniJsonParser scanner = new MiniJsonParser(src, idx);
                    String val = scanner.readJsonStringSafe();
                    if (val != null && !val.trim().isEmpty()) {
                        return val.trim();
                    }
                }
            }
            searchFrom = keyPos + quotedKey.length();
        }
        return "";
    }

    private static final class BestAlt {
        final String transcript;
        final double confidence;

        BestAlt(String transcript, double confidence) {
            this.transcript = transcript;
            this.confidence = confidence;
        }
    }

    /**
     * Minimal RFC 8259 compliant recursive-descent JSON parser in pure Java.
     */
    private static final class MiniJsonParser {
        private final String s;
        private int pos;

        MiniJsonParser(String s, int startPos) {
            this.s = s;
            this.pos = startPos;
        }

        static Object parseSafe(String json) {
            try {
                MiniJsonParser p = new MiniJsonParser(json, 0);
                p.skipWs();
                if (p.pos >= p.s.length()) {
                    return null;
                }
                return p.readValue();
            } catch (RuntimeException ex) {
                return null;
            }
        }

        String readJsonStringSafe() {
            try {
                return readString();
            } catch (RuntimeException ex) {
                return null;
            }
        }

        private void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private Object readValue() {
            skipWs();
            if (pos >= s.length()) {
                throw new IllegalArgumentException("Unexpected EOF");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expectLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    expectLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    expectLiteral("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw new IllegalArgumentException("Unexpected char: " + c);
            }
        }

        private Map<String, Object> readObject() {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (pos < s.length()) {
                skipWs();
                String key = readString();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw new IllegalArgumentException("Expected ':'");
                }
                pos++;
                Object val = readValue();
                map.put(key, val);
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                } else if (pos < s.length() && s.charAt(pos) == '}') {
                    pos++;
                    return map;
                } else {
                    throw new IllegalArgumentException("Expected ',' or '}'");
                }
            }
            throw new IllegalArgumentException("Unterminated object");
        }

        private List<Object> readArray() {
            pos++; // '['
            List<Object> list = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (pos < s.length()) {
                list.add(readValue());
                skipWs();
                if (pos < s.length() && s.charAt(pos) == ',') {
                    pos++;
                } else if (pos < s.length() && s.charAt(pos) == ']') {
                    pos++;
                    return list;
                } else {
                    throw new IllegalArgumentException("Expected ',' or ']'");
                }
            }
            throw new IllegalArgumentException("Unterminated array");
        }

        private String readString() {
            if (pos >= s.length() || s.charAt(pos) != '"') {
                throw new IllegalArgumentException("Expected '\"'");
            }
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("Unterminated escape");
                    }
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (pos + 4 > s.length()) {
                                throw new IllegalArgumentException("Short unicode escape");
                            }
                            int codeUnit = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            pos += 4;
                            sb.append((char) codeUnit);
                            break;
                        default:
                            sb.append(esc);
                            break;
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private Double readNumber() {
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') {
                pos++;
            }
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            return Double.valueOf(s.substring(start, pos));
        }

        private void expectLiteral(String lit) {
            if (!s.startsWith(lit, pos)) {
                throw new IllegalArgumentException("Expected " + lit);
            }
            pos += lit.length();
        }
    }
}
