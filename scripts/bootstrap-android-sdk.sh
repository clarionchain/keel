#!/usr/bin/env bash
set -euo pipefail
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JDK 17 not found at $JAVA_HOME" >&2
  exit 1
fi
if [[ ! -d "$ANDROID_SDK_ROOT/platforms/android-35" ]]; then
  echo "Android SDK 35 not found at $ANDROID_SDK_ROOT" >&2
  echo "Install cmdline-tools and: sdkmanager platform-tools platforms;android-35 build-tools;35.0.0" >&2
  exit 1
fi
mkdir -p "$(dirname "$0")/../android"
echo "sdk.dir=$ANDROID_SDK_ROOT" > "$(dirname "$0")/../android/local.properties"
echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
echo "wrote android/local.properties"
