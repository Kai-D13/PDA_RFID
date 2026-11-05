#!/usr/bin/env bash
# Builds a signed APK without Gradle / Android Studio, using the command-line Android tools
# packaged by Ubuntu/Debian. Use this when Google's SDK download servers are not reachable;
# otherwise Android Studio (or ./gradlew assembleRelease) on this same project works too.
#
#   sudo apt-get install -y openjdk-17-jdk aapt zipalign dalvik-exchange apksigner
#   ./build_apk.sh                     # -> dist/rfid-tem-check-<version>.apk
#
# Needs an android.jar for API 34. Set ANDROID_JAR, or it is looked up in $ANDROID_HOME and
# then downloaded once into .build-cache/ from the Sable/android-platforms mirror on GitHub.
set -euo pipefail
cd "$(dirname "$0")"

VERSION_NAME="1.0.0"
VERSION_CODE=1
MIN_SDK=21
TARGET_SDK=34
PACKAGE="com.buymed.rfidtag"
ZXING_VERSION="3.3.3"

CACHE=.build-cache
OUT=build/manual
mkdir -p "$CACHE" dist
rm -rf "$OUT" && mkdir -p "$OUT"/{gen,classes,res-manifest}

# ---- inputs -------------------------------------------------------------------------------
if [ -z "${ANDROID_JAR:-}" ]; then
  if [ -n "${ANDROID_HOME:-}" ] && [ -f "$ANDROID_HOME/platforms/android-$TARGET_SDK/android.jar" ]; then
    ANDROID_JAR="$ANDROID_HOME/platforms/android-$TARGET_SDK/android.jar"
  else
    ANDROID_JAR="$CACHE/android-$TARGET_SDK.jar"
    [ -f "$ANDROID_JAR" ] || curl -fSL -o "$ANDROID_JAR" \
      "https://raw.githubusercontent.com/Sable/android-platforms/master/android-$TARGET_SDK/android.jar"
  fi
fi
ZXING_JAR="$CACHE/zxing-core-$ZXING_VERSION.jar"
[ -f "$ZXING_JAR" ] || curl -fSL -o "$ZXING_JAR" \
  "https://repo.maven.apache.org/maven2/com/google/zxing/core/$ZXING_VERSION/core-$ZXING_VERSION.jar"

KEYSTORE="${KEYSTORE:-signing/rfidtag-release.jks}"
KS_PASS="${KS_PASS:-rfidtag123}"
KEY_ALIAS="${KEY_ALIAS:-rfidtag}"
if [ ! -f "$KEYSTORE" ]; then
  echo ">> Creating signing key $KEYSTORE (keep this file: updates must be signed with the same key)"
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=RFID Tem Check, O=Buymed, C=VN" >/dev/null 2>&1
fi

# ---- 1. resources -------------------------------------------------------------------------
# Gradle injects the package name from build.gradle; aapt needs it in the manifest itself.
sed "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$PACKAGE\">|" \
  app/src/main/AndroidManifest.xml > "$OUT/res-manifest/AndroidManifest.xml"

echo ">> aapt package"
aapt package -f -m \
  -M "$OUT/res-manifest/AndroidManifest.xml" \
  -S app/src/main/res \
  -A app/src/main/assets \
  -I "$ANDROID_JAR" \
  -J "$OUT/gen" \
  -F "$OUT/app.unaligned.apk" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -0 dat \
  --auto-add-overlay

# ---- 2. compile ---------------------------------------------------------------------------
echo ">> javac"
find app/src/main/java "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" -classpath "$ZXING_JAR" \
  -d "$OUT/classes" @"$OUT/sources.txt" 2>&1 | grep -v -E "bootstrap|source value 8|target value 8|To suppress|^warning: \[options\]|^[0-9]+ warning" || true
[ -f "$OUT/classes/com/buymed/rfidtag/MainActivity.class" ] || { echo "javac failed"; exit 1; }

# ---- 3. dex -------------------------------------------------------------------------------
echo ">> dx"
dalvik-exchange --dex --min-sdk-version="$MIN_SDK" --output="$OUT/classes.dex" "$OUT/classes" "$ZXING_JAR"

# ---- 4. package, align, sign --------------------------------------------------------------
echo ">> add dex, zipalign, sign"
(cd "$OUT" && aapt add -f app.unaligned.apk classes.dex >/dev/null)
zipalign -f -p 4 "$OUT/app.unaligned.apk" "$OUT/app.aligned.apk"
APK="dist/rfid-tem-check-$VERSION_NAME.apk"
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --ks-key-alias "$KEY_ALIAS" \
  --min-sdk-version "$MIN_SDK" --out "$APK" "$OUT/app.aligned.apk"
apksigner verify "$APK"
echo ">> OK: $APK ($(du -h "$APK" | cut -f1))"
