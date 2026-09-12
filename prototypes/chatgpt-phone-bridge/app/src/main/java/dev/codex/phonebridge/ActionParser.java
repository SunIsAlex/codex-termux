package dev.codex.phonebridge;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** Strict parser for nonce-bound PHONE_ACTION_V1 replies. */
public final class ActionParser {
    public static final String MARKER = "PHONE_ACTION_V1";
    private static final int MAX_RESPONSE_CHARS = 128_000;
    private static final int MAX_OBJECT_CHARS = 8_192;

    private ActionParser() {}

    public static PhoneAction parse(String response, String expectedNonce) {
        if (response == null || expectedNonce == null || expectedNonce.isEmpty()) {
            throw new IllegalArgumentException("Missing response or nonce");
        }
        if (response.length() > MAX_RESPONSE_CHARS) {
            throw new IllegalArgumentException("Response is too large");
        }

        PhoneAction match = null;
        int searchFrom = 0;
        while (true) {
            int marker = response.indexOf(MARKER, searchFrom);
            if (marker < 0) {
                break;
            }
            int objectStart = response.indexOf('{', marker + MARKER.length());
            if (objectStart < 0) {
                break;
            }
            int objectEnd = findObjectEnd(response, objectStart);
            if (objectEnd < 0) {
                searchFrom = objectStart + 1;
                continue;
            }
            String candidate = response.substring(objectStart, objectEnd + 1);
            searchFrom = objectEnd + 1;
            if (candidate.length() > MAX_OBJECT_CHARS) {
                continue;
            }
            try {
                PhoneAction parsed = parseCandidate(candidate, expectedNonce);
                if (parsed != null) {
                    if (match != null) {
                        throw new IllegalArgumentException("Multiple valid actions in one reply");
                    }
                    match = parsed;
                }
            } catch (JSONException | IllegalStateException ignored) {
                // The visible user prompt also contains the marker and a schema template.
                // Ignore malformed/non-matching candidates and keep looking for the reply.
            }
        }
        if (match == null) {
            throw new IllegalArgumentException("No valid action for this request");
        }
        return match;
    }

    private static PhoneAction parseCandidate(String raw, String expectedNonce) throws JSONException {
        requireTokenOnce(raw, "nonce");
        requireTokenOnce(raw, "action");
        requireTokenOnce(raw, "arguments");

        JSONObject object = new JSONObject(raw);
        requireKeys(object, "nonce", "action", "arguments");
        if (!expectedNonce.equals(object.getString("nonce"))) {
            return null;
        }
        String action = object.getString("action");
        JSONObject arguments = object.getJSONObject("arguments");
        switch (action) {
            case "clarify":
                requireTokenOnce(raw, "message");
                requireKeys(arguments, "message");
                return PhoneAction.text(
                        PhoneAction.Kind.CLARIFY,
                        boundedText(arguments.getString("message"), 1, 240));
            case "open_app":
                requireTokenOnce(raw, "appName");
                requireKeys(arguments, "appName");
                return PhoneAction.text(
                        PhoneAction.Kind.OPEN_APP,
                        boundedText(arguments.getString("appName"), 1, 80));
            case "set_volume":
                requireTokenOnce(raw, "level");
                requireKeys(arguments, "level");
                return PhoneAction.number(
                        PhoneAction.Kind.SET_VOLUME,
                        boundedInt(arguments, "level", 0, 100));
            case "set_brightness":
                requireTokenOnce(raw, "level");
                requireKeys(arguments, "level");
                return PhoneAction.number(
                        PhoneAction.Kind.SET_BRIGHTNESS,
                        boundedInt(arguments, "level", 1, 100));
            case "media_play_pause":
                requireKeys(arguments);
                return PhoneAction.simple(PhoneAction.Kind.MEDIA_PLAY_PAUSE);
            case "toggle_flashlight":
                requireKeys(arguments);
                return PhoneAction.simple(PhoneAction.Kind.TOGGLE_FLASHLIGHT);
            case "open_settings_page":
                requireTokenOnce(raw, "page");
                requireKeys(arguments, "page");
                String page = arguments.getString("page");
                if (!page.equals("wifi")
                        && !page.equals("bluetooth")
                        && !page.equals("display")
                        && !page.equals("accessibility")) {
                    throw new IllegalStateException("Unknown settings page");
                }
                return PhoneAction.text(PhoneAction.Kind.OPEN_SETTINGS_PAGE, page);
            case "lock_screen":
                requireKeys(arguments);
                return PhoneAction.simple(PhoneAction.Kind.LOCK_SCREEN);
            case "list_apps":
                requireTokenOnce(raw, "query");
                requireKeys(arguments, "query");
                return PhoneAction.text(
                        PhoneAction.Kind.LIST_APPS,
                        boundedText(arguments.getString("query"), 0, 80));
            case "start_intent":
                requireKeys(
                        arguments,
                        "intentAction",
                        "dataUri",
                        "packageName",
                        "componentName",
                        "extras");
                return PhoneAction.intent(parseIntent(arguments));
            default:
                throw new IllegalStateException("Unknown action");
        }
    }

