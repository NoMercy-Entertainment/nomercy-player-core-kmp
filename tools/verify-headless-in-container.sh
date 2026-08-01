#!/usr/bin/env bash
# Runs INSIDE a BARE JDK image. Proves the desktop engine can be CONSTRUCTED on a
# machine with no window system on it at all.
#
# It is a separate proof from verify-in-container.sh because it needs a
# different machine. That one has xvfb and a sound stack, because a desktop has
# them and playing a file needs somewhere to draw; this one has nothing on top
# of the JDK, because the question here is whether building an engine drags in a
# toolkit.
#
# It used to. The previous binding built an overlay in the media player's
# constructor, which touched java.awt.Rectangle, which loaded AWT, which wanted
# libXtst — so `new VlcjVideoBackend()` threw on a headless Linux box before
# anything had been asked to play. The direct binding calls libvlc_new and
# libvlc_media_player_new and stops, and this is what says so.
set -euo pipefail

echo "── this machine's idea of a display"
for name in libX11 libXext libXtst libXrender libXi libxcb; do
  ldconfig -p 2>/dev/null | grep -q "$name\." && { echo "FAIL: $name is installed — this is not a headless machine"; exit 1; }
done
echo "   no X client libraries at all"
[ -z "${DISPLAY:-}" ] || { echo "FAIL: DISPLAY is set to $DISPLAY"; exit 1; }
echo "   no DISPLAY"

# The release layout the library expects, built from the mount. Same as the
# playback proof: the payload is fetched, digest-checked and unpacked by exactly
# the code a real consumer runs, and there is no network here.
mkdir -p /tmp/mirror/natives-libvlc-3.0.23
cp /mirror/libvlc-3.0.23-linux-x64.tar.gz /tmp/mirror/natives-libvlc-3.0.23/

echo
echo "── the consumer, constructing an engine and nothing else"
JAVA_OPTS="-Dnomercy.player.natives.baseUrl=file:///tmp/mirror" \
  /app/bin/nomercy-player-consumer-check --bind-only

echo
echo "PASS: the desktop engine was constructed with no window system present."
