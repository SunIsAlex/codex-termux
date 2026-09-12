package dev.codex.phonebridge;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import java.util.Locale;

public final class VoiceFeedback {
    private static TextToSpeech tts;
    private static boolean ready;
    private static String queued;

    private VoiceFeedback() {}

    public static synchronized void initialize(Context context) {
        if (tts != null) {
            return;
        }
        tts = new TextToSpeech(
                context.getApplicationContext(),
                status -> {
                    synchronized (VoiceFeedback.class) {
                        ready = status == TextToSpeech.SUCCESS;
                        if (ready) {
                            tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                            if (queued != null) {
                                speakNow(queued);
                                queued = null;
                            }
                        }
                    }
                });
    }

    public static synchronized void speak(Context context, String message) {
        initialize(context);
        if (!ready) {
            queued = message;
            return;
        }
        speakNow(message);
    }

    private static void speakNow(String message) {
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "phone-bridge-result");
    }
}
