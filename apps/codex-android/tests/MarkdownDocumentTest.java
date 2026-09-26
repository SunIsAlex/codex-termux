package dev.codex.nativeapp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import static dev.codex.nativeapp.MarkdownDocument.Kind;

public final class MarkdownDocumentTest {
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected:\n" + expected + "\nActual:\n" + actual);
    }
    private static void style(MarkdownDocument document, Kind kind, String expected) {
        for (MarkdownDocument.Range range : document.ranges) {
            if (range.kind == kind && document.text.substring(range.start, range.end).equals(expected)) return;
        }
        throw new AssertionError("Missing " + kind + " on " + expected);
    }
    public static void main(String[] args) throws Exception {
        MarkdownDocument nested = MarkdownDocument.parse("# Title\n\n**Bold and _italic_** with ~~old~~ and `a * b`.");
        equal("Title\n\nBold and italic with old and a * b.\n\n", nested.text);
        style(nested, Kind.HEADING, "Title"); style(nested, Kind.STRONG, "Bold and italic");
        style(nested, Kind.EMPHASIS, "italic"); style(nested, Kind.STRIKE, "old"); style(nested, Kind.CODE, "a * b");

        MarkdownDocument code = MarkdownDocument.parse("```java\n  **literal** <tag>\n    x();\n```\n\n    indented();");
        style(code, Kind.CODE_BLOCK, "  **literal** <tag>\n    x();\n");
        style(code, Kind.CODE_BLOCK, "indented();\n");
        assert code.ranges.stream().noneMatch(r -> r.kind == Kind.STRONG);

        MarkdownDocument list = MarkdownDocument.parse("3. First\n4. Second\n   - nested\n\n- [x] Done\n- [ ] Todo");
        equal("3. First\n4. Second\n    • nested\n\n• ☑ Done\n• ☐ Todo\n\n", list.text);
        equal("escaped *literal* & entity\n\n", MarkdownDocument.parse("escaped \\*literal\\* &amp; entity").text);
        equal("soft break\nnew line\n\n", MarkdownDocument.parse("soft\nbreak  \nnew line").text);

        MarkdownDocument links = MarkdownDocument.parse("[site](https://example.com) [bad](javascript:alert) [local](file:///tmp/a) ![alt](https://example.com/a.png)");
        style(links, Kind.LINK, "site");
        equal(1L, links.ranges.stream().filter(r -> r.kind == Kind.LINK).count());
        assert links.text.contains("[Image: alt]");
        assert !MarkdownDocument.safeLink("intent://anything");
        assert !MarkdownDocument.safeLink("data:text/html,hello");
        assert MarkdownDocument.safeLink("mailto:hello@example.com");
        assert MarkdownDocument.parse("<script>alert(1)</script>").text.contains("<script>alert(1)</script>");

        String sample = new String(Files.readAllBytes(Paths.get("tests/markdown.md")), StandardCharsets.UTF_8);
        MarkdownDocument document = MarkdownDocument.parse(sample);
        StringBuilder snapshot = new StringBuilder(document.text).append("\n--- styles ---\n");
        for (MarkdownDocument.Range range : document.ranges) {
            snapshot.append(range.kind).append(' ').append(range.value).append(" | ")
                .append(document.text.substring(range.start, range.end).replace("\n", "\\n")).append('\n');
        }
        if (args.length == 1 && args[0].equals("--update-snapshot")) Files.write(Paths.get("tests/markdown.snap"), snapshot.toString().getBytes(StandardCharsets.UTF_8));
        equal(new String(Files.readAllBytes(Paths.get("tests/markdown.snap")), StandardCharsets.UTF_8), snapshot.toString());
        // Every streaming prefix, including incomplete links and fences, must render safely.
        for (int i = 0; i <= sample.length(); i++) {
            MarkdownDocument partial = MarkdownDocument.parse(sample.substring(0, i));
            for (MarkdownDocument.Range range : partial.ranges) {
                assert range.start >= 0 && range.start < range.end && range.end <= partial.text.length();
            }
        }
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100; i++) deep.append("> ");
        assert MarkdownDocument.parse(deep + "bounded").text.contains("[…]");
        System.out.println("PASS: Markdown syntax, style snapshot, safe links, code whitespace and all streaming prefixes");
    }
}
