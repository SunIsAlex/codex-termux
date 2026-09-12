package dev.codex.phonebridge;

import java.util.Objects;

/** A locally validated action. No field can contain an executable command. */
public final class PhoneAction {
    public enum Kind {
        CLARIFY,
        OPEN_APP,
        SET_VOLUME,
        SET_BRIGHTNESS,
        MEDIA_PLAY_PAUSE,
        TOGGLE_FLASHLIGHT,
        OPEN_SETTINGS_PAGE,
        LOCK_SCREEN,
        LIST_APPS,
        START_INTENT
    }

    private final Kind kind;
    private final String text;
    private final int number;
    private final IntentSpec intent;

    private PhoneAction(Kind kind, String text, int number, IntentSpec intent) {
        this.kind = Objects.requireNonNull(kind);
        this.text = text;
        this.number = number;
        this.intent = intent;
    }

    public static PhoneAction text(Kind kind, String value) {
        return new PhoneAction(kind, Objects.requireNonNull(value), 0, null);
    }

    public static PhoneAction number(Kind kind, int value) {
        return new PhoneAction(kind, null, value, null);
    }

    public static PhoneAction simple(Kind kind) {
        return new PhoneAction(kind, null, 0, null);
    }

    public static PhoneAction intent(IntentSpec value) {
        return new PhoneAction(Kind.START_INTENT, null, 0, Objects.requireNonNull(value));
    }

    public Kind kind() {
        return kind;
    }

    public String text() {
        return text;
    }

    public int number() {
        return number;
    }

    public IntentSpec intent() {
        return intent;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PhoneAction)) {
            return false;
        }
        PhoneAction action = (PhoneAction) other;
        return number == action.number
                && kind == action.kind
                && Objects.equals(text, action.text)
                && Objects.equals(intent, action.intent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, text, number, intent);
    }

    @Override
    public String toString() {
        return "PhoneAction{" + kind + ", text=" + text + ", number=" + number + ", intent=" + intent + "}";
    }
}
