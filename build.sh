#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
PLATFORM_DIR="$SDK_DIR/platforms/android-35"
BUILD_TOOLS_DIR="$SDK_DIR/build-tools/34.0.0"
JAVAC_BIN="${JAVAC:-}"

if [[ -z "$JAVAC_BIN" ]]; then
  if [[ -x "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/javac" ]]; then
    JAVAC_BIN="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/javac"
  elif [[ -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/javac" ]]; then
    JAVAC_BIN="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/javac"
  else
    JAVAC_BIN="javac"
  fi
fi

if [[ ! -f "$PLATFORM_DIR/android.jar" ]]; then
  echo "Missing Android platform jar: $PLATFORM_DIR/android.jar" >&2
  exit 1
fi

for tool in aapt2 d8 zipalign apksigner; do
  if [[ ! -x "$BUILD_TOOLS_DIR/$tool" ]]; then
    echo "Missing Android build tool: $BUILD_TOOLS_DIR/$tool" >&2
    exit 1
  fi
done

APP_DIR="$ROOT_DIR/app"
BUILD_DIR="$APP_DIR/build"
GEN_DIR="$BUILD_DIR/generated"
CLASS_DIR="$BUILD_DIR/classes"
DEX_DIR="$BUILD_DIR/dex"
OUT_DIR="$BUILD_DIR/outputs/apk/debug"
KEYSTORE_DIR="$ROOT_DIR/keystore"
KEYSTORE="$KEYSTORE_DIR/debug.keystore"
UNSIGNED_APK="$BUILD_DIR/CoinPush-unsigned.apk"
ALIGNED_APK="$BUILD_DIR/CoinPush-aligned.apk"
SIGNED_APK="$OUT_DIR/CoinPush-debug.apk"
INSTALLABLE_APK="$ROOT_DIR/CoinPush-debug.apk"

rm -rf "$BUILD_DIR"
mkdir -p "$GEN_DIR" "$CLASS_DIR" "$DEX_DIR" "$OUT_DIR" "$KEYSTORE_DIR"

"$BUILD_TOOLS_DIR/aapt2" compile \
  --dir "$APP_DIR/src/main/res" \
  -o "$BUILD_DIR/resources.zip"

"$BUILD_TOOLS_DIR/aapt2" link \
  -I "$PLATFORM_DIR/android.jar" \
  --manifest "$APP_DIR/src/main/AndroidManifest.xml" \
  --java "$GEN_DIR" \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  -o "$UNSIGNED_APK" \
  "$BUILD_DIR/resources.zip"

JAVA_FILES=()
while IFS= read -r file; do
  JAVA_FILES+=("$file")
done < <(find "$APP_DIR/src/main/java" "$GEN_DIR" -name '*.java' -print)
"$JAVAC_BIN" \
  -source 8 \
  -target 8 \
  -encoding UTF-8 \
  -bootclasspath "$PLATFORM_DIR/android.jar" \
  -d "$CLASS_DIR" \
  "${JAVA_FILES[@]}"

CLASS_FILES=()
while IFS= read -r file; do
  CLASS_FILES+=("$file")
done < <(find "$CLASS_DIR" -name '*.class' -print)
"$BUILD_TOOLS_DIR/d8" \
  --min-api 26 \
  --output "$DEX_DIR" \
  "${CLASS_FILES[@]}"

(
  cd "$DEX_DIR"
  zip -q -r "$UNSIGNED_APK" classes.dex
)

if [[ ! -f "$KEYSTORE" ]]; then
  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storepass android \
    -keypass android \
    -alias androiddebugkey \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=CoinPush,C=US" \
    >/dev/null
fi

"$BUILD_TOOLS_DIR/zipalign" -f -p 4 "$UNSIGNED_APK" "$ALIGNED_APK"
"$BUILD_TOOLS_DIR/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$SIGNED_APK" \
  "$ALIGNED_APK"
"$BUILD_TOOLS_DIR/apksigner" verify "$SIGNED_APK"
cp "$SIGNED_APK" "$INSTALLABLE_APK"

echo "$INSTALLABLE_APK"
