#!/usr/bin/env bash
# Builds app/build/my-home.apk with plain SDK tools: no Gradle, no libraries.
# Needs a JDK, aapt, dx (dalvik-exchange), zipalign, apksigner and an android.jar.
#   Ubuntu: apt-get install aapt apksigner zipalign dalvik-exchange
#   ANDROID_JAR=/path/to/platforms/android-30/android.jar ./build.sh
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

VERSION_CODE=${VERSION_CODE:-1}
VERSION_NAME=${VERSION_NAME:-1.0}
JAR=${ANDROID_JAR:-$HOME/sdk/android-30.jar}
DX=$(command -v dalvik-exchange || command -v dx)
[[ -f $JAR ]] || { echo "error: android.jar not found at $JAR (set ANDROID_JAR)" >&2; exit 1; }

KEYSTORE=${HOME_KEYSTORE:-$PWD/release.keystore}
KEYPASS=${HOME_KEYPASS:-myhomekey}
if [[ ! -f $KEYSTORE ]]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias home -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$KEYPASS" -keypass "$KEYPASS" -dname "CN=My Home" >/dev/null
  echo "created signing key $KEYSTORE (keep it: updates must be signed with the same key)"
fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/my-home.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/gen" "$WORK/classes" build

aapt package -f -M AndroidManifest.xml -S res -A assets -I "$JAR" -J "$WORK/gen" \
  --min-sdk-version 28 --target-sdk-version 28 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -F "$WORK/app.apk"

find src "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
javac --release 8 -Xlint:all -Xlint:-options -encoding UTF-8 \
  -classpath "$JAR" -d "$WORK/classes" @"$WORK/sources.txt"

"$DX" --dex --min-sdk-version=28 --output="$WORK/classes.dex" "$WORK/classes"
(cd "$WORK" && zip -q -X app.apk classes.dex)

zipalign -f -p 4 "$WORK/app.apk" "$WORK/aligned.apk"
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
  --out build/my-home.apk "$WORK/aligned.apk"
apksigner verify build/my-home.apk
printf 'built build/my-home.apk  %s bytes\n' "$(wc -c < build/my-home.apk | tr -d ' ')"
