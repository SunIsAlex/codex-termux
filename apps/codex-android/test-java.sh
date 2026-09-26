#!/data/data/com.termux/files/usr/bin/sh
set -eu
cd "$(dirname "$0")"
json_jar=${JSON_JAR:-../../prototypes/chatgpt-phone-bridge/.cache/json-20250517.jar}
test -f "$json_jar" || { echo 'Set JSON_JAR to an org.json JVM jar.' >&2; exit 1; }
mkdir -p build/test-classes
javac --release 8 -cp "$json_jar" -d build/test-classes \
    app/src/main/java/dev/codex/nativeapp/RpcClient.java tests/RpcClientTest.java
java -ea -cp "build/test-classes:$json_jar" dev.codex.nativeapp.RpcClientTest
