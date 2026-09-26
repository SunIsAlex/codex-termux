# Codex CLI for Termux

> Android Termux package built from upstream OpenAI Codex `rust-v0.155.0`.

Package metadata for the Termux-focused line `@mmmbuto/codex-cli-termux`.

## Install

```bash
pkg update && pkg upgrade -y
pkg install nodejs-lts -y
npm install -g @mmmbuto/codex-cli-termux@latest
codex --version
codex login
```

## Lightweight Web GUI

Launch the Android-friendly local Web UI from any project directory:

```bash
codex web
```

It opens the browser automatically and supports prompt input, image attachments,
model selection, streaming output, and approvals. Use `--no-open` to only print
the private local link, or `--port PORT` to choose a fixed loopback port.

Existing threads resume normally in the Web UI, including after a restart.
Only threads whose writer is held by another client open read-only; sending
from one creates a separate branch. Refresh after that client releases the
thread to resume the original. CLI and Web share authentication, configuration,
and project files.

## Optional ChatGPT Web provider

The Termux launcher can install the pinned, unofficial
`miuuyy/codex-chatgpt-web` bridge and expose its Browser-only models in Codex:

```sh
pkg install x11-repo termux-x11-nightly chromium bun
codex chatgpt-web install
# Install/open the Termux:X11 Android app, then start its server:
termux-x11 :0 &
export DISPLAY=:0
codex chatgpt-web setup
codex chatgpt-web status
```

To reuse the session already signed in within Android Chrome, enable Android Wireless debugging,
pair/connect Termux's `adb`, keep Chrome open, and run:

```sh
adb pair PHONE_IP:PAIRING_PORT
adb connect PHONE_IP:DEBUG_PORT
codex chatgpt-web setup --android-chrome
```

This mode attaches through an ADB-forwarded Chrome DevTools connection. It does not copy or persist
Chrome cookies. The forwarding is restored automatically when the provider starts; after a reboot,
Android may require a new `adb connect` using the current Wireless debugging port.

Get the companion Android app from the
[Termux:X11 releases](https://github.com/termux/termux-x11/releases).

Once its reversible Codex route is active, normal `codex` and `codex web`
launches ensure that the local Responses bridge is running. Android Full-harness
mode is not enabled because its tunnel-client runtime is not Android-compatible.
The bridge automates a private Chromium profile and can break when ChatGPT's UI
changes; use it only with your own account and applicable workspace policy.

## Notes

- Android 10+ / API 29+ on Termux ARM64 (the release binary is built for API 29)
- Built from upstream `rust-v0.155.0`
- Carries only the Termux compatibility delta needed for packaging and runtime
- Real code-mode (`exec`/`wait`) is enabled on the native Android build via the in-process V8 runtime (no longer stubbed) — this is the meaningful capability gain on Termux
- Realtime voice/audio is not part of this build: upstream removed the TUI
  realtime voice surface, so the fork's former Android `cpal`/`oboe` toggle was
  retired as well. A plain Termux CLI process has no Android `JavaVM`/`Activity`
  for that backend; a future Termux-native audio path is tracked separately.
- Packaged launchers preserve bundled `libc++_shared.so` visibility
- Android ELFs are hardened with `RUNPATH=$ORIGIN`
- Fork-owned Android `rusty_v8` prebuilds are used for maintainer cross-builds
- GitHub Actions builds the Android ARM64 tarball from an exact sanitized candidate ref; the maintainer verifies that artifact and publishes the unchanged tarball locally before promoting GitHub `main` and the release

See the main repository for release notes and patch inventory:

- https://github.com/DioNanos/codex-termux
- https://github.com/DioNanos/codex-termux/blob/main/patches/README.md
