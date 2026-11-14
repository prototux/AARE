#!/bin/sh
# Draws the car screen mockups for the README (docs/screenshots/styles) with Paparazzi, on the JVM:
# no emulator, no phone. They are mockups: made-up apps and music, a drawn map; a real car screen
# can look a bit different. Needs ~3 GB of memory.
set -e
cd "$(dirname "$0")/.."
./build.sh :app:recordPaparazziDebug -Pmockups --no-configuration-cache --tests '*CarScreenMockups*' "$@"
mkdir -p docs/screenshots/styles
for f in app/src/test/snapshots/images/*CarScreenMockups*.png; do
    # net.prototux.aare.ui.car_CarScreenMockups_retroAmber.png -> retroAmber.png
    name=$(basename "$f" .png | sed 's/.*CarScreenMockups_//')
    cp "$f" "docs/screenshots/styles/$name.png"
done
rm -rf app/src/test/snapshots
ls docs/screenshots/styles
