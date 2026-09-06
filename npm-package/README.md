# Codex CLI for Termux

> Android Termux package built from upstream OpenAI Codex `rust-v0.153.2`.

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

## Notes

- Android 10+ / API 29+ on Termux ARM64 (the release binary is built for API 29)
- Built from upstream `rust-v0.153.2`
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
