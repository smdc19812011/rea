#!/bin/sh
# Rebuilds app/src/main/assets/apk-template/classes.dex from src/ (needs JDK + Android SDK build-tools 35, platform 35).
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-/opt/android-sdk}
rm -rf out && mkdir -p out/classes
javac --release 8 -cp "$SDK/platforms/android-35/android.jar" -d out/classes $(find src -name '*.java')
"$SDK/build-tools/35.0.0/d8" --min-api 24 --release --lib "$SDK/platforms/android-35/android.jar" --output out $(find out/classes -name '*.class')
cp out/classes.dex ../../app/src/main/assets/apk-template/classes.dex
rm -rf out
echo "classes.dex updated"
