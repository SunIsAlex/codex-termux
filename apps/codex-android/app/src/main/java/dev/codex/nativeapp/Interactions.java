package dev.codex.nativeapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import static dev.codex.nativeapp.RpcClient.object;

/** Explicit approval and question dialogs; unsupported elicitation is declined. */
final class Interactions {
    interface Answer { void send(JSONObject result); }
    static AlertDialog show(Activity activity, JSONObject request, Answer answer) {
        String method = request.optString("method"); JSONObject params = request.optJSONObject("params");
        if (params == null) params = object();
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setCancelable(false);
        if (method.endsWith("/requestApproval")) {
            JSONArray offered = params.optJSONArray("availableDecisions");
            if (offered == null || offered.length() == 0) offered = new JSONArray().put("accept").put("decline").put("cancel");
            final JSONArray decisions = offered;
            String[] labels = new String[decisions.length()];
            for (int i = 0; i < decisions.length(); i++) {
                String value = decisions.optString(i);
                labels[i] = value.equals("accept") ? "Allow once" : value.equals("acceptForSession") ? "Allow for this session" : value.equals("decline") ? "Decline" : value.equals("cancel") ? "Cancel task" : value;
            }
            String detail = params.optString("command", params.optString("reason", params.toString()));
            if (detail.length() > 6000) detail = detail.substring(0, 6000) + "…";
            TextView text = new TextView(activity); text.setText(detail); text.setTextIsSelectable(true); text.setPadding(32, 16, 32, 16);
            ScrollView view = new ScrollView(activity); view.addView(text);
            dialog.setTitle("Codex requests permission").setView(view).setItems(labels, (d, index) -> answer.send(object("decision", decisions.opt(index))));
        } else if (method.endsWith("requestUserInput")) {
            LinearLayout fields = new LinearLayout(activity); fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(32, 16, 32, 16);
            JSONArray questions = params.optJSONArray("questions"); ArrayList<EditText> inputs = new ArrayList<>(); ArrayList<String> ids = new ArrayList<>();
            if (questions != null) for (int i = 0; i < questions.length(); i++) {
                JSONObject q = questions.optJSONObject(i); TextView label = new TextView(activity); String title = q.optString("question");
                JSONArray options = q.optJSONArray("options");
                if (options != null) for (int j = 0; j < options.length(); j++) title += "\n• " + options.optJSONObject(j).optString("label");
                label.setText(title); fields.addView(label); EditText input = new EditText(activity); fields.addView(input); inputs.add(input); ids.add(q.optString("id"));
            }
            ScrollView view = new ScrollView(activity); view.addView(fields);
            dialog.setTitle("Codex needs your input").setView(view).setPositiveButton("Send answer", (d, which) -> {
                JSONObject values = object();
                try { for (int i = 0; i < ids.size(); i++) values.put(ids.get(i), object("answers", new JSONArray().put(inputs.get(i).getText().toString()))); }
                catch (Exception error) { throw new IllegalStateException(error); }
                answer.send(object("answers", values));
            });
        } else {
            dialog.setTitle("External tool request").setMessage(params.optString("message", "This request requires a client that supports MCP forms or URL authorization."))
                .setPositiveButton("Decline request", (d, which) -> answer.send(object("action", "decline", "content", JSONObject.NULL)));
        }
        return dialog.show();
    }
}
