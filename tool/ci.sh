#!/usr/bin/env bash
# What CI runs, runnable locally: the debug APK and the JVM unit tests of the app module.
# Needs JAVA_HOME (JDK 17) and ANDROID_HOME. Exits with the code of the first failing step.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew --console=plain :TMessagesProj_App:assembleAfatDebug :TMessagesProj_App:testAfatDebugUnitTest
