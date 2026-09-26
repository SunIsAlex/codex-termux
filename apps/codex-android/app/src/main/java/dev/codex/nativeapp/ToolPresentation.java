package dev.codex.nativeapp;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** Bounded, human-readable tool content shared by live cards and restored history. */
final class ToolPresentation {
    static final int LIMIT = 24000;
    enum Tone { RUNNING, SUCCESS, ERROR, NEUTRAL }
    final String title, symbol, status, summary, metadata, details;
    final Tone tone;
    final boolean diff;
    private ToolPresentation(String title, String symbol, String status, Tone tone,
            String summary, String metadata, String details, boolean diff) {
        this.title = head(title, 100); this.symbol = symbol; this.status = head(status, 60); this.tone = tone;
        this.summary = head(summary, 240); this.metadata = head(metadata, 500);
        this.details = head(details, LIMIT); this.diff = diff;
    }
    static boolean isTool(String type) {
        return !type.equals("userMessage") && !type.equals("agentMessage") && !type.equals("plan") && !type.equals("reasoning");
    }
    static ToolPresentation from(JSONObject item) {
        String type = text(item, "type"), title = human(type), symbol = "◇";
        String state = text(item, "status"), summary = "", metadata = "";
        Tone tone = Tone.NEUTRAL; String status = "Details";
        if (state.equals("inProgress") || state.equals("running") || state.equals("searching")) { tone = Tone.RUNNING; status = "Running"; }
        else if (state.equals("completed")) { tone = Tone.SUCCESS; status = "Done"; }
        else if (state.equals("failed") || state.equals("errored")) { tone = Tone.ERROR; status = "Failed"; }
        else if (state.equals("declined") || state.equals("cancelled") || state.equals("canceled")) { status = human(state); }
        else if (!state.isEmpty()) status = human(state);
        if ((!item.isNull("exitCode") && item.optInt("exitCode") != 0)
                || (!item.isNull("success") && !item.optBoolean("success")) || !item.isNull("error")) {
            tone = Tone.ERROR; status = "Failed";
        }
        StringBuilder detail = new StringBuilder();
        switch (type) {
            case "commandExecution":
                title = "Terminal"; symbol = ">_"; summary = text(item, "command");
                metadata = text(item, "cwd"); section(detail, "Command", summary);
                section(detail, "Output", text(item, "aggregatedOutput"));
                if (!item.isNull("exitCode")) metadata = join(metadata, "exit " + item.optInt("exitCode"));
                break;
            case "fileChange":
                title = "File changes"; symbol = "±"; JSONArray changes = item.optJSONArray("changes");
                int count = changes == null ? 0 : changes.length();
                summary = count + (count == 1 ? " file" : " files");
                if (count > 0) {
                    JSONObject first = changes.optJSONObject(0);
                    summary = text(first, "path") + (count > 1 ? "  +" + (count - 1) + " more" : "");
                    for (int i = 0; i < Math.min(count, 20) && detail.length() < LIMIT; i++) {
                        JSONObject change = changes.optJSONObject(i); if (change == null) continue;
                        JSONObject kind = change.optJSONObject("kind");
                        String action = kind == null ? "Update" : human(text(kind, "type"));
                        section(detail, action + " · " + text(change, "path"), text(change, "diff"));
                        if (kind != null && !kind.isNull("movePath")) section(detail, "Move to", text(kind, "movePath"));
                    }
                    if (count > 20) section(detail, "Additional files", (count - 20) + " not shown in this preview");
                }
                break;
            case "mcpToolCall":
            case "dynamicToolCall":
                title = text(item, "tool"); if (title.isEmpty()) title = "Tool call";
                symbol = "⚙"; summary = text(item, "server");
                if (summary.isEmpty()) summary = text(item, "namespace");
                section(detail, "Arguments", pretty(item.opt("arguments")));
                section(detail, "Result", pretty(item.opt("result")));
                section(detail, "Content", pretty(item.opt("contentItems")));
                break;
            case "functionCallOutput":
                title = text(item, "name"); if (title.isEmpty()) title = "Tool result";
                symbol = "↳"; summary = text(item, "namespace"); status = "Result";
                section(detail, "Output", pretty(item.opt("output"))); break;
            case "webSearch":
                title = "Web search"; symbol = "⌕"; summary = text(item, "query");
                section(detail, "Search", summary); section(detail, "Action", pretty(item.opt("action")));
                section(detail, "Results", pretty(item.opt("results"))); break;
            case "collabAgentToolCall":
                title = "Agent · " + human(text(item, "tool")); symbol = "◎";
                summary = text(item, "prompt"); section(detail, "Task", summary);
                section(detail, "Agents", pretty(item.opt("agentsStates"))); break;
            case "imageView":
                title = "View image"; symbol = "▧"; summary = text(item, "path"); section(detail, "Path", summary); break;
            case "contextCompaction":
                title = "Context compacted"; symbol = "≋"; summary = "Earlier context summarized"; break;
            default:
                summary = text(item, "text"); section(detail, "Details", pretty(item)); break;
        }
        section(detail, "Error", pretty(item.opt("error")));
        if (!item.isNull("durationMs")) {
            long ms = item.optLong("durationMs");
            metadata = join(metadata, ms < 1000 ? ms + " ms" : String.format(Locale.ROOT, "%.1f s", ms / 1000.0));
        }
        if (summary.isEmpty()) summary = tone == Tone.RUNNING ? "Working…" : "Tap to inspect details";
        return new ToolPresentation(title, symbol, status, tone, summary, metadata, detail.toString(), type.equals("fileChange"));
    }
    ToolPresentation appendOutput(String delta) {
        boolean hasOutput = details.startsWith("Output\n") || details.contains("\n\nOutput\n") || details.startsWith("[Earlier output omitted]");
        String output = details + (hasOutput ? "" : (details.isEmpty() ? "" : "\n\n") + "Output\n") + delta;
        if (output.length() > LIMIT) output = "[Earlier output omitted]\n" + output.substring(output.length() - LIMIT + 26);
        return new ToolPresentation(title, symbol, status, tone, summary, metadata, output, diff);
    }
    ToolPresentation progress(String message) {
        return new ToolPresentation(title, symbol, status, tone, message, metadata, details, diff);
    }
    private static void section(StringBuilder out, String label, String value) {
        if (value.isEmpty() || out.length() >= LIMIT) return;
        if (out.length() > 0) out.append("\n\n");
        out.append(label).append('\n').append(head(value, LIMIT - Math.min(out.length(), LIMIT)));
    }
    private static String pretty(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        try {
            if (value instanceof JSONObject) return ((JSONObject) value).toString(2);
            if (value instanceof JSONArray) return ((JSONArray) value).toString(2);
        } catch (Exception ignored) {}
        return value.toString();
    }
    private static String text(JSONObject item, String key) { return item == null || item.isNull(key) ? "" : item.optString(key, ""); }
    private static String join(String a, String b) { return a.isEmpty() ? b : a + " · " + b; }
    private static String head(String value, int max) { return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…"; }
    private static String human(String value) {
        String words = value.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
        return words.isEmpty() ? "Activity" : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
