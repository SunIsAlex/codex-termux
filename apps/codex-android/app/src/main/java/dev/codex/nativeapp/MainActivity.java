package dev.codex.nativeapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;
import static dev.codex.nativeapp.RpcClient.object;

/** Native conversation surface; all Codex work remains in the Termux service. */
public final class MainActivity extends Activity {
    private static final int INK = 0xff202b29, PAPER = 0xfff4f5f0, ACCENT = 0xff126b55;
    private SharedPreferences prefs;
    private RpcClient client;
    private TextView status, heading, project, error;
    private EditText prompt;
    private Button send, stop, older;
    private Spinner model;
    private LinearLayout messages;
    private ScrollView scroll;
    private final ArrayList<String> modelIds = new ArrayList<>();
    private final LinkedHashMap<String, TextView> rows = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> rowText = new LinkedHashMap<>();
    private final LinkedHashMap<String, JSONObject> requests = new LinkedHashMap<>();
    private AlertDialog approval;
    private String thread, turn, cursor, cwd;
    private boolean ready, busy, sending, syncing, uncertain, olderLoading;
    private int connectionGeneration, historyGeneration;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("codex", MODE_PRIVATE);
        thread = prefs.getString("thread", "");
        cwd = prefs.getString("cwd", "/data/data/com.termux/files/home");
        getWindow().setStatusBarColor(PAPER); getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root = column(); root.setBackgroundColor(PAPER); root.setFitsSystemWindows(true);
        root.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL);
        heading = label("Codex", 26); heading.setTypeface(null, Typeface.BOLD);
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(button("Sessions", this::sessions)); top.addView(button("Settings", this::settings)); root.addView(top);
        status = label("Not connected", 13); status.setTextColor(ACCENT); root.addView(status);
        project = label(cwd, 12); project.setMaxLines(1); root.addView(project);
        model = new Spinner(this); root.addView(model);
        older = button("Load earlier messages", () -> history(false)); older.setVisibility(View.GONE); root.addView(older);
        scroll = new ScrollView(this); scroll.setFillViewport(true); messages = column(); scroll.addView(messages);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        error = label("", 13); error.setTextColor(0xffa33126); root.addView(error);
        prompt = new EditText(this); prompt.setHint("What would you like to build?"); prompt.setTextSize(16);
        prompt.setMinLines(2); prompt.setMaxLines(5); prompt.setGravity(Gravity.TOP);
        prompt.setText(prefs.getString("draft", "")); root.addView(prompt);
        prompt.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { prefs.edit().putString("draft", s.toString()).apply(); }
            public void afterTextChanged(Editable text) {}
        });
        LinearLayout actions = new LinearLayout(this);
        actions.addView(button("New chat", this::newChat), new LinearLayout.LayoutParams(0, dp(52), 1));
        stop = button("Stop", this::interrupt); actions.addView(stop, new LinearLayout.LayoutParams(0, dp(52), 1));
        send = button("Send", this::send); send.setTextColor(0xffffffff);
        GradientDrawable primary = new GradientDrawable(); primary.setColor(ACCENT); primary.setCornerRadius(dp(18)); send.setBackground(primary);
        actions.addView(send, new LinearLayout.LayoutParams(0, dp(52), 1)); root.addView(actions);
        setContentView(root); empty(); controls();
    }
    @Override protected void onStart() { super.onStart(); connect(); }
    @Override protected void onStop() {
        connectionGeneration++; ready = false;
        if (client != null) { client.close(); client = null; }
        if (approval != null) approval.dismiss(); approval = null; requests.clear();
        super.onStop();
    }
    private void connect() {
        int generation = ++connectionGeneration;
        if (client != null) client.close();
        ready = false; syncing = false; sending = false; controls();
        String token = prefs.getString("token", "");
        if (!token.matches("[a-f0-9]{64}")) { status.setText("Open Settings to pair with Termux"); return; }
        client = new RpcClient(token, 8766, new RpcClient.Listener() {
            public void state(String text) { runOnUiThread(() -> {
                if (generation != connectionGeneration) return;
                ready = false; status.setText(text); controls();
            }); }
            public void event(JSONObject message) { runOnUiThread(() -> {
                if (generation == connectionGeneration) receive(message);
            }); }
        });
        client.start();
    }
    private void rpc(String method, JSONObject params, RpcClient.Reply reply) {
        if (client == null) { reply.complete(null, "Connect to Termux first"); return; }
        int generation = connectionGeneration;
        client.call(method, params, (result, failure) -> runOnUiThread(() -> {
            if (generation == connectionGeneration) reply.complete(result, failure);
        }));
    }
    private void receive(JSONObject message) {
        String method = message.optString("method"); JSONObject p = message.optJSONObject("params");
        if (p == null) p = object();
        if (method.equals("native/ready") || method.equals("bridge/sync")) {
            ready = p.optBoolean("ready", true); status.setText(ready ? "Connected · on this device" : "Codex is restarting…");
            JSONArray queued = p.optJSONArray("requests");
            requests.clear(); if (approval != null) approval.dismiss(); approval = null;
            if (queued != null) for (int i = 0; i < queued.length(); i++) queueRequest(queued.optJSONObject(i));
            if (ready) { models(); if (!thread.isEmpty()) restore(); else { uncertain = false; busy = false; } }
        } else if (method.startsWith("bridge/")) {
            ready = false; status.setText("Codex reconnecting…");
        } else if (message.has("id")) queueRequest(message);
        else if (method.equals("serverRequest/resolved")) {
            requests.remove(String.valueOf(p.opt("requestId")));
            if (approval != null) approval.dismiss(); approval = null; showRequest();
        } else if (thread.equals(p.optString("threadId"))) {
            if (method.equals("item/started") || method.equals("item/completed")) render(p.optJSONObject("item"), false);
            else if (method.equals("item/agentMessage/delta")) {
                String id = p.optString("itemId");
                render(object("id", id, "type", "agentMessage", "text", rowText.getOrDefault(id, "") + p.optString("delta")), false);
            } else if (method.equals("turn/started")) {
                JSONObject active = p.optJSONObject("turn"); if (active != null) turn = active.optString("id"); busy = true;
            } else if (method.equals("turn/completed")) {
                busy = false; turn = null;
                JSONObject completed = p.optJSONObject("turn");
                if (completed != null && completed.optJSONObject("error") != null) fail(completed.optJSONObject("error").optString("message"));
            } else if (method.equals("error")) {
                JSONObject detail = p.optJSONObject("error"); fail(detail == null ? p.toString() : detail.optString("message"));
            }
        }
        controls();
    }
    private void models() {
        rpc("model/list", object(), (result, failure) -> {
            if (failure != null) { fail(failure); return; }
            JSONArray data = result.optJSONArray("data"); ArrayList<String> names = new ArrayList<>();
            modelIds.clear(); modelIds.add(""); names.add("Use configured model");
            if (data != null) for (int i = 0; i < data.length(); i++) {
                JSONObject entry = data.optJSONObject(i); modelIds.add(entry.optString("id")); names.add(entry.optString("displayName", entry.optString("id")));
            }
            model.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
            int selected = modelIds.indexOf(prefs.getString("model", "")); model.setSelection(Math.max(0, selected));
        });
    }
    private void restore() {
        syncing = true; sending = false; controls(); int generation = ++historyGeneration;
        rpc("thread/resume", object("threadId", thread, "excludeTurns", true,
            "initialTurnsPage", object("limit", 20, "sortDirection", "desc", "itemsView", "summary")), (result, failure) -> {
            if (generation != historyGeneration) return;
            if (failure != null) { syncing = false; uncertain = true; fail(failure + " · Use New chat if this session is owned elsewhere."); controls(); return; }
            JSONObject value = result.optJSONObject("thread"); cwd = value.optString("cwd", cwd); project.setText(cwd);
            busy = false; turn = null;
            JSONObject page = result.optJSONObject("initialTurnsPage"); JSONArray turns = page == null ? null : page.optJSONArray("data");
            if (turns != null) for (int i = 0; i < turns.length(); i++) {
                JSONObject t = turns.optJSONObject(i); if ("inProgress".equals(t.optString("status"))) { busy = true; turn = t.optString("id"); }
            }
            history(true);
        });
    }
    private void history(boolean reset) {
        if (olderLoading && !reset) return;
        int generation = historyGeneration; String target = thread;
        if (reset) { rows.clear(); rowText.clear(); messages.removeAllViews(); cursor = null; }
        olderLoading = true; older.setEnabled(false);
        rpc("thread/items/list", object("threadId", target, "limit", 50, "sortDirection", "desc", "cursor", reset ? JSONObject.NULL : cursor), (result, failure) -> {
            if (generation != historyGeneration || !thread.equals(target)) return;
            olderLoading = false; older.setEnabled(true); syncing = false;
            if (failure != null) { uncertain = true; fail(failure); controls(); return; }
            JSONArray data = result.optJSONArray("data");
            // Descending pages are prepended; items received live win over stale snapshots.
            if (data != null) for (int i = 0; i < data.length(); i++) {
                JSONObject entry = data.optJSONObject(i), item = entry.optJSONObject("item");
                if (item != null && !rows.containsKey(item.optString("id"))) render(item, true);
            }
            cursor = result.isNull("nextCursor") ? null : result.optString("nextCursor", null);
            older.setVisibility(cursor == null ? View.GONE : View.VISIBLE);
            uncertain = false; controls(); if (reset) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }
    private void send() {
        String text = prompt.getText().toString().trim(); if (text.isEmpty() || !ready || busy || sending || syncing || uncertain) return;
        sending = true; error.setText(""); controls();
        int selection = model.getSelectedItemPosition(); String selected = selection >= 0 && selection < modelIds.size() ? modelIds.get(selection) : "";
        prefs.edit().putString("model", selected).apply();
        if (thread.isEmpty()) {
            rpc("thread/start", object("cwd", cwd), (result, failure) -> {
                if (failure != null) { sending = false; fail(failure); controls(); return; }
                thread = result.optJSONObject("thread").optString("id"); prefs.edit().putString("thread", thread).apply();
                messages.removeAllViews(); submit(text, selected);
            });
        } else submit(text, selected);
    }
    private void submit(String text, String selected) {
        JSONObject params = object("threadId", thread, "clientUserMessageId", UUID.randomUUID().toString(),
            "input", new JSONArray().put(object("type", "text", "text", text)));
        if (!selected.isEmpty()) try { params.put("model", selected); } catch (Exception ignored) {}
        uncertain = true;
        rpc("turn/start", params, (result, failure) -> {
            sending = false;
            if (failure != null) { fail(failure + " · Reconnect in Settings to verify the outcome."); controls(); return; }
            uncertain = false; prompt.setText(""); JSONObject active = result.optJSONObject("turn");
            turn = active.optString("id"); busy = "inProgress".equals(active.optString("status")); controls();
        });
    }
    private void interrupt() {
        if (turn == null) return;
        rpc("turn/interrupt", object("threadId", thread, "turnId", turn), (result, failure) -> { if (failure != null) fail(failure); });
    }
    private void newChat() {
        if (sending || syncing) { fail("Wait for the current request to finish."); return; }
        if (busy) { fail("Stop the current task before opening a new chat."); return; }
        historyGeneration++; thread = ""; turn = null; cursor = null; uncertain = false; olderLoading = false;
        prefs.edit().remove("thread").apply(); rows.clear(); rowText.clear(); empty(); older.setVisibility(View.GONE); error.setText(""); controls();
    }
    private void sessions() {
        if (!ready || sending || syncing || busy) { fail("Connect and finish the current task before switching sessions."); return; }
        sessionPage(null);
    }
    private void sessionPage(String pageCursor) {
        rpc("thread/list", object("limit", 30, "cursor", pageCursor == null ? JSONObject.NULL : pageCursor), (result, failure) -> {
            if (failure != null) { fail(failure); return; }
            JSONArray data = result.optJSONArray("data"); ArrayList<String> labels = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) { JSONObject t = data.optJSONObject(i); labels.add(t.optString("name", t.optString("preview", t.optString("id")))); }
            AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("Your sessions").setItems(labels.toArray(new String[0]), (d, index) -> {
                thread = data.optJSONObject(index).optString("id"); prefs.edit().putString("thread", thread).apply(); restore();
            }).setNegativeButton("Close", null);
            if (!result.isNull("nextCursor")) dialog.setPositiveButton("More", (d, which) -> sessionPage(result.optString("nextCursor")));
            dialog.show();
        });
    }
    private void render(JSONObject item, boolean prepend) {
        if (item == null || "reasoning".equals(item.optString("type"))) return;
        String id = item.optString("id"), type = item.optString("type"), body;
        if (type.equals("userMessage")) {
            StringBuilder text = new StringBuilder(); JSONArray content = item.optJSONArray("content");
            if (content != null) for (int i = 0; i < content.length(); i++) text.append(content.optJSONObject(i).optString("text", "[attachment]")).append('\n');
            body = text.toString();
        } else body = item.optString("text", item.toString());
        if (body.length() > 24000) body = body.substring(body.length() - 24000);
        rowText.put(id, body); TextView view = rows.get(id);
        boolean follow = scroll.getScrollY() + scroll.getHeight() >= messages.getHeight() - dp(100);
        if (view == null) {
            view = label("", 16); view.setTextIsSelectable(true); view.setPadding(dp(14), dp(12), dp(14), dp(12));
            GradientDrawable background = new GradientDrawable(); background.setColor(type.equals("userMessage") ? 0xffe2ebe3 : 0xffffffff); background.setCornerRadius(dp(14)); view.setBackground(background);
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.setMargins(0, dp(6), 0, dp(6));
            messages.addView(view, prepend ? 0 : messages.getChildCount(), layout); rows.put(id, view);
        }
        if (type.equals("agentMessage") || type.equals("plan")) {
            // Coalesce streaming deltas without delaying rendering indefinitely.
            TextView target = view;
            if (target.getTag() == null) {
                target.setTag(Boolean.TRUE);
                Runnable update = () -> {
                    target.setTag(null);
                    if (rows.get(id) != target) return;
                    boolean atBottom = scroll.getScrollY() + scroll.getHeight() >= messages.getHeight() - dp(100);
                    target.setText(MarkdownText.render(type.equals("plan") ? "PLAN" : "CODEX",
                        MarkdownDocument.parse(rowText.getOrDefault(id, "")), getResources().getDisplayMetrics().density));
                    target.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                    if (atBottom && !prepend) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                };
                if (prepend) update.run(); else target.postDelayed(update, 60);
            }
        } else view.setText((type.equals("userMessage") ? "YOU" : type.toUpperCase()) + "\n\n" + body);
        // Bound the live view; complete history remains in Codex and can be reloaded.
        if (rows.size() > 200) {
            String remove = rows.keySet().iterator().next(); messages.removeView(rows.remove(remove)); rowText.remove(remove);
        }
        if (!prepend && follow) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
    private void queueRequest(JSONObject request) {
        if (request == null) return; requests.put(String.valueOf(request.opt("id")), request); showRequest();
    }
    private void showRequest() {
        if (approval != null || requests.isEmpty()) return;
        JSONObject request = requests.values().iterator().next();
        approval = Interactions.show(this, request, result -> rpc("native/answer", object("id", request.opt("id"), "result", result), (reply, failure) -> {
            if (failure != null) fail(failure);
            else requests.remove(String.valueOf(request.opt("id")));
            if (approval != null) approval.dismiss(); approval = null;
            if (failure == null) showRequest();
        }));
    }
    private void settings() {
        LinearLayout form = column(); form.setPadding(dp(24), dp(8), dp(24), 0);
        form.addView(label("Start the Termux service once, then paste its pairing code. It stays valid across restarts.", 14));
        form.addView(button("Start service in Termux", this::startService));
        EditText token = new EditText(this); token.setHint("64-character pairing code"); token.setSingleLine(true);
        token.setInputType(0x81); token.setText(prefs.getString("token", "")); form.addView(token);
        EditText directory = new EditText(this); directory.setHint("Project directory in Termux"); directory.setText(cwd); form.addView(directory);
        form.addView(label("Commands and edits use your existing Codex permissions. Other clients may hold a session's writer lease.", 12));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Connect your workspace").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Connect / reconnect", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(view -> {
            String value = token.getText().toString().trim();
            if (!value.matches("[a-f0-9]{64}")) { token.setError("Paste the complete pairing code"); return; }
            String path = directory.getText().toString().trim(); if (!path.startsWith("/")) { directory.setError("Use an absolute project path"); return; }
            cwd = path; project.setText(cwd); prefs.edit().putString("token", value).putString("cwd", cwd).apply(); dialog.dismiss(); connect();
        })); dialog.show();
    }
    private void startService() {
        if (checkSelfPermission("com.termux.permission.RUN_COMMAND") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"com.termux.permission.RUN_COMMAND"}, 1); return;
        }
        Intent command = new Intent("com.termux.RUN_COMMAND"); command.setComponent(new ComponentName("com.termux", "com.termux.app.RunCommandService"));
        command.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh");
        command.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"/data/data/com.termux/files/home/codex-termux/apps/codex-android/start-service.sh"});
        command.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
        try { startService(command); } catch (RuntimeException exception) { fail("Enable allow-external-apps=true in Termux settings, or start the service manually."); }
    }
    private void controls() {
        send.setEnabled(ready && !busy && !sending && !syncing && !uncertain); stop.setEnabled(ready && busy && turn != null);
        model.setEnabled(ready && !busy && !sending && !syncing && !uncertain);
        send.setText(sending ? "Sending…" : "Send");
    }
    private void empty() {
        messages.removeAllViews(); TextView welcome = label("A workspace in your pocket\n\nStart a conversation or open a recent session. Your work runs in Termux while this app handles the conversation.", 22);
        welcome.setPadding(dp(12), dp(60), dp(12), dp(24)); messages.addView(welcome);
    }
    private void fail(String text) { error.setText(text); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout value = new LinearLayout(this); value.setOrientation(LinearLayout.VERTICAL); return value; }
    private TextView label(String text, int size) { TextView value = new TextView(this); value.setText(text); value.setTextSize(size); value.setTextColor(INK); return value; }
    private Button button(String text, Runnable action) { Button value = new Button(this); value.setText(text); value.setAllCaps(false); value.setOnClickListener(v -> action.run()); return value; }
}
