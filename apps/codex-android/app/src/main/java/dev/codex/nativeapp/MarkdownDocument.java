package dev.codex.nativeapp;

import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.*;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Platform-independent Markdown layout, also used by the JVM regression tests. */
final class MarkdownDocument {
    enum Kind { STRONG, EMPHASIS, STRIKE, CODE, CODE_BLOCK, HEADING, QUOTE, LINK, TABLE }
    static final class Range {
        final Kind kind;
        final int start, end;
        final String value;
        Range(Kind kind, int start, int end, String value) {
            this.kind = kind; this.start = start; this.end = end; this.value = value;
        }
    }
    final String text;
    final List<Range> ranges;
    private MarkdownDocument(String text, List<Range> ranges) {
        this.text = text; this.ranges = Collections.unmodifiableList(ranges);
    }
    static MarkdownDocument parse(String source) {
        Parser parser = Parser.builder().extensions(Arrays.asList(StrikethroughExtension.create(),
            TablesExtension.create(), TaskListItemsExtension.create())).build();
        Layout layout = new Layout(); layout.walk(parser.parse(source), 0);
        return new MarkdownDocument(layout.out.toString(), layout.ranges);
    }
    static boolean safeLink(String destination) {
        try {
            URI uri = new URI(destination); String scheme = uri.getScheme();
            if (scheme == null) return false;
            return ((scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                && uri.getHost() != null) || (scheme.equalsIgnoreCase("mailto") && !uri.getSchemeSpecificPart().isEmpty());
        } catch (Exception ignored) { return false; }
    }
    private static final class Layout {
        final StringBuilder out = new StringBuilder();
        final List<Range> ranges = new ArrayList<>();
        int listDepth;
        void gap(int count) {
            if (out.length() == 0) return;
            int present = 0;
            for (int i = out.length() - 1; i >= 0 && out.charAt(i) == '\n'; i--) present++;
            for (int i = present; i < count; i++) out.append('\n');
        }
        void range(Kind kind, int start, String value) {
            if (out.length() > start) ranges.add(new Range(kind, start, out.length(), value));
        }
        void children(Node node, int depth) {
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) walk(child, depth + 1);
        }
        void styled(Node node, Kind kind, String value, int depth) {
            int start = out.length(); children(node, depth); range(kind, start, value);
        }
        void literalCode(String literal) {
            gap(2); int start = out.length(); out.append(literal); range(Kind.CODE_BLOCK, start, ""); gap(2);
        }
        void walk(Node node, int depth) {
            if (depth > 64) { out.append("[…]"); return; }
            if (node instanceof Text) out.append(((Text) node).getLiteral());
            else if (node instanceof SoftLineBreak) out.append(' ');
            else if (node instanceof HardLineBreak) out.append('\n');
            else if (node instanceof Code) {
                int start = out.length(); out.append(((Code) node).getLiteral()); range(Kind.CODE, start, "");
            } else if (node instanceof FencedCodeBlock) literalCode(((FencedCodeBlock) node).getLiteral());
            else if (node instanceof IndentedCodeBlock) literalCode(((IndentedCodeBlock) node).getLiteral());
            else if (node instanceof StrongEmphasis) styled(node, Kind.STRONG, "", depth);
            else if (node instanceof Emphasis) styled(node, Kind.EMPHASIS, "", depth);
            else if (node instanceof Strikethrough) styled(node, Kind.STRIKE, "", depth);
            else if (node instanceof Heading) {
                gap(2); styled(node, Kind.HEADING, String.valueOf(((Heading) node).getLevel()), depth); gap(2);
            } else if (node instanceof BlockQuote) {
                gap(2); styled(node, Kind.QUOTE, "", depth); gap(2);
            } else if (node instanceof Paragraph) {
                children(node, depth); gap(node.getParent() instanceof ListItem ? 1 : 2);
            } else if (node instanceof ListBlock) {
                gap(listDepth == 0 ? 2 : 1); listDepth++;
                int number = node instanceof OrderedList ? ((OrderedList) node).getStartNumber() : 0;
                for (Node item = node.getFirstChild(); item != null; item = item.getNext()) {
                    gap(1);
                    for (int i = 1; i < listDepth; i++) out.append("    ");
                    out.append(node instanceof OrderedList ? number++ + ". " : "• ");
                    walk(item, depth + 1);
                }
                listDepth--; gap(listDepth == 0 ? 2 : 1);
            } else if (node instanceof TaskListItemMarker) out.append(((TaskListItemMarker) node).isChecked() ? "☑ " : "☐ ");
            else if (node instanceof Link) {
                String destination = ((Link) node).getDestination();
                if (safeLink(destination)) styled(node, Kind.LINK, destination, depth); else children(node, depth);
            } else if (node instanceof Image) {
                out.append("[Image: "); children(node, depth); out.append(']');
            } else if (node instanceof ThematicBreak) { gap(2); out.append("────────────"); gap(2); }
            else if (node instanceof HtmlInline) out.append(((HtmlInline) node).getLiteral());
            else if (node instanceof HtmlBlock) { gap(2); out.append(((HtmlBlock) node).getLiteral()); gap(2); }
            else if (node instanceof TableBlock) { gap(2); styled(node, Kind.TABLE, "", depth); gap(2); }
            else if (node instanceof TableRow) { children(node, depth); gap(1); }
            else if (node instanceof TableCell) {
                if (node.getPrevious() != null) out.append("  │  ");
                if (((TableCell) node).isHeader()) styled(node, Kind.STRONG, "", depth); else children(node, depth);
            } else children(node, depth);
        }
    }
}
