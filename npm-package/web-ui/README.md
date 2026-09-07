# Codex Web for Termux

Run `codex login` once, then `codex web` from your project directory. The launcher
opens the local browser; if opening fails, copy the printed link. Keep that link
private. No additional npm dependencies or frontend build are required.

Use `codex web --no-open` to only print the link, or `codex web --port 8765` to
choose a port. Run `codex help web` for the short command reference. The
standalone `codex-web` command remains as a compatibility alias.

For a source checkout, run `node npm-package/bin/codex-web.js`. It uses the bundled
binary when available, otherwise the existing `codex` command on PATH.
The installed package rewrites the launcher
shebang for Termux automatically.

Select up to four JPEG, PNG or WebP images (10 MiB each). Enter inserts a newline;
Ctrl+Enter or Send submits. Existing Codex authentication, provider configuration,
and permissions apply. Images are stored under `$CODEX_HOME/web-ui` (default
`~/.codex/web-ui`). Unsent uploads expire on startup after 24 hours. The settings
panel can permanently delete all uploaded images, including historical images.

One page controls the service at a time. Closing the browser does not terminate
Codex; Android can still kill Termux. Stop the service with Ctrl+C in Termux.
Termux CLI and Web may run simultaneously. Existing threads first attempt normal
resume, including after restarting the Web service. Only an active writer conflict
falls back to read-only history and “分支并发送”; submitting then creates a separate
thread with copied history. Once the other client releases its writer, refresh to
retry normal resume. Other resume errors are reported without creating a branch.
This is independent conversation branching, not simultaneous editing of one
conversation. Both clients can still modify the same project files.
After an uncertain submission timeout, refresh and inspect the conversation before
resending. API credentials never enter the browser.

Markdown supports headings, emphasis, lists, tables, quotes, links and code blocks
with copy buttons. Marked 15.0.12 and DOMPurify 3.3.3 are bundled locally in
`vendor/` with their licenses; no CDN is contacted by the browser. HTML is sanitized
and remote images are not embedded. Effort options follow the model catalog and
are remembered per model; reasoning transcript items are hidden.

Current limitations: MCP elicitation supports scalar forms and URL authorization, while complex
extension forms can be declined/cancelled. History loads
100 items per page and retains at most 160 rendered items. Older stores without
item pagination report an explicit error instead of loading unbounded history.
Android resource measurements remain pending.

No test or build commands were run for this implementation, as requested.
