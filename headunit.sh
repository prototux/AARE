#!/bin/sh
# Pseudo head unit on the desktop, for a phone (or emulator) connected with adb.
#
#   ./headunit.sh                 AARE on the phone, through AARE's own desktop head unit
#   ./headunit.sh google          the official Android Auto app, through Google's Desktop Head Unit
#
# Both follow a simulated Paris -> Marseille drive: AARE's head unit sends it as the car's GPS,
# and on an emulator the phone's own GPS is moved along it too (so apps using the phone's location
# follow as well). Extra options are passed to the head unit, e.g.:
#   ./headunit.sh --res 1920x1080 --time-scale 10 --speed 110 --rhd
#   ./headunit.sh google --time-scale 60
set -e
# before the cd, while "$0" still resolves; prints the comment block above
case "$1" in
    -h|--help)
        awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0"
        exit 0 ;;
esac
cd "$(dirname "$0")"
ROOT=$(pwd)

MODE=aare
case "$1" in
    google|official|aa) MODE=google; shift ;;
    aare) shift ;;
esac

command -v adb >/dev/null || { echo "adb not found (Android SDK platform-tools)" >&2; exit 1; }
SERIAL=${ANDROID_SERIAL:-$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')}
[ -n "$SERIAL" ] || { echo "No phone or emulator found by adb. Plug one in (USB debugging on) or start an emulator." >&2; exit 1; }
export ANDROID_SERIAL=$SERIAL
echo "Phone: $SERIAL"

# AARE listens on the abstract unix socket @aare_hu (only adb may connect); the official app listens on TCP 5277.
if [ "$MODE" = aare ]; then
    adb forward tcp:5277 localabstract:aare_hu >/dev/null
    command -v ffmpeg >/dev/null || { echo "ffmpeg not found: install it (it decodes the car screen)" >&2; exit 1; }
    if ! adb shell pm path net.prototux.aare >/dev/null 2>&1; then
        echo "AARE isn't installed on $SERIAL: ./build.sh && adb install -r app/build/outputs/apk/debug/app-debug.apk" >&2
        exit 1
    fi
    # Debug builds of AARE turn their head unit server on when asked (release builds: toggle it in the app).
    adb shell am start -n net.prototux.aare/.ui.MainActivity --ez hu_server true >/dev/null 2>&1 || true
    # adb forward accepts connections even when nothing listens on the phone, so check the phone itself
    # (an abstract socket shows as @aare_hu in /proc/net/unix).
    listening() { adb shell cat /proc/net/unix 2>/dev/null | grep -q '@aare_hu'; }
    i=0
    while ! listening && [ $i -lt 10 ]; do sleep 1; i=$((i + 1)); done
    if ! listening; then
        echo "AARE's head unit server isn't listening on the phone yet. On the phone, open AARE and turn on" >&2
        echo "\"Head unit server\" (release builds always need this). The head unit keeps retrying." >&2
    fi

    BIN="$ROOT/headunit/build/install/aare-headunit/bin/aare-headunit"
    # Rebuild when the sources' contents changed (not their dates: copies and syncs keep old dates).
    STAMP="$ROOT/headunit/build/sources.cksum"
    SUM=$(find "$ROOT/headunit/src" "$ROOT/aap/src" "$ROOT/headunit/build.gradle.kts" "$ROOT/aap/build.gradle.kts" -type f | sort | xargs cat | cksum)
    if [ ! -x "$BIN" ] || [ "$(cat "$STAMP" 2>/dev/null)" != "$SUM" ]; then
        echo "Building the desktop head unit…"
        "$ROOT/build.sh" :headunit:installDist --console=plain -q
        echo "$SUM" > "$STAMP"
    fi
    exec "$BIN" --serial "$SERIAL" "$@"
fi

# ---- official Android Auto, with Google's Desktop Head Unit (DHU)
adb forward tcp:5277 tcp:5277 >/dev/null
DHU=""
for d in "${ANDROID_HOME:-$HOME/Android/Sdk}/extras/google/auto" "$HOME/.cache/aare/dhu"; do
    [ -x "$d/desktop-head-unit" ] && DHU="$d" && break
done
if [ -z "$DHU" ]; then
    echo "Downloading Google's Desktop Head Unit (Android SDK package extras;google;auto)…"
    DHU="$HOME/.cache/aare/dhu"
    mkdir -p "$DHU"
    curl -fsSL -o "$DHU/dhu.zip" https://dl.google.com/android/repository/desktop-head-unit-linux-x64_r02.1.zip
    unzip -q -o "$DHU/dhu.zip" -d "$DHU" && rm "$DHU/dhu.zip"
fi
# The DHU needs libc++; the Android NDK has one if the system doesn't.
if ldd "$DHU/desktop-head-unit" 2>/dev/null | grep -q 'not found'; then
    NDKLIB=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/lib/x86_64-unknown-linux-gnu 2>/dev/null | tail -1)
    export LD_LIBRARY_PATH="$DHU:$NDKLIB${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi

cat <<'EOF'
On the phone, in Android Auto: Settings > tap "Version" 10 times > menu (⋮) > Developer settings,
then menu > "Start head unit server". The DHU connects as soon as it runs.
DHU console: "day"/"night", "restrict"/"unrestrict" (driving), "mic begin", "keycode back"...
EOF

# The route through the phone's GPS (emulators only: real phones need a mock-location app).
GPS_PID=""
case "$SERIAL" in emulator-*)
    BIN="$ROOT/headunit/build/install/aare-headunit/bin/aare-headunit"
    [ -x "$BIN" ] || "$ROOT/build.sh" :headunit:installDist --console=plain -q
    AARE_HEADUNIT_GPS_ONLY=1 "$BIN" --gps-only --serial "$SERIAL" "$@" &
    GPS_PID=$!
    trap '[ -n "$GPS_PID" ] && kill $GPS_PID 2>/dev/null' EXIT INT TERM
    echo "Driving the emulator's GPS from Paris to Marseille (pid $GPS_PID)."
    ;;
esac

CONFIG="$DHU/config/default_720p.ini"
for a in "$@"; do case "$a" in 1920x1080) CONFIG="$DHU/config/default_1080p.ini" ;; 800x480) CONFIG="$DHU/config/default.ini" ;; esac; done
"$DHU/desktop-head-unit" -c "$CONFIG" -a
