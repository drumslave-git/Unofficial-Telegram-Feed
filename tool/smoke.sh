#!/usr/bin/env bash
# Installs an APK of the app on the connected device or emulator, opens it and fails when the
# app crashes, stops responding or is not on the screen after the launch.
#
#   tool/smoke.sh [--out <dir>] <apk>
#
# A fresh install opens the intro page, the first screen every new user sees. The script
# refuses to run while the app is installed, so it never replaces an installed copy and its
# data, and uninstalls the app again when it is done. --out keeps the log and a screenshot.
# Needs adb on PATH or ANDROID_HOME.
set -euo pipefail

PKG=org.unofficial.telegramfeed
WAIT=20   # seconds the app has to keep running after the launch

out=""
while [ $# -gt 0 ]; do
  case "$1" in
    --out) out="$2"; shift 2 ;;
    -*) echo "unknown argument $1" >&2; exit 2 ;;
    *) break ;;
  esac
done
if [ $# -ne 1 ]; then
  echo "usage: tool/smoke.sh [--out <dir>] <apk>" >&2
  exit 2
fi
apk="$1"
if ! command -v adb > /dev/null; then
  adb() { "${ANDROID_HOME:?adb is not on PATH and ANDROID_HOME is not set}/platform-tools/adb" "$@"; }
fi

adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
if adb shell pm list packages "$PKG" | tr -d '\r' | grep -Fx "package:$PKG" > /dev/null; then
  echo "$PKG is installed on the device; uninstall it or use another emulator" >&2
  exit 1
fi

adb install "$apk"
trap 'adb uninstall "$PKG" > /dev/null || true' EXIT
adb logcat -b main -b system -b crash -c
if ! adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1; then
  echo "the launcher activity of $PKG did not start" >&2
  exit 1
fi
sleep "$WAIT"

log=$(adb logcat -d -b main -b system -b crash)
top=$(adb shell dumpsys activity activities | tr -d '\r' | grep -m1 -E 'topResumedActivity|mResumedActivity' || true)
fail=""
if grep -qF "Process: $PKG, PID" <<< "$log"; then
  fail="the app crashed"
elif grep -qF ">>> $PKG <<<" <<< "$log"; then
  fail="the app crashed in native code"
elif grep -qF "ANR in $PKG" <<< "$log"; then
  fail="the app stopped responding"
elif [ -z "$(adb shell pidof "$PKG" | tr -d '\r')" ]; then
  fail="the app is not running"
elif ! grep -qF " $PKG/" <<< "$top"; then
  fail="the app is not on the screen ($top)"
fi

if [ -n "$out" ]; then
  mkdir -p "$out"
  printf '%s\n' "$log" > "$out/logcat.txt"
  adb exec-out screencap -p > "$out/screen.png"
fi
if [ -n "$fail" ]; then
  echo "smoke test failed: $fail" >&2
  adb logcat -d -b crash >&2
  exit 1
fi
echo "smoke test passed: $PKG kept running for ${WAIT}s after the launch"
