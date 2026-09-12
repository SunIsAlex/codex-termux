package dev.codex.phonebridge;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

/** Local reference to the dedicated ChatGPT conversation. */
public final class SessionConfig {
    private static final String PREFERENCES = "dedicatedChat";
    private static final String CHAT_URL = "chatUrl";
    private static final String SESSION_ID = "sessionId";
    private static final String INITIALIZED = "initialized";
    private static final String INITIALIZATION_VERSION = "initializationVersion";
    private static final int CURRENT_INITIALIZATION_VERSION = 5;

    private final SharedPreferences preferences;

    public SessionConfig(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public String chatUrl() {
        return preferences.getString(CHAT_URL, "");
    }

    public String sessionId() {
        String existing = preferences.getString(SESSION_ID, "");
        if (!existing.isEmpty()) {
            return existing;
        }
        String created = UUID.randomUUID().toString();
        preferences.edit().putString(SESSION_ID, created).apply();
        return created;
    }

    public boolean isInitialized() {
        return !chatUrl().isEmpty()
                && preferences.getInt(INITIALIZATION_VERSION, 0)
                        == CURRENT_INITIALIZATION_VERSION;
    }

    public void markInitialized() {
        preferences.edit()
                .putBoolean(INITIALIZED, true)
                .putInt(INITIALIZATION_VERSION, CURRENT_INITIALIZATION_VERSION)
                .apply();
    }

    public void saveChatUrl(String input) {
        String normalized = normalizeAndValidate(input);
        String previous = chatUrl();
        SharedPreferences.Editor editor = preferences.edit().putString(CHAT_URL, normalized);
        if (!normalized.equals(previous)) {
            editor.putString(SESSION_ID, UUID.randomUUID().toString());
            editor.putBoolean(INITIALIZED, false);
            editor.putInt(INITIALIZATION_VERSION, 0);
        }
        editor.apply();
    }

    public static String normalizeAndValidate(String input) {
        return DedicatedChatUrl.normalize(input);
    }
}
