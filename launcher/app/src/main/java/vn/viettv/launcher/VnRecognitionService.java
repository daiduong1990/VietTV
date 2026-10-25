package vn.viettv.launcher;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * System-wide Android RecognitionService providing Vietnamese speech recognition backed by
 * VoiceAudioRecorder (Xiaomi BLE Remote Hold-to-Talk via VoiceScoket) and VoiceSttClient.
 */
public class VnRecognitionService extends RecognitionService {

    private static final String TAG = "TvHomeVnRecSvc";

    private static volatile boolean listeningActive = false;

    public static boolean isListeningActive() {
        return listeningActive;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger sessionCounter = new AtomicInteger(0);

    private VoiceAudioRecorder activeRecorder;
    private SpeechRecognizer delegateRecognizer;
    private boolean beginningReported = false;

    private boolean isCallerAuthorizedForMic() {
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.myUid() || callingUid == Process.SYSTEM_UID || callingUid == 2000) {
            return true;
        }
        return checkPermission(Manifest.permission.RECORD_AUDIO, Binder.getCallingPid(), callingUid)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isCallbackCallerAuthorized(Callback listener) {
        if (listener == null) {
            return true;
        }
        int callbackUid = listener.getCallingUid();
        if (callbackUid <= 0
                || callbackUid == Process.myUid()
                || callbackUid == Process.SYSTEM_UID
                || callbackUid == 2000) {
            return true;
        }
        PackageManager pm = getPackageManager();
        if (pm == null) {
            return false;
        }
        String[] pkgs = pm.getPackagesForUid(callbackUid);
        if (pkgs == null || pkgs.length == 0) {
            return false;
        }
        for (String pkg : pkgs) {
            if (pkg != null && pm.checkPermission(Manifest.permission.RECORD_AUDIO, pkg)
                    == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onStartListening(Intent recognizerIntent, final Callback listener) {
        if (listener == null) {
            return;
        }

        if (!isCallerAuthorizedForMic()
                || !isCallbackCallerAuthorized(listener)
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendErrorSafely(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS);
            return;
        }

        cancelCurrentSession();

        final int sessionId = sessionCounter.incrementAndGet();
        beginningReported = false;

        String voiceMode = readVoiceMode();
        ComponentName extService = "cloud_key".equals(voiceMode)
                ? null
                : TvHardwareProfiler.findExternalRecognitionService(this);
        if (extService != null) {
            startDelegateListening(sessionId, extService, recognizerIntent, listener, voiceMode);
            return;
        }

        if (!"on_tv".equals(voiceMode) && VoiceSttClient.hasConfiguredCloudStt(this)) {
            startCloudRecorderSession(sessionId, listener);
            return;
        }

        sendErrorSafely(listener, SpeechRecognizer.ERROR_CLIENT);
    }

    private String readVoiceMode() {
        try {
            SharedPreferences prefs = getSharedPreferences(TvHardwareProfiler.PREFS_NAME, Context.MODE_PRIVATE);
            return prefs != null ? Objects.toString(prefs.getString("voice_mode", "auto"), "auto").trim() : "auto";
        } catch (Throwable t) {
            Log.d(TAG, "readVoiceMode fallback: " + t.getMessage());
            return "auto";
        }
    }

    private void startDelegateListening(
            final int sessionId,
            final ComponentName extService,
            final Intent recognizerIntent,
            final Callback listener,
            final String voiceMode) {
        try {
            delegateRecognizer = SpeechRecognizer.createSpeechRecognizer(this, extService);
            delegateRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    if (sessionId == sessionCounter.get()) {
                        forwardReady(listener, params);
                    }
                }

                @Override
                public void onBeginningOfSpeech() {
                    if (sessionId == sessionCounter.get() && !beginningReported) {
                        beginningReported = true;
                        forwardBeginning(listener);
                    }
                }

                @Override
                public void onRmsChanged(float rmsdB) {
                    if (sessionId == sessionCounter.get()) {
                        forwardRms(listener, rmsdB);
                    }
                }

                @Override
                public void onBufferReceived(byte[] buffer) {
                    if (sessionId == sessionCounter.get() && buffer != null) {
                        forwardBuffer(listener, buffer);
                    }
                }

                @Override
                public void onEndOfSpeech() {
                    if (sessionId == sessionCounter.get()) {
                        forwardEnd(listener);
                    }
                }

                @Override
                public void onError(int error) {
                    if (sessionId != sessionCounter.get()) {
                        return;
                    }
                    listeningActive = false;
                    destroyDelegateRecognizer();
                    if (!"on_tv".equals(voiceMode)
                            && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            && VoiceSttClient.hasConfiguredCloudStt(VnRecognitionService.this)) {
                        startCloudRecorderSession(sessionId, listener);
                        return;
                    }
                    sendErrorSafely(listener, error);
                }

                @Override
                public void onResults(Bundle results) {
                    if (sessionId != sessionCounter.get()) {
                        return;
                    }
                    listeningActive = false;
                    destroyDelegateRecognizer();
                    forwardResults(listener, results);
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    if (sessionId == sessionCounter.get() && partialResults != null) {
                        forwardPartial(listener, partialResults);
                    }
                }

                @Override
                public void onEvent(int eventType, Bundle params) {
                }
            });
            listeningActive = true;
            Intent targetIntent = recognizerIntent != null ? new Intent(recognizerIntent) : new Intent();
            targetIntent.putExtra("android.speech.extra.LANGUAGE", "vi-VN");
            delegateRecognizer.startListening(targetIntent);
        } catch (Throwable t) {
            Log.w(TAG, "External delegate failed: " + t.getMessage());
            listeningActive = false;
            destroyDelegateRecognizer();
            if (!"on_tv".equals(voiceMode) && VoiceSttClient.hasConfiguredCloudStt(this)) {
                startCloudRecorderSession(sessionId, listener);
            } else {
                sendErrorSafely(listener, SpeechRecognizer.ERROR_CLIENT);
            }
        }
    }

    private void startCloudRecorderSession(final int sessionId, final Callback listener) {
        try {
            listener.readyForSpeech(new Bundle());
        } catch (RemoteException re) {
            Log.w(TAG, "readyForSpeech RemoteException: " + re.getMessage());
            return;
        }

        activeRecorder = new VoiceAudioRecorder(this, mainHandler, new VoiceAudioRecorder.Listener() {
            @Override
            public void onSpeechStart() {
                if (sessionId != sessionCounter.get() || beginningReported) {
                    return;
                }
                beginningReported = true;
                forwardBeginning(listener);
            }

            @Override
            public void onRmsChanged(float normalizedRms) {
                if (sessionId != sessionCounter.get()) {
                    return;
                }
                forwardRms(listener, -2.0f + (normalizedRms * 12.0f));
            }

            @Override
            public void onRecordingComplete(byte[] pcm16kBytes, int durationMs) {
                if (sessionId != sessionCounter.get()) {
                    return;
                }
                listeningActive = false;
                activeRecorder = null;
                if (!beginningReported) {
                    beginningReported = true;
                    forwardBeginning(listener);
                }
                forwardEnd(listener);
                runAsyncStt(sessionId, pcm16kBytes, listener);
            }

            @Override
            public void onError(String message) {
                if (sessionId != sessionCounter.get()) {
                    return;
                }
                listeningActive = false;
                activeRecorder = null;
                sendErrorSafely(listener, SpeechRecognizer.ERROR_NO_MATCH);
            }
        });
        listeningActive = true;
        activeRecorder.start();
    }

    private void runAsyncStt(final int sessionId, final byte[] pcm16kBytes, final Callback listener) {
        Thread worker = new Thread(() -> {
            try {
                final String transcript = VoiceSttClient.transcribeWithUserConfig(VnRecognitionService.this, pcm16kBytes);
                mainHandler.post(() -> {
                    if (sessionId != sessionCounter.get()) {
                        return;
                    }
                    if (transcript == null || transcript.trim().isEmpty()) {
                        sendErrorSafely(listener, SpeechRecognizer.ERROR_NO_MATCH);
                        return;
                    }
                    Bundle resultsBundle = new Bundle();
                    ArrayList<String> matches = new ArrayList<>(1);
                    matches.add(transcript.trim());
                    resultsBundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, matches);
                    resultsBundle.putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, new float[]{0.95f});
                    forwardResults(listener, resultsBundle);
                });
            } catch (IOException ioe) {
                Log.w(TAG, "RecognitionService STT error: " + ioe.getMessage());
                final int code = "NO_STT_KEY_CONFIGURED".equals(ioe.getMessage())
                        ? SpeechRecognizer.ERROR_CLIENT
                        : SpeechRecognizer.ERROR_NETWORK;
                mainHandler.post(() -> {
                    if (sessionId == sessionCounter.get()) {
                        sendErrorSafely(listener, code);
                    }
                });
            } catch (Throwable t) {
                Log.e(TAG, "RecognitionService unexpected error: " + t.getMessage());
                mainHandler.post(() -> {
                    if (sessionId == sessionCounter.get()) {
                        sendErrorSafely(listener, SpeechRecognizer.ERROR_NO_MATCH);
                    }
                });
            }
        }, "TvHome-VnRecSvc-STT");
        worker.start();
    }

    @Override
    protected void onStopListening(Callback listener) {
        if (!isCallerAuthorizedForMic() || !isCallbackCallerAuthorized(listener)) {
            return;
        }
        if (delegateRecognizer != null) {
            try {
                delegateRecognizer.stopListening();
            } catch (Throwable t) {
                Log.w(TAG, "delegate stopListening warning: " + t.getMessage());
            }
        }
        if (activeRecorder != null) {
            activeRecorder.finishEarly();
        }
    }

    @Override
    protected void onCancel(Callback listener) {
        if (!isCallerAuthorizedForMic() || !isCallbackCallerAuthorized(listener)) {
            return;
        }
        cancelCurrentSession();
    }

    @Override
    public void onDestroy() {
        listeningActive = false;
        cancelCurrentSession();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void cancelCurrentSession() {
        listeningActive = false;
        sessionCounter.incrementAndGet();
        destroyDelegateRecognizer();
        if (activeRecorder != null) {
            activeRecorder.cancel();
            activeRecorder = null;
        }
    }

    private void destroyDelegateRecognizer() {
        if (delegateRecognizer != null) {
            try {
                delegateRecognizer.cancel();
            } catch (Throwable t) {
                Log.d(TAG, "delegate cancel ignored: " + t.getMessage());
            }
            try {
                delegateRecognizer.destroy();
            } catch (Throwable t) {
                Log.d(TAG, "delegate destroy ignored: " + t.getMessage());
            }
            delegateRecognizer = null;
        }
    }

    private static void forwardReady(Callback listener, Bundle params) {
        try {
            listener.readyForSpeech(params != null ? params : new Bundle());
        } catch (RemoteException re) {
            Log.w(TAG, "readyForSpeech RemoteException: " + re.getMessage());
        }
    }

    private static void forwardBeginning(Callback listener) {
        try {
            listener.beginningOfSpeech();
        } catch (RemoteException re) {
            Log.w(TAG, "beginningOfSpeech RemoteException: " + re.getMessage());
        }
    }

    private static void forwardRms(Callback listener, float rmsdB) {
        try {
            listener.rmsChanged(rmsdB);
        } catch (RemoteException re) {
            Log.w(TAG, "rmsChanged RemoteException: " + re.getMessage());
        }
    }

    private static void forwardBuffer(Callback listener, byte[] buf) {
        try {
            listener.bufferReceived(buf);
        } catch (RemoteException re) {
            Log.w(TAG, "bufferReceived RemoteException: " + re.getMessage());
        }
    }

    private static void forwardEnd(Callback listener) {
        try {
            listener.endOfSpeech();
        } catch (RemoteException re) {
            Log.w(TAG, "endOfSpeech RemoteException: " + re.getMessage());
        }
    }

    private static void forwardPartial(Callback listener, Bundle partial) {
        try {
            listener.partialResults(partial);
        } catch (RemoteException re) {
            Log.w(TAG, "partialResults RemoteException: " + re.getMessage());
        }
    }

    private static void forwardResults(Callback listener, Bundle results) {
        try {
            listener.results(results != null ? results : new Bundle());
        } catch (RemoteException re) {
            Log.w(TAG, "results RemoteException: " + re.getMessage());
        }
    }

    private static void sendErrorSafely(Callback listener, int errorCode) {
        if (listener == null) {
            return;
        }
        try {
            listener.error(errorCode);
        } catch (RemoteException re) {
            Log.w(TAG, "error(" + errorCode + ") RemoteException: " + re.getMessage());
        }
    }
}
