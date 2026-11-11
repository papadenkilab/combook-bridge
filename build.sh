#!/bin/bash
# Manual Android build (no Gradle): javac -> d8 -> aapt -> zipalign -> apksigner
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-$HOME/android-sdk}"
PLATFORM="$SDK/platforms/android-34/android.jar"
BT="$SDK/build-tools/34.0.0"
OUT="build"
VERSION="v1.0.0"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/6] javac"
javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$PLATFORM" -d "$OUT/classes" \
  $(find src -name '*.java')

echo "[2/6] d8"
"$BT/d8" --lib "$PLATFORM" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[3/6] aapt package"
"$BT/aapt" package -f -M AndroidManifest.xml -S res -I "$PLATFORM" -F "$OUT/unsigned.apk"

echo "[4/6] add dex"
cd "$OUT"
zip -q unsigned.apk dex/classes.dex -j
cd ..

echo "[5/6] zipalign + keystore"
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi

echo "[6/6] apksigner"
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out "combook-bridge-$VERSION.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "combook-bridge-$VERSION.apk" | head -3
ls -lh "combook-bridge-$VERSION.apk"
echo "DONE: combook-bridge-$VERSION.apk"
