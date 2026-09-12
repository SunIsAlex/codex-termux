package dev.codex.phonebridge;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Locale;

/** Owns one Android speech-recognition session at a time. */
public final class SpeechInputController implements RecognitionListener {
    public interface Callback {
        void onStatus(String message);

        void onTranscript(String transcript);
    }

    private final Context context;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean active;
    private boolean holdToTalk;
    private long startedAtMs;

    public SpeechInputController(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    /** Starts listening. Returns false without consuming a hardware key when unavailable. */
    public boolean start(boolean holdToTalk) {
        if (active) {
            callback.onStatus("语音识别已经在进行中");
            return false;
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            callback.onStatus("请先打开 Phone Bridge 并授予麦克风权限");
            return false;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            callback.onStatus("系统没有可用的语音识别服务");
            return false;
        }
        destroyRecognizer();
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(this);

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        try {
            this.holdToTalk = holdToTalk;
            active = true;
            startedAtMs = SystemClock.elapsedRealtime();
            recognizer.startListening(intent);
            callback.onStatus(holdToTalk ? "正在听，松开任一音量键结束…" : "正在听…");
            return true;
        } catch (RuntimeException error) {
            finish();
            callback.onStatus("无法启动系统语音识别服务");
            return false;
        }
    }

    public void stop() {
        if (active && recognizer != null) {
            long remainingMs = 300 - (SystemClock.elapsedRealtime() - startedAtMs);
            if (remainingMs > 0) {
                handler.postDelayed(this::stop, remainingMs);
                return;
            }
            callback.onStatus("正在识别…");
            recognizer.stopListening();
        }
    }

    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        active = false;
        destroyRecognizer();
    }

    @Override
    public void onReadyForSpeech(Bundle parameters) {
        callback.onStatus(holdToTalk ? "请说出操作，松开按键结束" : "请说出一个手机操作");
    }

    @Override
    public void onBeginningOfSpeech() {}

    @Override
    public void onRmsChanged(float rms) {}

    @Override
    public void onBufferReceived(byte[] buffer) {}

    @Override
    public void onEndOfSpeech() {
        callback.onStatus("正在识别…");
    }

    @Override
    public void onError(int error) {
        finish();
        callback.onStatus(recognitionErrorMessage(error));
    }

    @Override
    public void onResults(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        finish();
        if (matches == null || matches.isEmpty()) {
            callback.onStatus("没有识别到语音");
            return;
        }
        callback.onTranscript(matches.get(0));
    }

    @Override
    public void onPartialResults(Bundle partialResults) {}

    @Override
    public void onEvent(int eventType, Bundle parameters) {}

    private void finish() {
        active = false;
        destroyRecognizer();
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    private static String recognitionErrorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "无法读取麦克风";
            case SpeechRecognizer.ERROR_CLIENT:
                return "语音识别客户端状态异常，请重新按键";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "没有麦克风权限";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "语音识别网络不可用";
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "没有识别到清晰语音";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "语音识别服务正忙，请稍后重试";
            case SpeechRecognizer.ERROR_SERVER:
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                return "系统语音识别服务暂时不可用";
            default:
                return "语音识别失败，错误码 " + error;
        }
    }
}
