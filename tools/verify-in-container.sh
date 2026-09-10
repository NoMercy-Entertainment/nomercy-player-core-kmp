#!/usr/bin/env bash
# Runs INSIDE the clean container. Proves three things, in this order, because
# each one is worthless without the one before it.
set -euo pipefail

echo "── this machine's idea of VLC"
for path in /usr/bin/vlc /usr/lib/*/libvlc.so.5 /usr/lib/*/vlc /usr/local/lib/libvlc*; do
  [ -e "$path" ] && { echo "FAIL: $path exists — this is not a clean machine"; exit 1; }
done
echo "   no vlc binary, no libvlc, no plugin directory, no libass"
ldconfig -p 2>/dev/null | grep -E '\blibvlc\.|\blibvlccore\.|\blibass\.' && { echo "FAIL: the linker knows about one"; exit 1; }
echo "   the dynamic linker has never heard of either"

# The release layout the library expects, built from the mount. A real consumer
# reaches github.com for this; there is no network here, which is the point.
mkdir -p /tmp/mirror/natives-libvlc-3.0.23
cp /mirror/libvlc-3.0.23-linux-x64.tar.gz /tmp/mirror/natives-libvlc-3.0.23/

echo
echo "── the consumer, which knows only the coordinate"
cd /tmp
time JAVA_OPTS="-Dnomercy.player.natives.baseUrl=file:///tmp/mirror" \
  xvfb-run -a /app/bin/nomercy-player-consumer-check /media.mkv 2>/tmp/vlc.log \
  || { echo; echo "── libvlc said:"; tail -40 /tmp/vlc.log; exit 1; }

echo
echo "── second run, payload already unpacked"
time JAVA_OPTS="-Dnomercy.player.natives.baseUrl=file:///tmp/mirror" \
  xvfb-run -a /app/bin/nomercy-player-consumer-check /media.mkv 2>/dev/null

echo
echo "PASS: a machine with no VLC installed played the file."
