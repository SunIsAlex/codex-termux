#!/data/data/com.termux/files/usr/bin/sh
set -eu
cd "$(dirname "$0")"
android_jar=${ANDROID_JAR:-../../prototypes/chatgpt-phone-bridge/.cache/android-35.jar}
for tool in aapt2 javac jar d8 zip keytool apksigner; do
    command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 1; }
done
test -f "$android_jar" || { echo 'Set ANDROID_JAR to an Android API 35 android.jar.' >&2; exit 1; }
mkdir -p build .cache
stage=$(mktemp -d "$PWD/build/compile.XXXXXX")
mkdir -p "$stage/classes" "$stage/dex"
aapt2 link -I "$android_jar" --manifest app/src/main/AndroidManifest.xml \
    --min-sdk-version 29 --target-sdk-version 35 --version-code 1 --version-name 0.1.0 \
    -o "$stage/unsigned.apk"
javac --release 8 -cp "$android_jar" -d "$stage/classes" app/src/main/java/dev/codex/nativeapp/*.java
jar cf "$stage/classes.jar" -C "$stage/classes" .
d8 --lib "$android_jar" --min-api 29 --output "$stage/dex" "$stage/classes.jar"
(cd "$stage/dex" && zip -q -j ../unsigned.apk classes.dex)
if [ ! -f .cache/debug.keystore ]; then
    keytool -genkeypair -keystore .cache/debug.keystore -storepass android -keypass android \
        -alias debug -dname CN=CodexNative -keyalg RSA -keysize 2048 -validity 10000
fi
apksigner sign --ks .cache/debug.keystore --ks-pass pass:android \
    --out build/codex-native.apk "$stage/unsigned.apk"
apksigner verify --verbose build/codex-native.apk
printf 'Built %s/build/codex-native.apk\n' "$PWD"
if [ -d /sdcard/Download ] && [ -w /sdcard/Download ]; then
    cp build/codex-native.apk /sdcard/Download/codex-native.apk
    echo 'Copied to /sdcard/Download/codex-native.apk'
fi
