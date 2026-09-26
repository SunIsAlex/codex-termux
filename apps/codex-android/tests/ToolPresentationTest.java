package dev.codex.nativeapp;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import static dev.codex.nativeapp.RpcClient.object;

public final class ToolPresentationTest {
    public static void main(String[] args) throws Exception {
        JSONObject[] examples = {
            object("type", "commandExecution", "status", "inProgress", "command", "npm run build", "cwd", "/workspace/app"),
            object("type", "commandExecution", "status", "completed", "command", "npm run build", "cwd", "/workspace/app", "exitCode", 0, "durationMs", 1250, "aggregatedOutput", "Build complete\n"),
            object("type", "commandExecution", "status", "completed", "command", "npm test", "exitCode", 1, "durationMs", 80, "aggregatedOutput", "One test failed\n"),
            object("type", "fileChange", "status", "completed", "changes", new JSONArray().put(object("path", "src/app.js", "kind", object("type", "update"), "diff", "@@ -1 +1 @@\n-old()\n+new()"))),
            object("type", "mcpToolCall", "status", "inProgress", "server", "docs", "tool", "search", "arguments", object("query", "Android")),
            object("type", "dynamicToolCall", "status", "completed", "success", false, "tool", "lookup", "contentItems", new JSONArray().put(object("text", "Not found"))),
            object("type", "webSearch", "status", "completed", "query", "Android text spans"),
            object("type", "futureToolType", "message", "Keep unfamiliar tools inspectable"),
        };
        StringBuilder snapshot = new StringBuilder();
        for (JSONObject example : examples) {
            ToolPresentation view = ToolPresentation.from(example);
            snapshot.append(view.symbol).append(' ').append(view.title).append(" [").append(view.status).append(" / ").append(view.tone).append("]\n")
                .append(view.summary).append('\n').append(view.metadata).append('\n').append(view.details).append("\n---\n");
        }
        if (args.length == 1 && args[0].equals("--update-snapshot")) Files.write(Paths.get("tests/tools.snap"), snapshot.toString().getBytes(StandardCharsets.UTF_8));
        String expected = new String(Files.readAllBytes(Paths.get("tests/tools.snap")), StandardCharsets.UTF_8);
        if (!expected.equals(snapshot.toString())) throw new AssertionError(snapshot);
        ToolPresentation running = ToolPresentation.from(examples[0]);
        ToolPresentation output = running.appendOutput("compiling…").appendOutput("\ndone");
        assert output.details.equals("Command\nnpm run build\n\nOutput\ncompiling…\ndone");
        assert output.details.contains("compiling…\ndone");
        assert output.title.equals(running.title) && output.status.equals("Running");
        assert ToolPresentation.from(examples[2]).tone == ToolPresentation.Tone.ERROR;
        assert ToolPresentation.from(examples[3]).diff;
        assert ToolPresentation.from(examples[4]).progress("3 pages found").summary.equals("3 pages found");
        assert ToolPresentation.from(examples[5]).tone == ToolPresentation.Tone.ERROR;
        assert ToolPresentation.from(object("type", "fileChange", "status", "declined")).status.equals("Declined");
        assert !ToolPresentation.isTool("agentMessage") && ToolPresentation.isTool("functionCallOutput");
        StringBuilder huge = new StringBuilder(); for (int i = 0; i < 30000; i++) huge.append('x');
        ToolPresentation bounded = output.appendOutput(huge + "TAIL");
        assert bounded.details.length() <= ToolPresentation.LIMIT && bounded.details.endsWith("TAIL");
        assert ToolPresentation.from(object("type", "mcpToolCall", "tool", huge.toString(), "arguments", huge.toString())).details.length() <= ToolPresentation.LIMIT;
        ToolPresentation replaced = ToolPresentation.from(examples[1]);
        assert replaced.tone == ToolPresentation.Tone.SUCCESS && !replaced.details.contains("compiling…");
        System.out.println("PASS: tool card snapshots, errors, progress, final-state replacement and output bounds");
    }
}