    private static IntentSpec parseIntent(JSONObject arguments) throws JSONException {
        String action = nullableText(arguments, "intentAction", 160);
        String data = sanitizeDataUri(nullableText(arguments, "dataUri", 2_048));
        String packageName = nullableText(arguments, "packageName", 255);
        String component = nullableText(arguments, "componentName", 512);
        if (action == null && component == null) {
            throw new IllegalStateException("Intent needs an action or component");
        }
        if (action != null && !action.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalStateException("Invalid Intent action");
        }
        if (packageName != null && !packageName.matches("[A-Za-z0-9._]+")) {
            throw new IllegalStateException("Invalid package name");
        }
        if (component != null && !component.matches("[A-Za-z0-9._$]+/[A-Za-z0-9._$]+")) {
            throw new IllegalStateException("Invalid component name");
        }

        JSONObject rawExtras = arguments.getJSONObject("extras");
        if (rawExtras.length() > 20) {
            throw new IllegalStateException("Too many Intent extras");
        }
        LinkedHashMap<String, Object> extras = new LinkedHashMap<>();
        Iterator<String> keys = rawExtras.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.matches("[A-Za-z0-9._-]{1,160}")) {
                throw new IllegalStateException("Invalid extra name");
            }
            Object value = rawExtras.get(key);
            if (value instanceof String) {
                extras.put(key, boundedText((String) value, 0, 500));
            } else if (value instanceof Boolean || value instanceof Integer) {
                extras.put(key, value);
            } else if (value instanceof Long) {
                long number = (Long) value;
                if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
                    throw new IllegalStateException("Intent integer extra is out of range");
                }
                extras.put(key, (int) number);
            } else {
                throw new IllegalStateException("Unsupported Intent extra type");
            }
        }
        return new IntentSpec(action, data, packageName, component, extras);
    }

    private static String sanitizeDataUri(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder clean = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == 0xFFFD || Character.getType(codePoint) == Character.FORMAT) {
                continue;
            }
            clean.appendCodePoint(codePoint);
        }
        String result = clean.toString().trim();
        String lower = result.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            int asciiEnd = 0;
            while (asciiEnd < result.length()) {
                char character = result.charAt(asciiEnd);
                if (character < 0x21 || character > 0x7E) {
                    break;
                }
                asciiEnd++;
            }
            result = result.substring(0, asciiEnd);
        }
        return result.isEmpty() ? null : result;
    }

    private static String nullableText(JSONObject object, String name, int max) throws JSONException {
        Object value = object.get(name);
        if (value == JSONObject.NULL) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new IllegalStateException("Expected string or null");
        }
        return boundedText((String) value, 0, max);
    }

    private static String boundedText(String value, int min, int max) {
        String text = value == null ? "" : value.trim();
        if (text.length() < min || text.length() > max || containsControlCharacter(text)) {
            throw new IllegalStateException("Invalid text argument");
        }
        return text;
    }

    private static int boundedInt(JSONObject object, String name, int min, int max)
            throws JSONException {
        Object raw = object.get(name);
        if (!(raw instanceof Integer) && !(raw instanceof Long)) {
            throw new IllegalStateException("Expected integer argument");
        }
        long value = ((Number) raw).longValue();
        if (value < min || value > max) {
            throw new IllegalStateException("Integer is out of range");
        }
        return (int) value;
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < 0x20 && character != '\n' && character != '\t') {
                return true;
            }
        }
        return false;
    }

    private static void requireKeys(JSONObject object, String... expected) {
        Set<String> actual = new HashSet<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            actual.add(keys.next());
        }
        Set<String> wanted = new HashSet<>();
        for (String key : expected) {
            wanted.add(key);
        }
        if (!actual.equals(wanted)) {
            throw new IllegalStateException("Unexpected or missing fields");
        }
    }

    private static void requireTokenOnce(String raw, String key) {
        String token = "\"" + key + "\"";
        int first = raw.indexOf(token);
        if (first < 0 || raw.indexOf(token, first + token.length()) >= 0) {
            throw new IllegalStateException("Missing or duplicate field");
        }
    }

    private static int findObjectEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char character = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
                if (depth < 0) {
                    return -1;
                }
            }
        }
        return -1;
    }
}
