#!/data/data/com.termux/files/usr/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD_DIR="$PROJECT_DIR/build"
CACHE_DIR="$PROJECT_DIR/.cache"
ANDROID_JAR="$CACHE_DIR/android-35.jar"
JSON_JAR="$CACHE_DIR/json-20250517.jar"
AAPT2_BIN=${AAPT2_BIN:-aapt2}
ANDROID_JAR_SHA256=60be6880d7b2b526d654460d4ce6fa81bf23efaa28b02d901d0b63a01ccf4671
JSON_JAR_SHA256=3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796

mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/generated" "$CACHE_DIR"

for command in "$AAPT2_BIN" curl javac jar d8 zip keytool apksigner sha256sum; do
  command -v "$command" >/dev/null 2>&1 || {
    printf 'Missing build command: %s\n' "$command" >&2
    exit 1
  }
done

download_verified() {
  url=$1
  output=$2
  expected=$3
  if [ -f "$output" ] && printf '%s  %s\n' "$expected" "$output" | sha256sum -c - >/dev/null 2>&1; then
    return
  fi
  rm -f "$output"
  curl -L --fail --retry 3 --retry-all-errors --output "$output" "$url"
  printf '%s  %s\n' "$expected" "$output" | sha256sum -c - >/dev/null
}

download_verified \
  https://github.com/Reginer/aosp-android-jar/raw/main/android-35/android.jar \
  "$ANDROID_JAR" "$ANDROID_JAR_SHA256"
download_verified \
  https://repo1.maven.org/maven2/org/json/json/20250517/json-20250517.jar \
  "$JSON_JAR" "$JSON_JAR_SHA256"

rm -rf "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/generated"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/generated"

"$AAPT2_BIN" compile --dir "$PROJECT_DIR/app/src/main/res" -o "$BUILD_DIR/resources.zip"
"$AAPT2_BIN" link \
  -I "$ANDROID_JAR" \
  --manifest "$PROJECT_DIR/app/src/main/AndroidManifest.xml" \
  --min-sdk-version 29 \
  --target-sdk-version 35 \
  --version-code 1 \
  --version-name 0.1.0 \
  --java "$BUILD_DIR/generated" \
  -o "$BUILD_DIR/unsigned.apk" \
  "$BUILD_DIR/resources.zip"

find "$PROJECT_DIR/app/src/main/java" "$BUILD_DIR/generated" -name '*.java' -print \
  > "$BUILD_DIR/sources.txt"
javac --release 8 -cp "$ANDROID_JAR" -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
jar --create --file "$BUILD_DIR/classes.jar" -C "$BUILD_DIR/classes" .
d8 --lib "$ANDROID_JAR" --min-api 29 --output "$BUILD_DIR/dex" "$BUILD_DIR/classes.jar"

cp "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/phone-bridge-unsigned.apk"
(cd "$BUILD_DIR/dex" && zip -q -j "$BUILD_DIR/phone-bridge-unsigned.apk" classes.dex)

KEYSTORE="$CACHE_DIR/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android \
  --out "$BUILD_DIR/phone-bridge-debug.apk" "$BUILD_DIR/phone-bridge-unsigned.apk"
apksigner verify --verbose "$BUILD_DIR/phone-bridge-debug.apk"

rm -rf "$BUILD_DIR/test-classes"
mkdir -p "$BUILD_DIR/test-classes"
javac --release 8 -cp "$JSON_JAR" -d "$BUILD_DIR/test-classes" \
  "$PROJECT_DIR/app/src/main/java/dev/codex/phonebridge/PhoneAction.java" \
  "$PROJECT_DIR/app/src/main/java/dev/codex/phonebridge/IntentSpec.java" \
  "$PROJECT_DIR/app/src/main/java/dev/codex/phonebridge/ActionParser.java" \
  "$PROJECT_DIR/app/src/main/java/dev/codex/phonebridge/DedicatedChatUrl.java" \
  "$PROJECT_DIR/app/src/main/java/dev/codex/phonebridge/ProtocolPrompt.java" \
  "$PROJECT_DIR/app/src/test/java/dev/codex/phonebridge/ActionParserTest.java"
java -ea -cp "$BUILD_DIR/test-classes:$JSON_JAR" dev.codex.phonebridge.ActionParserTest

printf 'Built %s\n' "$BUILD_DIR/phone-bridge-debug.apk"
