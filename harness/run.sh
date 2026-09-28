#!/usr/bin/env bash
# Regression harness for the agent loop: runs the Kotlin checks against a mock provider.
#
#   harness/run.sh
#
# It builds core/ and app/, compiles Harness.kt against them, starts mock_server.py on
# 127.0.0.1:8731 (the endpoint the checks point at) and runs the checks. Exit code is non-zero
# when any check fails, so it can gate a commit.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

: "${JAVA_HOME:=/usr/lib/jvm/java-17-openjdk}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

find_jar() { find "$HOME/.gradle/caches" -name "$1" 2>/dev/null | head -1; }

STDLIB="$(find_jar 'kotlin-stdlib-2.0.21.jar')"
COROUTINES="$(find_jar 'kotlinx-coroutines-core-jvm-*.jar')"
COMPILER="$(find_jar 'kotlin-compiler-embeddable-2.0.21.jar')"
SCRIPTRT="$(find_jar 'kotlin-script-runtime-2.0.21.jar')"
REFLECT="$(find_jar 'kotlin-reflect-2.0.21.jar')"
TROVE="$(find_jar 'trove4j-*.jar')"
ANNOT="$(find_jar 'annotations-13.0.jar')"
# Android provides org.json on-device; on the JVM the harness needs a real implementation.
JSON="$(find_jar 'json-*.jar')"

for jar in "$STDLIB" "$COROUTINES" "$COMPILER" "$REFLECT" "$TROVE" "$ANNOT" "$JSON"; do
    if [ -z "$jar" ]; then
        echo "缺少 Kotlin 依赖（请先跑一次 ./gradlew :app:assembleDebug）" >&2
        exit 2
    fi
done

echo "== 构建 core 与 app =="
./gradlew :core:jar :app:assembleDebug --offline --console=plain -q

CLASSES="core/build/libs/core.jar:app/build/tmp/kotlin-classes/debug:$STDLIB:$COROUTINES:$JSON"
OUT="harness/out"
rm -rf "$OUT" && mkdir -p "$OUT"

echo "== 编译检查 =="
java -cp "$COMPILER:$STDLIB:$SCRIPTRT:$REFLECT:$TROVE:$COROUTINES:$ANNOT" \
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -classpath "$CLASSES" -d "$OUT" -jvm-target 17 -nowarn harness/Harness.kt

echo "== 启动 mock 提供方 =="
python3 harness/mock_server.py 8731 >/dev/null 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null || true' EXIT
sleep 0.5

echo "== 运行检查 =="
java -cp "$OUT:$CLASSES" HarnessKt
