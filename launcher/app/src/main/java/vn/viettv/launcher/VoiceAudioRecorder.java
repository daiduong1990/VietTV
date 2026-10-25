package vn.viettv.launcher;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 16kHz 16-bit Signed Little-Endian Mono PCM recorder optimized for Xiaomi MiTV-MFTR0
 * (Hold-to-Talk BLE Voice Remote scanCode=63 via MiVoiceControl.VoiceScoket),
 * non-blocking READ_NON_BLOCKING EOF detection, adaptive noise-floor VAD, and peak normalization.
 */
public final class VoiceAudioRecorder {

    private static final String TAG = "TvHomeVoiceRec";

    public static final int XIAOMI_VOICE_SCANCODE = 63;
    public static final int SAMPLE_RATE_HZ = 16000;
    private static final int FRAME_DURATION_MS = 50;
    private static final int FRAME_SAMPLES = (SAMPLE_RATE_HZ * FRAME_DURATION_MS) / 1000; // 800 samples

    private static final long NOISE_FLOOR_CALIB_MS = 200L;
    private static final double MIN_SPEECH_RMS_THRESHOLD = 140.0;
    private static final double NOISE_FLOOR_MULTIPLIER = 2.4;

    private static final long MIN_SPEECH_CAPTURE_MS = 260L;
    private static final long MAX_INITIAL_SILENCE_WALL_MS = 9000L;
    private static final long BLE_STREAM_EOF_SILENCE_MS = 320L;
    private static final long POST_SPEECH_SILENCE_STOP_MS = 1150L;
    private static final long HARD_CAP_WALL_MS = 14000L;
    private static final long MAX_STUCK_KEY_HOLD_MS = 15000L;

    private static volatile VoiceAudioRecorder activeInstance = null;
    private static volatile boolean physicalVoiceKeyHeld = false;
    private static volatile long lastPhysicalKeyChangeWallMs = 0L;

    public interface Listener {
        void onRmsChanged(float normalizedRms);

        void onRecordingComplete(byte[] pcm16kBytes, int durationMs);

        void onError(String message);

        default void onSpeechStart() {
        }

        default void onKeyHoldStateChanged(boolean held) {
        }
    }

    public static boolean isPhysicalVoiceKeyHeld() {
        return physicalVoiceKeyHeld
                && (SystemClock.elapsedRealtime() - lastPhysicalKeyChangeWallMs) < MAX_STUCK_KEY_HOLD_MS;
    }

    public static void notifyPhysicalVoiceKeyState(boolean held) {
        physicalVoiceKeyHeld = held;
        lastPhysicalKeyChangeWallMs = SystemClock.elapsedRealtime();
        VoiceAudioRecorder current = activeInstance;
        if (current != null) {
            current.onPhysicalVoiceKeyChanged(held);
        }
    }

    private final Handler callbackHandler;
    private final Listener listener;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean finishRequested = false;
    private volatile boolean cancelled = false;
    private volatile boolean recordingActive = false;
    private volatile boolean keyReleasedSinceSpeech = false;
    private volatile long keyPressResetWallMs = 0L;
    private Thread workerThread;

    public VoiceAudioRecorder(Context context, Listener listener) {
        this(context, new Handler(Looper.getMainLooper()), listener);
    }

    public VoiceAudioRecorder(Context context, Handler callbackHandler, Listener listener) {
        this.callbackHandler = callbackHandler != null ? callbackHandler : new Handler(Looper.getMainLooper());
        this.listener = listener;
    }

    public boolean isRecording() {
        return recordingActive && !cancelled;
    }

    public void onPhysicalVoiceKeyChanged(final boolean held) {
        if (held) {
            keyReleasedSinceSpeech = false;
            keyPressResetWallMs = SystemClock.elapsedRealtime();
        } else {
            keyReleasedSinceSpeech = true;
        }
        if (!cancelled && listener != null) {
            callbackHandler.post(() -> {
                if (!cancelled) {
                    listener.onKeyHoldStateChanged(held);
                }
            });
        }
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        cancelled = false;
        finishRequested = false;
        keyReleasedSinceSpeech = false;
        recordingActive = true;
        activeInstance = this;
        workerThread = new Thread(this::captureLoop, "TvHome-VoiceRecorder");
        workerThread.setPriority(Thread.MAX_PRIORITY - 1);
        workerThread.start();
    }

