#!/usr/bin/env bash
# Builds the app with gradle's output kept in a log and only the result on the terminal.
#
#   tool/build.sh [debug|release|ci] [--install] [--run] [<gradle args>...]
#
# debug (the default) builds the debug APK, release the x86_64 release APK for hands-on passes
# (docs/ARCHITECTURE.md, section 2) and ci runs tool/ci.sh. The log is build/tool-build.log.
# On success the script prints the build time, the APK and the number of warnings in the fork's
# files; on failure it prints the compiler errors and gradle's "What went wrong" section, at most
# 80 lines, and exits with gradle's code. --install installs the APK on the connected device or
# emulator and --run opens it.
# Needs JAVA_HOME (JDK 17; Gradle's Temurin 17 under ~/.gradle/jdks is used when it is unset)
# and ANDROID_HOME.
set -uo pipefail
cd "$(dirname "$0")/.."

mode=debug; install=0; run=0; args=()
while [ $# -gt 0 ]; do
  case "$1" in
    debug|release|ci) mode="$1"; shift ;;
    --install) install=1; shift ;;
    --run) run=1; shift ;;
    -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
    *) args+=("$1"); shift ;;
  esac
done
if [ -z "${JAVA_HOME:-}" ]; then
  for d in "$HOME"/.gradle/jdks/*17*/; do
    if [ -x "$d/bin/java" ] || [ -x "$d/bin/java.exe" ]; then export JAVA_HOME="${d%/}"; break; fi
  done
fi
if ! command -v adb > /dev/null; then
  adb() { "${ANDROID_HOME:?adb is not on PATH and ANDROID_HOME is not set}/platform-tools/adb" "$@"; }
fi

mkdir -p build
log=build/tool-build.log
apk=TMessagesProj_App/build/outputs/apk/afat/debug/app.apk
case "$mode" in
  debug)   ./gradlew --console=plain :TMessagesProj_App:assembleAfatDebug "${args[@]}" > "$log" 2>&1 ;;
  release) apk=TMessagesProj_App/build/outputs/apk/afat/release/app.apk
           ./gradlew --console=plain -PTGFEED_ABIS=x86_64 :TMessagesProj_App:assembleAfatRelease "${args[@]}" > "$log" 2>&1 ;;
  ci)      tool/ci.sh > "$log" 2>&1 ;;
esac
code=$?

if [ $code -ne 0 ]; then
  {
    grep -n -A3 -E '(^|[^A-Za-z])error:|^e: ' "$log"
    awk '/^\* What went wrong:/{p=1} /^\* Try:/{p=0} p' "$log"
    grep -E ' FAILED$|tests completed,' "$log"
  } | head -80
  echo "build failed (exit $code); the full log is $log" >&2
  exit $code
fi

grep -E '^BUILD SUCCESSFUL' "$log" || tail -1 "$log"
echo "$apk ($(du -h "$apk" | cut -f1))"
warnings=$(grep -c -E 'telegramfeed.*warning:' "$log")
[ "$warnings" -gt 0 ] && echo "$warnings warnings in the fork's files (see $log)"
if [ $install -eq 1 ]; then
  adb install -r "$apk" | tail -1
fi
if [ $run -eq 1 ]; then
  adb shell monkey -p org.unofficial.telegramfeed -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
fi
exit 0
