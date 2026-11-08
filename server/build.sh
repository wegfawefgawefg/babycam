#!/bin/bash
# Builds out/babycam.apk: the phone app (GO button, boot start) plus the camera server,
# which the starter runs as shell via app_process with CLASSPATH pointing at the installed APK.
set -e
cd "$(dirname "$0")"
# Point these at your Android build-tools and an android.jar (API 33), or put them in
# server/env.local (not committed).
[ -f env.local ] && . ./env.local
BT=${ANDROID_BUILD_TOOLS:?set ANDROID_BUILD_TOOLS to an Android build-tools directory}
AJ=${ANDROID_JAR:?set ANDROID_JAR to platforms/android-33/android.jar}
rm -rf out && mkdir -p out/classes out/res
javac -nowarn -source 11 -target 11 -classpath "$AJ" -d out/classes $(find src -name '*.java') 2>&1 | grep -vE '^(Note|warning)' || true
$BT/d8 --lib "$AJ" --min-api 29 --output out $(find out/classes -name '*.class')
$BT/aapt2 compile --dir res -o out/res.zip
$BT/aapt2 link -o out/unsigned.apk --manifest AndroidManifest.xml -I "$AJ" -A assets out/res.zip
(cd out && zip -qj unsigned.apk classes.dex)
$BT/zipalign -f 4 out/unsigned.apk out/aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias k -keyalg RSA -validity 10000 -dname CN=babycam >/dev/null 2>&1
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android --out out/babycam.apk out/aligned.apk
echo built out/babycam.apk
