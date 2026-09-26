package dev.codex.nativeapp;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;

/** Compact tool activity with a stable disclosure state across streaming updates. */
final class ToolCard extends LinearLayout {
    private final TextView icon, title, badge, summary, metadata, disclosure, detail;
    private final LinearLayout expanded;
    private ToolPresentation value;
    private boolean open, scheduled;
    ToolCard(Context context) {
        super(context); setOrientation(VERTICAL); setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable(); background.setColor(0xfff9faf7);
        background.setCornerRadius(dp(14)); background.setStroke(dp(1), 0xffdce3dd); setBackground(background);
        LinearLayout header = new LinearLayout(context); header.setGravity(Gravity.CENTER_VERTICAL);
        icon = text(16, 0xff526c60); icon.setGravity(Gravity.CENTER_VERTICAL); icon.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); header.addView(icon, new LayoutParams(dp(36), dp(32)));
        title = text(14, 0xff243b30); title.setTypeface(null, Typeface.BOLD); title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LayoutParams(0, -2, 1));
        badge = text(11, 0xff526c60); badge.setPadding(dp(9), dp(4), dp(9), dp(4)); header.addView(badge); addView(header);
        summary = text(14, 0xff33483d); summary.setMaxLines(2); summary.setEllipsize(TextUtils.TruncateAt.END); summary.setPadding(0, dp(6), 0, dp(4)); addView(summary);
        metadata = text(11, 0xff64766a); metadata.setMaxLines(2); metadata.setEllipsize(TextUtils.TruncateAt.MIDDLE); addView(metadata);
        disclosure = text(12, 0xff126b55); disclosure.setGravity(Gravity.CENTER_VERTICAL); disclosure.setMinHeight(dp(48));
        disclosure.setFocusable(true); disclosure.setOnClickListener(v -> { open = !open; refreshDisclosure(); }); addView(disclosure);
        expanded = new LinearLayout(context); expanded.setOrientation(VERTICAL);
        detail = text(12, 0xff34483e); detail.setTypeface(Typeface.MONOSPACE); detail.setTextIsSelectable(true); detail.setPadding(dp(10), dp(10), dp(10), dp(10)); detail.setBackgroundColor(0xffedf1ec);
        expanded.addView(detail);
        Button copy = new Button(context); copy.setText("Copy details"); copy.setAllCaps(false);
        copy.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(value.title, value.details));
            Toast.makeText(context, "Details copied", Toast.LENGTH_SHORT).show();
        }); expanded.addView(copy); addView(expanded);
    }
    void bind(JSONObject item) { value = ToolPresentation.from(item); paint(); }
    void update(String method, String delta) {
        if (value == null) return;
        value = method.endsWith("/progress") ? value.progress(delta) : value.appendOutput(delta);
        if (!scheduled) { scheduled = true; postDelayed(() -> { scheduled = false; paint(); }, 80); }
    }
    private void paint() {
        icon.setText(value.symbol); title.setText(value.title); badge.setText(value.status);
        int ink, fill;
        switch (value.tone) {
            case RUNNING: ink = 0xff815b12; fill = 0xfff9edcf; break;
            case SUCCESS: ink = 0xff176346; fill = 0xffdff0e4; break;
            case ERROR: ink = 0xffa1332c; fill = 0xfffbe5e1; break;
            default: ink = 0xff59675f; fill = 0xffe8ece7; break;
        }
        GradientDrawable pill = new GradientDrawable(); pill.setColor(fill); pill.setCornerRadius(dp(12)); badge.setBackground(pill); badge.setTextColor(ink);
        summary.setText(value.summary); metadata.setText(value.metadata); metadata.setVisibility(value.metadata.isEmpty() ? GONE : VISIBLE);
        refreshDisclosure();
    }
    private void refreshDisclosure() {
        boolean hasDetails = !value.details.isEmpty(); disclosure.setVisibility(hasDetails ? VISIBLE : GONE);
        disclosure.setText(open ? "▾  Hide details" : "▸  Show details");
        disclosure.setContentDescription((open ? "Collapse " : "Expand ") + value.title + " details");
        expanded.setVisibility(open && hasDetails ? VISIBLE : GONE);
        if (open && hasDetails) {
            SpannableStringBuilder output = new SpannableStringBuilder(value.details);
            if (value.diff) {
                int start = 0;
                for (String line : value.details.split("\n", -1)) {
                    int color = line.startsWith("+") ? 0xff176346 : line.startsWith("-") ? 0xffa1332c : line.startsWith("@@") ? 0xff4667a1 : 0;
                    if (color != 0 && !line.isEmpty()) output.setSpan(new ForegroundColorSpan(color), start, start + line.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    start += line.length() + 1;
                }
            }
            detail.setText(output);
        }
    }
    private TextView text(int size, int color) { TextView view = new TextView(getContext()); view.setTextSize(size); view.setTextColor(color); return view; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
