#!/data/data/com.termux/files/usr/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p .cache/markdown
# Java 8-compatible parser and extensions, pinned with verified artifact hashes.
while read -r artifact checksum; do
    target=".cache/markdown/$artifact.jar"
    if [ -f "$target" ] && printf '%s  %s\n' "$checksum" "$target" | sha256sum -c - >/dev/null 2>&1; then
        continue
    fi
    download=$(mktemp .cache/markdown/download.XXXXXX)
    curl -fsSL --retry 3 "https://repo.maven.apache.org/maven2/org/commonmark/$artifact/0.21.0/$artifact-0.21.0.jar" -o "$download"
    printf '%s  %s\n' "$checksum" "$download" | sha256sum -c - >/dev/null
    mv "$download" "$target"
done <<'DEPS'
commonmark 81084a7035046fe306f0dbf16ef57a68d08ee5c97004ea867e62b5db46e98afb
commonmark-ext-gfm-strikethrough b5ed6fa18214e588e502385d95e878a8150f122c7a874a75a389682837b906f8
commonmark-ext-gfm-tables fc05fe991f2254ab0c8f6ccb9f0b6ec1c2b6df350389ed3e411ac6f52e7a75e5
commonmark-ext-task-list-items 53a3c76cf56947af1f6882a9a1ce962f3b338ca952d83dd402b7f5711c14bee0
DEPS
