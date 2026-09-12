package dev.codex.phonebridge;

import android.content.Context;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** Internal, parameter-free audit log. Spoken text and model responses are never persisted. */
public final class AuditLog {
    private AuditLog() {}

    public static synchronized void record(Context context, PhoneAction.Kind kind, boolean success) {
        try {
            JSONObject entry = new JSONObject();
            entry.put("timestampMs", System.currentTimeMillis());
            entry.put("action", kind.name());
            entry.put("success", success);
            byte[] line = (entry.toString() + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream output = context.openFileOutput("audit.jsonl", Context.MODE_APPEND)) {
                output.write(line);
            }
        } catch (IOException | JSONException ignored) {
            // Logging must never turn a completed action into a retry.
        }
    }
}
