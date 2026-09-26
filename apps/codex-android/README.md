# Codex Native for Android

A native Android conversation client for Codex running in Termux. This app does
not load a WebView or start `codex web`. It connects to a small authenticated
loopback service that owns a Codex app-server process. The service currently
reuses the repository's tested process bridge module, not the web panel server.

## Install and connect

1. Build with `sh apps/codex-android/build-termux.sh` from the repository root.
   Requires Java, aapt2, d8, zip and apksigner. Set `ANDROID_JAR` to an absolute
   API 35 android.jar path if the existing local prototype SDK cache is absent.
2. Install `apps/codex-android/build/codex-native.apk` (also copied to Android
   Downloads when shared storage is available). Android 10 or later is required.
3. In Termux run `sh apps/codex-android/start-service.sh`. Keep that session open.
4. Open **Codex Native → Settings**, paste the pairing code printed by the
   service and set your project directory. Tap **Connect / reconnect**.

The optional **Start service in Termux** button requires Termux's RUN_COMMAND
permission and `allow-external-apps=true` in `~/.termux/termux.properties`. It
assumes this checkout is at `~/codex-termux`. Manual startup works at any path.
Grant the permission and tap the button again if Android prompts on first use.

## Behavior and limits

- Native message list, streamed replies, model picker, paginated sessions and
  history, saved drafts, task interruption, permission decisions and user-input
  questions. Existing Codex model/provider and permission configuration applies.
- The loopback service listens only on `127.0.0.1:8766`, requires a random
  pairing token, and allows one controlling app connection at a time. Store the
  code privately. To rotate it, stop the service, move
  `~/.codex/native-android/pairing-token` aside, and restart/re-pair.
- Leaving the app closes its socket. Codex keeps working in Termux. Returning
  resumes the selected session and reloads persisted history and approvals.
  Network failures trigger bounded-backoff reconnect; submitted prompts are
  never automatically retried. On an uncertain submission the app blocks Send
  until reconnect/history reconciliation. The unsent draft is preserved.
- Android may terminate Termux; a wake lock does not prevent that. Restart the
  service manually if needed. A Codex child-process crash triggers reconnect.
- Sessions owned by another client display the backend error. Use a new chat
  or release that client's writer lease before resuming.
- Version 0.2 renders Markdown replies and plans using native text spans:
  headings, bold/italic/strikethrough, nested lists, task lists, blockquotes,
  inline/fenced/indented code, links, and basic text tables. Streaming updates
  are coalesced. User messages and tool details stay literal. Code whitespace
  is preserved; wide code and table rows wrap to the screen. Images show their
  alt text, and HTML stays literal. HTTP(S) and mailto links open externally.
  Image upload, account login, Plan/effort controls and MCP form/URL acceptance are not yet
  implemented; MCP requests can be explicitly declined. Login with `codex login`
  in Termux. Model names come from the running backend.
- The viewport retains up to 200 items with 24,000 characters per item. Full
  history remains in Codex. The APK uses a local development signing key in
  `.cache/`; retain it for compatible future APK upgrades.

## Verification

Run `node --test apps/codex-android/tests/*.test.mjs` for authenticated transport,
reconnect, backend failure and message-boundary coverage. The build script
compiles and verifies the APK signature. Device UI checks require installing
the APK; a successful build alone does not establish on-device behavior.
Run `sh apps/codex-android/test-java.sh` for the actual Android transport on the
host JVM, with Unicode framing and an interrupted request across reconnection.
Set `JSON_JAR` to an org.json JVM jar if the existing prototype cache is absent.
The same script checks Markdown text/style snapshots and partial streaming
input. Builds fetch checksum-pinned CommonMark 0.21.0 artifacts (Java 8) from
Maven Central and cache them locally. Its BSD license is included in the APK.
