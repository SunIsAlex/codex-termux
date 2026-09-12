# ChatGPT Phone Bridge

An Android 10+ proof of concept that uses the normal ChatGPT Android UI and
the user's ChatGPT plan instead of the OpenAI API. It captures a push-to-talk
command with Android speech recognition, opens one dedicated ChatGPT
conversation, sends a compact nonce-bound command through the visible composer,
parses one allow-listed action, executes it locally, and speaks the result with
Android TTS. The classifier instructions are sent once when that conversation is
initialized instead of being repeated with every command.

The V5 prompt also exposes a small CLI-style bridge to the model. ChatGPT can
request `list_apps`, receive up to 100 matching launcher activities in the same
conversation, then issue `start_intent` with an Android action, data URI,
package, component, and primitive extras. For requests such as “find the latest
video about this topic and play it”, the prompt tells ChatGPT to use its own web
search first and then launch the selected YouTube URL with an Android Intent.
Commands that clearly need current information carry `webSearch: "required"`;
the initialization prompt forbids asking the user to supply a link or approve
search in that case. Before submitting such a command, the accessibility bridge
also tries to enable ChatGPT's visible **Search the web / 搜索网页** control by
its semantic label. Device-only commands carry `webSearch: "auto"`.
Search commands containing “open”, “play”, “打开”, “播放”, or “观看” also carry
`resultMode: "open"`. In that mode Phone Bridge rejects query Intents and search
result pages, then gives ChatGPT one automatic correction turn to return a
concrete `ACTION_VIEW` URL. Plain “search/list” requests use `resultMode: "list"`.

It deliberately does **not** read ChatGPT credentials, intercept network
traffic, inject private protocol messages, or expose an arbitrary root shell.
This is UI automation, so a ChatGPT app update can require adapting the
accessibility selectors.

## Build in Termux

```sh
pkg install openjdk-17 aapt2 d8 apksigner zip
./build-termux.sh
termux-open --view build/phone-bridge-debug.apk
```

Use `adb install -r build/phone-bridge-debug.apk` instead when installing from
another connected machine.

The build script downloads an Android 35 compile jar and the JSON.org jar into
the ignored `.cache` directory. It compiles, signs, verifies, and runs the parser
security tests without requiring the Android SDK or Gradle.

## Setup on the phone

1. Install and sign in to the official ChatGPT Android app.
2. Create a new ordinary ChatGPT chat, optionally rename or pin it as
   `Phone Bridge`, and copy its URL. It must be the private conversation URL
   `https://chatgpt.com/c/<conversation-id>`, not a `/share/` link.
   If ChatGPT's Share action gives you a `/share/` URL, open `chatgpt.com` in a
   browser with the same account, select the original conversation from history,
   and copy the `/c/` URL from the browser address bar. A share ID must not be
   converted into a conversation ID by replacing the path text.
3. Install this APK, open it, and grant microphone permission.
4. Paste that URL into **Dedicated conversation**, then tap **Save dedicated
   conversation URL**.
5. Tap **Enable accessibility bridge** and enable only ChatGPT Phone Bridge.
6. Return to Phone Bridge and tap **Send one-time initialization instruction**.
   Wait until ChatGPT replies `PHONE_BRIDGE_READY_V5`. Repeating this step is
   only necessary after changing the saved conversation URL.
7. Optionally add the Phone Bridge Quick Settings tile.
8. For text-only debugging, enter a command in the app's **纯文本调试** field
   and tap **发送文本指令** (or press the keyboard's Send action). This uses the
   same dedicated conversation and execution pipeline without microphone access.
   Use **取消当前请求** if ChatGPT returns an unusable reply or a request needs
   to be abandoned; restarting the accessibility service is not necessary.
9. Press and hold volume-up and volume-down together within 500 ms, say one
   action such as “音量调到百分之三十”, and release either key to stop recording.
   Either volume key works normally when pressed alone. The bridge restores the
   media volume captured before a successful chord. The in-app **Speak command**
   button and Quick Settings tile remain available as tap-to-talk alternatives.
10. If brightness control is needed, grant “modify system settings” when asked;
   no Root access is attempted.

The initialization instruction is the first normal user message in a dedicated
ChatGPT conversation. The consumer ChatGPT UI does not provide this bridge with
an API-level `system` role. Conversation history is what makes the instruction
available to later compact commands, so do not use this chat for unrelated
messages or delete its initialization turn.

ChatGPT must expose its composer and response text through Android's
accessibility tree. If it does not, the bridge fails closed and reports that the
installed ChatGPT version is incompatible. No coordinate-click fallback is used.

If speech recognition reports a client-state error, update to the latest APK,
confirm microphone permission, and check that Android has a default speech
recognition service enabled. The bridge creates a fresh recognizer for every
button press and ignores repeated presses while recognition is active.

## Supported actions

- open an installed launcher app by its visible label
- set media volume or screen brightness
- play/pause media
- toggle the flashlight
- open Wi-Fi, Bluetooth, display, or accessibility settings
- lock the screen
- query installed launcher apps and return their package/component to ChatGPT
- construct and start a CLI-style Android Intent selected by ChatGPT

Every request has a random nonce. The parser rejects stale replies, unknown
fields, unknown actions, malformed JSON, unsupported nested Intent extras,
shell text, and multi-action replies. Raw Intents can deliberately contain
package names, components, and URIs. They are launched through Android's
`startActivity()` rather than passed to `su` or a shell. Audit records contain
only the action name, success/failure, and timestamp.
Search-citation artifacts such as Unicode word joiners and replacement
characters are stripped from `dataUri` before constructing the Android Intent.
For HTTP(S), parsing stops at the first non-ASCII citation/widget artifact, and
the status result reports the exact normalized URL passed to Android.

## Known limitations

- This consumes ordinary ChatGPT message allowance, not Realtime API usage.
- The bridge depends on the saved private `/c/` URL continuing to open the same
  conversation in the installed ChatGPT app.
- It uses Android speech recognition and local TTS rather than ChatGPT Voice.
- ChatGPT UI/accessibility changes can break composer or response discovery.
- If the installed ChatGPT build does not expose its web-search control through
  accessibility, Phone Bridge falls back to requesting automatic model search
  in the command protocol and reports that fallback in its status text.
- The prototype allows only one pending request and one action per request.
- A plain-text refusal is detected and releases the pending request immediately.
  Other completed non-protocol replies are cancelled after a short idle grace
  period; the manual cancel button is always available as a fallback.
- Installing the V5 APK over an older build requires sending the one-time
  initialization instruction again so the dedicated conversation learns
  `list_apps` and `start_intent`.
- While physical push-to-talk is enabled, the simultaneous volume-up +
  volume-down chord is consumed by the bridge; either key used alone keeps its
  normal behavior. Turn the chord off from the app if the device maps that
  combination to another system feature.
- Sending messages, arbitrary UI control, deletion, payment, passwords, and
  verification codes are intentionally outside the action protocol.
