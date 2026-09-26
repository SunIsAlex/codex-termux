package dev.codex.nativeapp;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.*;
import android.view.View;
import android.widget.Toast;

/** Renders Markdown using selectable native spans; no HTML or embedded browser. */
final class MarkdownText {
    static CharSequence render(String title, MarkdownDocument document, float density) {
        SpannableStringBuilder text = new SpannableStringBuilder(title + "\n\n");
        text.setSpan(new StyleSpan(Typeface.BOLD), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int offset = text.length(); text.append(document.text);
        for (MarkdownDocument.Range range : document.ranges) {
            int start = offset + range.start, end = offset + range.end;
            switch (range.kind) {
                case STRONG: apply(text, new StyleSpan(Typeface.BOLD), start, end); break;
                case EMPHASIS: apply(text, new StyleSpan(Typeface.ITALIC), start, end); break;
                case STRIKE: apply(text, new StrikethroughSpan(), start, end); break;
                case CODE:
                case CODE_BLOCK:
                case TABLE:
                    apply(text, new TypefaceSpan("monospace"), start, end);
                    apply(text, new RelativeSizeSpan(0.9f), start, end);
                    apply(text, new BackgroundColorSpan(0xffeef1ed), start, end);
                    break;
                case HEADING:
                    apply(text, new StyleSpan(Typeface.BOLD), start, end);
                    apply(text, new RelativeSizeSpan(1.65f - 0.1f * Integer.parseInt(range.value)), start, end);
                    break;
                case QUOTE:
                    apply(text, new QuoteSpan(0xff126b55, Math.round(3 * density), Math.round(10 * density)), start, end);
                    apply(text, new ForegroundColorSpan(0xff52635d), start, end);
                    break;
                case LINK:
                    apply(text, new URLSpan(range.value) {
                        @Override public void onClick(View widget) {
                            try { super.onClick(widget); }
                            catch (RuntimeException error) { Toast.makeText(widget.getContext(), "No app can open this link", Toast.LENGTH_SHORT).show(); }
                        }
                    }, start, end);
                    break;
            }
        }
        return text;
    }
    private static void apply(SpannableStringBuilder text, Object span, int start, int end) {
        text.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
}
