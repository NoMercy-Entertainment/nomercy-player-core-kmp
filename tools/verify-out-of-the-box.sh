#!/usr/bin/env bash
# The only proof that counts: a machine with no VLC on it plays a video.
#
# It cannot be proven on a developer's own machine, because a developer's
# machine has VLC — that is why the defect survived this long. A directory can
# be renamed and a PATH can be scrubbed, but the registry key stays, the JVM's
# working directory is still searched, and every one of those isolations is
# something somebody has to remember. So the proof runs in a container that has
# never had VLC installed and never will: nothing to hide, nothing to remember.
#
# The consumer inside it depends on the published coordinate and nothing else.
# It never names libVLC and never looks for an install — exactly what somebody
# adding this library to their own project writes.
#
#   tools/verify-out-of-the-box.sh 2.0.0-rc.1
set -euo pipefail

version="${1:-2.0.0-rc.1}"
here="$(cd "$(dirname "$0")/.." && pwd)"
consumer="$here/tools/consumer-check"
payloads="$here/build/native-payloads"

command -v docker >/dev/null || { echo "docker is required"; exit 1; }

echo "── building the consumer against tv.nomercy:nomercy-player-core-kmp:$version"
( cd "$here" && ./gradlew publishToMavenLocal -q --console=plain )
# The parent wrapper, pointed at this project. A second wrapper checked in
# beside it would be one more thing to keep at the same Gradle version.
"$here/gradlew" -p "$consumer" installDist -PplayerVersion="$version" -q --console=plain --no-configuration-cache

cp "$here/src/jvmTest/resources/fixtures/two-audio.mkv" "$consumer/media.mkv"

# file: rather than http: on purpose. The payload is fetched, digest-checked and
# unpacked by exactly the code a real consumer runs, and the container has no
# network at all — so nothing here can be passing because something else on the
# machine answered.
MSYS_NO_PATHCONV=1 docker run --rm --network none \
  -v "$consumer/build/install/nomercy-player-consumer-check:/app:ro" \
  -v "$consumer/media.mkv:/media.mkv:ro" \
  -v "$payloads:/mirror:ro" \
  -v "$here/tools/verify-in-container.sh:/verify.sh:ro" \
  nomercy-verify-clean bash /verify.sh

# The second half, on a different machine on purpose: a bare JDK with no window
# system on it at all. Playing needs somewhere to draw and this image has
# nowhere, so it constructs an engine and stops — which is the half that used to
# be impossible, because the old binding loaded AWT to build a media player.
echo
echo "── and again on an image with no X libraries, to construct one"
MSYS_NO_PATHCONV=1 docker run --rm --network none \
  -v "$consumer/build/install/nomercy-player-consumer-check:/app:ro" \
  -v "$payloads:/mirror:ro" \
  -v "$here/tools/verify-headless-in-container.sh:/verify.sh:ro" \
  eclipse-temurin:21-jdk bash /verify.sh
