#!/usr/bin/env bash
# Gradle-free build of ScootDash: kotlinc + aapt2 + d8 (or dx) + zipalign + apksigner.
#
# Needs: a JDK (17+), kotlinc (2.x), Android build-tools (aapt2, zipalign, apksigner,
# and d8 or dx), an android.jar (API 23 or newer), python3 (for the dx safety check).
# Override any path with environment variables, e.g.
#   ANDROID_JAR=~/Android/Sdk/platforms/android-34/android.jar BUILD_TOOLS=~/Android/Sdk/build-tools/34.0.0 ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

find_first() { for p in "$@"; do [ -e "$p" ] && { echo "$p"; return; }; done; echo ""; }

ANDROID_JAR="${ANDROID_JAR:-$(find_first "${ANDROID_HOME:-/nonexistent}"/platforms/android-3*/android.jar /usr/lib/android-sdk/platforms/android-23/android.jar)}"
BUILD_TOOLS="${BUILD_TOOLS:-$(find_first "${ANDROID_HOME:-/nonexistent}"/build-tools/3* /usr/lib/android-sdk/build-tools/debian)}"
KOTLINC="${KOTLINC:-$(command -v kotlinc || true)}"
KOTLIN_HOME="${KOTLIN_HOME:-$(cd "$(dirname "$(readlink -f "$KOTLINC")")/.." && pwd)}"
KOTLIN_STDLIB="${KOTLIN_STDLIB:-$KOTLIN_HOME/lib/kotlin-stdlib.jar}"
KEYSTORE="${KEYSTORE:-keystore/scootdash-debug.jks}"
VERSION_CODE="${VERSION_CODE:-2}"
VERSION_NAME="${VERSION_NAME:-1.1}"
OUT=build

for v in ANDROID_JAR BUILD_TOOLS KOTLINC KOTLIN_STDLIB; do
  [ -n "${!v}" ] && [ -e "${!v}" ] || { echo "missing $v (set it in the environment)"; exit 1; }
done
AAPT2="$BUILD_TOOLS/aapt2"
ZIPALIGN="$(find_first "$BUILD_TOOLS/zipalign" "$(command -v zipalign || true)")"
APKSIGNER="$(find_first "$BUILD_TOOLS/apksigner" "$(command -v apksigner || true)")"
D8="$(find_first "$BUILD_TOOLS/d8")"
DX="$(find_first "$BUILD_TOOLS/dx")"

echo "android.jar : $ANDROID_JAR"
echo "build-tools : $BUILD_TOOLS"
echo "kotlinc     : $KOTLINC"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/stdlib" "$OUT/dex"

echo "== resources"
"$AAPT2" compile --dir src/main/res -o "$OUT/res.zip"
"$AAPT2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
  --manifest src/main/AndroidManifest.xml -A src/main/assets \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  "$OUT/res.zip"

echo "== kotlin"
# -no-jdk: compile against android.jar's java.* only. Lambdas and SAM conversions as
# classes, not invokedynamic, because dx can't desugar those.
"$KOTLINC" -no-jdk -no-reflect -jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class \
  -classpath "$ANDROID_JAR" -d "$OUT/classes" src/main/kotlin 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
[ -f "$OUT/classes/io/github/pizzimenti/scootdash/MainActivity.class" ] || { echo "kotlin compile failed"; exit 1; }

echo "== dex"
(cd "$OUT/stdlib" && unzip -q "$KOTLIN_STDLIB" -x 'META-INF/*')
if [ -n "$D8" ]; then
  "$D8" --release --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class') "$KOTLIN_STDLIB"
else
  python3 tools/check_indy.py "$OUT/classes" "$OUT/stdlib" io/github/pizzimenti/scootdash/
  "$DX" --dex --min-sdk-version=26 --output="$OUT/dex/classes.dex" "$OUT/classes" "$OUT/stdlib" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
fi
[ -f "$OUT/dex/classes.dex" ] || { echo "dexing failed"; exit 1; }

echo "== package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X ../unsigned.apk classes*.dex)
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android -alias scootdash \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ScootDash debug" 2>&1 | grep -v '^Picked up' || true
fi
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/scootdash.apk" "$OUT/aligned.apk" 2>&1 | grep -v '^Picked up' || true
"$APKSIGNER" verify "$OUT/scootdash.apk" 2>&1 | grep -v '^Picked up' || true
ls -la "$OUT/scootdash.apk"
echo "done: $OUT/scootdash.apk"
