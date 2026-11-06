#!/usr/bin/env bash
# Qubic Tower dev harness (macOS). Pinned to one emulator: DEV (borrowed with the factory's emu_borrow.py).
#   tools/qt.sh cmd NAME [ARG]     debug broadcast:  am broadcast -a io.embodit.qubictower.DEBUG --es cmd NAME --es a ARG
#   tools/qt.sh shot OUT.png       still capture of the device (pico-cli capture screenshot)
#   tools/qt.sh log [REGEX]        logcat lines of tag QubicTower (optionally filtered)
#   tools/qt.sh install            install the debug APK and start the app
set -u
DEV="${DEV:-emulator-5562}"
PKG=io.embodit.qubictower
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
HERE="$(cd "$(dirname "$0")" && pwd)"
case "${1:-}" in
  cmd) "$ADB" -s "$DEV" shell am broadcast -a $PKG.DEBUG -p $PKG --es cmd "$2" --es a "\"${3:-}\"" >/dev/null ;;
  shot) pico-cli capture screenshot --device "$DEV" --out "$2" --replace >/dev/null ;;
  log) "$ADB" -s "$DEV" logcat -d -s QubicTower:V | grep -E "${2:-.}" ;;
  install) "$ADB" -s "$DEV" install -r -d "$HERE/../app/build/outputs/apk/debug/app-debug.apk" && "$ADB" -s "$DEV" shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 ;;
  *) sed -n 2,7p "$0" ;;
esac
