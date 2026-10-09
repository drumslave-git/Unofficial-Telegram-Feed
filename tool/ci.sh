#!/usr/bin/env bash
# What CI runs, runnable locally: the fork's JVM unit tests and the debug APK.
# Needs JAVA_HOME (JDK 17) and ANDROID_HOME. Exits with the code of the first failing step.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew --console=plain :TMessagesProj_FeedTests:test :TMessagesProj_App:assembleAfatDebug