    /**
     * Finalizes recording immediately when user presses OK / DPAD_CENTER after speaking.
     */
    public void finishEarly() {
        finishRequested = true;
    }

    /**
     * Aborts recording immediately without delivering completion or error callbacks.
     */
    public void cancel() {
        cancelled = true;
        finishRequested = true;
        recordingActive = false;
        if (activeInstance == this) {
            activeInstance = null;
        }
        Thread t = workerThread;
        if (t != null) {
            t.interrupt();
        }
    }

    private void captureLoop() {
        AudioRecord audioRecord = null;
        try {
            audioRecord = openBestAudioRecord();
            if (audioRecord == null) {
                recordingActive = false;
                postError("Không mở được micro. Vui lòng kiểm tra quyền thu âm hoặc điều khiển giọng nói.");
                return;
            }

            audioRecord.startRecording();
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                recordingActive = false;
                postError("Micro đang bận bởi ứng dụng khác. Hãy thử lại.");
                return;
            }

            ByteArrayOutputStream pcmOut = new ByteArrayOutputStream(SAMPLE_RATE_HZ * 2 * 4);
            short[] frameBuf = new short[FRAME_SAMPLES];

            int peakSinceStart = 0;
            long calibMs = 0L;
            double noiseFloorSum = 0.0;
            int noiseFloorFrames = 0;
            double noiseFloor = 55.0;

            boolean speechDetected = false;
            boolean speechStartNotified = false;
            long capturedSpeechSamples = 0L;
            long trailingSilenceMs = 0L;

            long totalWallStartMs = SystemClock.elapsedRealtime();
            long lastNonZeroReadWallMs = totalWallStartMs;
            activeInstance = this;

            while (!cancelled && !finishRequested) {
                int read = audioRecord.read(frameBuf, 0, frameBuf.length, AudioRecord.READ_NON_BLOCKING);
                if (cancelled) {
                    return;
                }
                if (read < 0) {
                    Log.w(TAG, "AudioRecord.read error code: " + read);
                    break;
                }

                long now = SystemClock.elapsedRealtime();
                if (read == 0) {
                    if (speechDetected) {
                        long speechMs = (capturedSpeechSamples * 1000L) / SAMPLE_RATE_HZ;
                        long zeroReadGapMs = now - lastNonZeroReadWallMs;
                        if (speechMs >= MIN_SPEECH_CAPTURE_MS) {
                            if ((keyReleasedSinceSpeech && !isPhysicalVoiceKeyHeld())
                                    || (!isPhysicalVoiceKeyHeld() && zeroReadGapMs >= BLE_STREAM_EOF_SILENCE_MS)
                                    || zeroReadGapMs >= POST_SPEECH_SILENCE_STOP_MS) {
                                break;
                            }
                        }
                    } else {
                        keyReleasedSinceSpeech = false;
                        long waitAnchorMs = Math.max(totalWallStartMs, keyPressResetWallMs);
                        if (!isPhysicalVoiceKeyHeld() && (now - waitAnchorMs) >= MAX_INITIAL_SILENCE_WALL_MS) {
                            recordingActive = false;
                            postError("Chưa nghe thấy giọng nói. Hãy nhấn và GIỮ nút Micro trên điều khiển khi nói.");
                            return;
                        }
                    }

                    if ((now - totalWallStartMs) >= HARD_CAP_WALL_MS) {
                        break;
                    }
                    SystemClock.sleep(20L);
                    continue;
                }

                lastNonZeroReadWallMs = now;
                long frameMs = Math.max(1L, (read * 1000L) / SAMPLE_RATE_HZ);
                double sumSq = 0.0;
                int framePeak = 0;

                for (int i = 0; i < read; i++) {
                    short s = frameBuf[i];
                    int abs = Math.abs((int) s);
                    if (abs > framePeak) {
                        framePeak = abs;
                    }
                    sumSq += (double) s * (double) s;
                    pcmOut.write(s & 0xFF);
                    pcmOut.write((s >> 8) & 0xFF);
                }

                if (framePeak > peakSinceStart) {
                    peakSinceStart = framePeak;
                }

                double rms = Math.sqrt(sumSq / (double) read);

                // Adaptive noise floor estimation during first 200ms of non-zero audio
                if (calibMs < NOISE_FLOOR_CALIB_MS) {
                    calibMs += frameMs;
                    noiseFloorSum += Math.min(rms, 750.0);
                    noiseFloorFrames++;
                    noiseFloor = noiseFloorSum / (double) noiseFloorFrames;
                }

                double speechThreshold = Math.max(MIN_SPEECH_RMS_THRESHOLD, noiseFloor * NOISE_FLOOR_MULTIPLIER);
                float normalizedRms = toNormalizedRms(rms, noiseFloor);
                postRms(normalizedRms);

                if (rms > speechThreshold) {
                    if (!speechDetected) {
                        speechDetected = true;
                        keyReleasedSinceSpeech = false;
                    }
                    if (!speechStartNotified) {
                        speechStartNotified = true;
                        postSpeechStart();
                    }
                    capturedSpeechSamples += read;
                    trailingSilenceMs = 0L;
                } else if (speechDetected) {
                    trailingSilenceMs += frameMs;
                    long speechDurationMs = (capturedSpeechSamples * 1000L) / SAMPLE_RATE_HZ;
                    if (speechDurationMs >= MIN_SPEECH_CAPTURE_MS
                            && trailingSilenceMs >= POST_SPEECH_SILENCE_STOP_MS) {
                        break;
                    }
                    if (speechDurationMs >= MIN_SPEECH_CAPTURE_MS
                            && keyReleasedSinceSpeech
                            && !isPhysicalVoiceKeyHeld()
                            && (now - lastPhysicalKeyChangeWallMs) >= 150L) {
                        break;
                    }
                } else {
                    long waitAnchorMs = Math.max(totalWallStartMs, keyPressResetWallMs);
                    if (!isPhysicalVoiceKeyHeld() && (now - waitAnchorMs) >= MAX_INITIAL_SILENCE_WALL_MS) {
                        recordingActive = false;
                        postError("Chưa nghe thấy giọng nói. Hãy nhấn và GIỮ nút Micro trên điều khiển khi nói.");
                        return;
                    }
                }

                if ((now - totalWallStartMs) >= HARD_CAP_WALL_MS) {
                    break;
                }
            }

            recordingActive = false;
            if (cancelled) {
                return;
            }

            byte[] rawPcm = pcmOut.toByteArray();
            int durationMs = (int) ((rawPcm.length / 2L) * 1000L / SAMPLE_RATE_HZ);
            int minRequiredBytes = (int) ((SAMPLE_RATE_HZ * MIN_SPEECH_CAPTURE_MS) / 1000L) * 2;
            if (rawPcm.length < minRequiredBytes || (!speechDetected && peakSinceStart <= 120)) {
                postError("Chưa nhận được giọng nói rõ ràng. Hãy nhấn giữ nút Micro khi nói.");
                return;
            }

            byte[] normalizedPcm = normalizePcm16Le(rawPcm);
            postComplete(normalizedPcm, durationMs);
        } catch (SecurityException se) {
            recordingActive = false;
            Log.e(TAG, "Missing RECORD_AUDIO permission: " + se.getMessage());
            postError("Chưa cấp quyền Micro (RECORD_AUDIO).");
        } catch (Throwable t) {
            recordingActive = false;
            Log.e(TAG, "Audio capture error: " + t.getMessage());
            postError("Lỗi thu âm: " + t.getMessage());
        } finally {
            recordingActive = false;
            if (activeInstance == this) {
                activeInstance = null;
            }
            if (audioRecord != null) {
                try {
                    if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "AudioRecord.stop warning: " + t.getMessage());
                }
                try {
                    audioRecord.release();
                } catch (Throwable t) {
                    Log.w(TAG, "AudioRecord.release warning: " + t.getMessage());
                }
            }
        }
    }

    /**
     * Peak-normalizes quiet 16-bit signed little-endian PCM audio:
     * When 150 < maxPeak < 10000, applies gain min(8.0f, 18000.0f / maxPeak) with clamping to [-32767, 32767].
     */
    public static byte[] normalizePcm16Le(byte[] pcmBytes) {
        if (pcmBytes == null || pcmBytes.length < 2) {
            return pcmBytes != null ? pcmBytes : new byte[0];
        }
        int evenLen = pcmBytes.length & ~1;
        int maxPeak = 0;
        for (int i = 0; i < evenLen; i += 2) {
            short sample = (short) ((pcmBytes[i] & 0xFF) | (pcmBytes[i + 1] << 8));
            int abs = Math.abs((int) sample);
            if (abs > maxPeak) {
                maxPeak = abs;
            }
        }

        if (maxPeak <= 150 || maxPeak >= 10000) {
            if (evenLen == pcmBytes.length) {
                return pcmBytes;
            }
            byte[] copy = new byte[evenLen];
            System.arraycopy(pcmBytes, 0, copy, 0, evenLen);
            return copy;
        }

        float gain = Math.min(8.0f, 18000.0f / (float) maxPeak);
        byte[] out = new byte[evenLen];
        for (int i = 0; i < evenLen; i += 2) {
            short sample = (short) ((pcmBytes[i] & 0xFF) | (pcmBytes[i + 1] << 8));
            int scaled = Math.round(sample * gain);
            if (scaled > 32767) {
                scaled = 32767;
            } else if (scaled < -32767) {
                scaled = -32767;
            }
            out[i] = (byte) (scaled & 0xFF);
            out[i + 1] = (byte) ((scaled >> 8) & 0xFF);
        }
        return out;
    }

    private static float toNormalizedRms(double rms, double noiseFloor) {
        double aboveFloor = Math.max(0.0, rms - Math.max(35.0, noiseFloor * 0.9));
        double norm = Math.sqrt(aboveFloor / 3600.0);
        if (norm < 0.0) {
            return 0.0f;
        }
        if (norm > 1.0) {
            return 1.0f;
        }
        return (float) norm;
    }

    private AudioRecord openBestAudioRecord() {
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(minBuf > 0 ? minBuf * 2 : SAMPLE_RATE_HZ * 2, FRAME_SAMPLES * 8);

        int[] sources = new int[]{
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };

        for (int source : sources) {
            AudioRecord candidate = null;
            try {
                candidate = new AudioRecord(
                        source,
                        SAMPLE_RATE_HZ,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferBytes);
                if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                    return candidate;
                }
                candidate.release();
            } catch (SecurityException se) {
                if (candidate != null) {
                    candidate.release();
                }
                throw se;
            } catch (Throwable t) {
                Log.w(TAG, "AudioSource " + source + " unavailable: " + t.getMessage());
                if (candidate != null) {
                    try {
                        candidate.release();
                    } catch (Throwable releaseErr) {
                        Log.w(TAG, "Release failed: " + releaseErr.getMessage());
                    }
                }
            }
        }
        return null;
    }

    private void postRms(final float rms) {
        if (cancelled || listener == null) {
            return;
        }
        callbackHandler.post(() -> {
            if (!cancelled) {
                listener.onRmsChanged(rms);
            }
        });
    }

    private void postSpeechStart() {
        if (cancelled || listener == null) {
            return;
        }
        callbackHandler.post(() -> {
            if (!cancelled) {
                listener.onSpeechStart();
            }
        });
    }

    private void postComplete(final byte[] pcmBytes, final int durationMs) {
        if (cancelled || listener == null) {
            return;
        }
        callbackHandler.post(() -> {
            if (!cancelled) {
                listener.onRecordingComplete(pcmBytes, durationMs);
            }
        });
    }

    private void postError(final String message) {
        if (cancelled || listener == null) {
            return;
        }
        callbackHandler.post(() -> {
            if (!cancelled) {
                listener.onError(message);
            }
        });
    }
}
