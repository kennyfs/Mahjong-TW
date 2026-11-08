#!/bin/bash
# The app's screenshots on a local emulator (scripts/shots.py: practice, a hand's result, its review and a decision's
# analysis, the analysis screen, the records, the settings and rules, battles), landscape first, then portrait, into
# $OUT (default $WORK/shots/android). With BATTLE_SERVER (e.g. 10.0.2.2:8091, a battle server on
# this machine) also a table game's lobby and table; ONLY=name,... only those shots.
#
# Starts a headless emulator (AVD "mahjong-$API-pixel9", made on the first run: a Pixel 9, 2424 x 1080 at 420 dpi, the
# phone the screenshots are judged at) on port $PORT (default 5556: Thirteen's uses 5554), sets it up as the user's phone
# (font scale 1.15, a hole cutout, no animations, light mode), installs the debug APK (built first unless NOBUILD=1) and
# stops the emulator after, unless KEEP=1. Needs the emulator and system-images;android-$API;google_apis;x86_64 in the
# SDK (API defaults to 37.0), and /dev/kvm. Paths as in build-apk.sh, plus ANDROID_AVD_HOME (default ~/.android/avd).
# The log is device-tests.log.
set -euo pipefail
cd "$(dirname "$0")"
if [ -f local.env ]; then set -a; . ./local.env; set +a; fi
export ANDROID_HOME=${ANDROID_HOME:-~/Android/Sdk}
export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-~/.android/avd}
GRADLE=${GRADLE:-gradle}
ADB=$ANDROID_HOME/platform-tools/adb
PORT=${PORT:-5556}
# Only ever this emulator: the script changes the display and font settings of the device it runs on.
export ANDROID_SERIAL=emulator-$PORT
API=${API:-37.0}
IMAGE="system-images;android-$API;google_apis;x86_64"
AVD=mahjong-$API-pixel9
exec > >(tee device-tests.log) 2>&1

if [ "${NOBUILD:-0}" != 1 ]; then
  # shellcheck disable=SC2086
  "$GRADLE" --no-daemon ${GRADLE_FLAGS:-} assembleDebug
fi
if [ ! -d "$ANDROID_AVD_HOME/$AVD.avd" ]; then
  mkdir -p "$ANDROID_AVD_HOME"
  echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d pixel_9
fi
started=0
if ! "$ADB" get-state > /dev/null 2>&1; then
  "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -port "$PORT" -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect > emulator.log 2>&1 &
  started=1
fi
stop() {
  if [ "$started" = 1 ] && [ "${KEEP:-0}" != 1 ]; then "$ADB" emu kill > /dev/null 2>&1 || true; fi
}
trap stop EXIT
"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
for s in window_animation_scale transition_animation_scale animator_duration_scale; do "$ADB" shell settings put global $s 0; done
# The user's phone: font scale 1.15 and a hole cutout (memory: screenshot standard).
"$ADB" shell settings put system font_scale 1.15
"$ADB" shell cmd overlay enable com.android.internal.display.cutout.emulation.hole
"$ADB" shell cmd uimode night no
# The app is full screen when sideways: the system's one-time "Viewing full screen" hint would cover it.
"$ADB" shell settings put secure immersive_mode_confirmations confirmed
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
python3 scripts/shots.py
