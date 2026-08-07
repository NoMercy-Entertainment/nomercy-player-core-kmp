#!/usr/bin/env bash
# Build one desktop native payload: the archive the JVM target downloads.
#
# A payload is self-contained on purpose. The JVM target has no build step in a
# consumer's project to hook — a library on Maven cannot ask somebody's Gradle to
# fetch a DLL — so whatever the machine needs has to be inside one archive that
# the running library can unpack and load. That is the same arrangement the Apple
# targets already have for libass: a prebuilt archive, pinned by digest, fetched
# rather than compiled. This is the desktop half of it.
#
# Two things this does that are easy to leave out and expensive to leave out:
#
#   1. It generates plugins.dat. libVLC indexes its plugin directory on every
#      launch unless a valid cache sits beside the plugins, and indexing 363
#      libraries is seconds a user pays before the first frame.
#
#   2. It normalises every timestamp in the payload to one fixed second BEFORE
#      generating that cache. libVLC records each plugin's size AND mtime in the
#      cache and rechecks both on load, so a cache generated against timestamps
#      that do not survive packaging is a cache that goes stale on somebody
#      else's machine — which is exactly the seven-second launch this exists to
#      remove. Fixed timestamps also make the archive byte-reproducible, so two
#      builds of the same upstream produce the same digest.
#
#   tools/build-native-payload.sh libvlc windows-x64
#   tools/build-native-payload.sh libvlc linux-x64 build/payloads
set -euo pipefail

payload="${1:?usage: build-native-payload.sh <payload> <platform> [outdir]}"
platform="${2:?platform required, e.g. windows-x64}"
here="$(cd "$(dirname "$0")/.." && pwd)"
outdir="${3:-$here/build/native-payloads}"
lock="$here/tools/native-payloads.lock"

# One second, chosen once, for every file in every payload. The value is
# arbitrary; that it never moves is not.
readonly STAMP_EPOCH=1700000000
readonly STAMP_DATE="@$STAMP_EPOCH"

pin() {
  local key="$1"
  sed -n "s/^${key//./\\.}=//p" "$lock" | head -1
}

version="$(pin "$payload.version")"
kind="$(pin "$payload.$platform.kind")"
source_url="$(pin "$payload.$platform.source")"
source_sha="$(pin "$payload.$platform.sha256")"

[ -n "$version" ] || { echo "no version pinned for $payload"; exit 1; }
[ -n "$kind" ] || { echo "no source pinned for $payload/$platform"; exit 1; }

work="$outdir/work/$payload-$version-$platform"
stage="$work/stage"
rm -rf "$work"
mkdir -p "$stage" "$outdir"

# Downloaded once and kept, because these are tens of megabytes and a rebuild
# that re-downloads is a rebuild nobody runs twice.
cache="$outdir/upstream"
mkdir -p "$cache"

fetch_verified() {
  local url="$1" expected="$2" into="$3"
  if [ ! -f "$into" ]; then
    echo "── fetching $(basename "$into")"
    curl -sSL --fail --max-time 900 -o "$into.part" "$url"
    mv "$into.part" "$into"
  fi
  local actual
  actual="$(sha256sum "$into" | cut -d' ' -f1)"
  if [ -n "$expected" ] && [ "$actual" != "$expected" ]; then
    echo "digest mismatch for $url"
    echo "  expected $expected"
    echo "  actual   $actual"
    exit 1
  fi
  echo "── upstream digest $actual"
}

# ---------------------------------------------------------------------------
# Windows: the official redistributable zip, which already contains libvlc,
# libvlccore, every plugin and vlc-cache-gen. Nothing is compiled here.
# ---------------------------------------------------------------------------
build_windows() {
  local archive="$cache/$(basename "$source_url")"
  fetch_verified "$source_url" "$source_sha" "$archive"

  local unpacked="$work/upstream"
  mkdir -p "$unpacked"
  unzip -q -o "$archive" -d "$unpacked"
  local root
  root="$(find "$unpacked" -maxdepth 1 -mindepth 1 -type d | head -1)"

  cp "$root/libvlc.dll" "$root/libvlccore.dll" "$stage/"
  cp -r "$root/plugins" "$stage/plugins"
  cp "$root/COPYING.txt" "$root/AUTHORS.txt" "$root/THANKS.txt" "$stage/"
  # Kept so the cache can be regenerated on the machine that owns the payload,
  # which is the recovery path when a packaging step rewrites timestamps.
  cp "$root/vlc-cache-gen.exe" "$stage/"

  stamp_tree
  generate_windows_cache
}

# vlc-cache-gen is a Windows binary and only runs on Windows. A payload built on
# any other host is still correct, it just launches slower — so this warns and
# continues rather than failing, and the warning names the consequence.
generate_windows_cache() {
  if [ "$(uname -s)" != "MINGW64_NT-10.0" ] && [ "${OS:-}" != "Windows_NT" ]; then
    echo "!! not a Windows host: plugins.dat NOT generated, this payload will"
    echo "!! index 363 plugins on every launch. Build it on Windows for the cache."
    return
  fi
  echo "── generating plugins.dat"
  ( cd "$stage" && ./vlc-cache-gen.exe plugins )
  [ -f "$stage/plugins/plugins.dat" ] || { echo "vlc-cache-gen produced no cache"; exit 1; }
  # The cache itself gets the same fixed timestamp; only the plugins' own
  # timestamps are what it recorded, and those are already frozen.
  touch -d "$STAMP_DATE" "$stage/plugins/plugins.dat"
}

# ---------------------------------------------------------------------------
# libmpv on Windows: one DLL and its headers out of the upstream dev package.
#
# No plugin tree and no cache to generate — libmpv links its decoders in, which
# is the whole reason its payload is one file where libVLC's is three hundred
# and sixty-four.
# ---------------------------------------------------------------------------
build_windows_mpv() {
  local archive="$cache/$(basename "$source_url")"
  fetch_verified "$source_url" "$source_sha" "$archive"

  local unpacked="$work/upstream"
  mkdir -p "$unpacked"

  # 7z, because that is what upstream publishes. Windows ships bsdtar, which
  # reads a great many formats and not this one, so the extractor is looked for
  # rather than assumed — a payload build that silently produced an empty stage
  # would pack an archive with no engine in it.
  #
  # Several places, because PATH under a Gradle exec is not the PATH in a
  # terminal: the same lookup that found 7z by name when this was run by hand
  # found nothing when the build ran it, and the payload build failed on a
  # machine that has 7-Zip installed.
  local seven=""
  local candidate
  # Both mount prefixes, because the shell the BUILD runs is not the shell a
  # person runs: ProcessBuilder("bash") on Windows finds WSL's bash, where the
  # C drive is /mnt/c and Git Bash's /c does not exist at all.
  for candidate in 7z 7za                    "C:/Program Files/7-Zip/7z.exe" "C:/Program Files (x86)/7-Zip/7z.exe"                    "/mnt/c/Program Files/7-Zip/7z.exe" "/mnt/c/Program Files (x86)/7-Zip/7z.exe"; do
    if command -v "$candidate" > /dev/null 2>&1; then seven="$candidate"; break; fi
    if [ -x "$candidate" ]; then seven="$candidate"; break; fi
  done
  [ -n "$seven" ] || { echo "7z is required to unpack $(basename "$archive")"; exit 1; }
  "$seven" x -y -o"$unpacked" "$archive" > /dev/null

  local dll
  dll="$(find "$unpacked" -name "libmpv-2.dll" | head -1)"
  [ -n "$dll" ] || { echo "no libmpv-2.dll inside $(basename "$archive")"; exit 1; }
  cp "$dll" "$stage/"

  # The headers travel with it. They are what the binding was corrected
  # against — the render parameter numbers were wrong from memory and right
  # from render.h — and a payload without them makes that check need a
  # download.
  local include
  include="$(find "$unpacked" -type d -name mpv | head -1)"
  [ -n "$include" ] && cp -r "$include" "$stage/include-mpv"

  stamp_tree
}

# ---------------------------------------------------------------------------
# Linux: no official portable build exists, so the payload is assembled inside a
# container from the distribution's own VLC packages, and then closed over —
# every shared object the VLC libraries need is copied in beside them, except
# the ones every glibc system already has. Without that closure the payload
# loads on the machine that built it and nowhere else.
# ---------------------------------------------------------------------------
build_linux() {
  command -v docker >/dev/null || { echo "docker is required to build the linux payload"; exit 1; }
  local image="$source_url"
  local digest
  digest="$(docker image inspect --format '{{index .RepoDigests 0}}' "$image" 2>/dev/null || true)"
  if [ -z "$digest" ]; then
    docker pull -q "$image" >/dev/null
    digest="$(docker image inspect --format '{{index .RepoDigests 0}}' "$image")"
  fi
  echo "── base image $digest"

  # A real script file rather than an inline heredoc: a container step written
  # into a string is a step nobody can run on its own when it breaks.
  #
  # The container writes the finished archive to stdout, so the whole payload —
  # symlinks included — arrives as one file and never has to survive a bind
  # mount onto a filesystem that cannot represent it.
  MSYS_NO_PATHCONV=1 docker run --rm -i \
    -v "$here/tools/native-payload-linux.sh:/assemble.sh:ro" \
    "$image" bash /assemble.sh "$STAMP_EPOCH" "$version" > "$out"

  echo "$digest" > "$work/provenance.txt"
  packed=yes
}

# ---------------------------------------------------------------------------
# macOS: VLC ships as a .dmg carrying VLC.app. hdiutil is macOS-only, so this
# path only runs there.
# ---------------------------------------------------------------------------
build_macos() {
  [ "$(uname -s)" = "Darwin" ] || { echo "the macos payload must be built on macOS"; exit 1; }
  local archive="$cache/$(basename "$source_url")"
  fetch_verified "$source_url" "$source_sha" "$archive"

  local mount="$work/mnt"
  mkdir -p "$mount"
  hdiutil attach -nobrowse -readonly -mountpoint "$mount" "$archive" >/dev/null
  trap 'hdiutil detach "$mount" >/dev/null 2>&1 || true' EXIT

  local app="$mount/VLC.app/Contents/MacOS"
  mkdir -p "$stage/lib"
  cp "$app/lib/libvlc.dylib" "$app/lib/libvlccore.dylib" "$stage/lib/"
  cp -R "$app/plugins" "$stage/plugins"
  cp "$mount/VLC.app/Contents/Resources/COPYING.txt" "$stage/" 2>/dev/null || true
  hdiutil detach "$mount" >/dev/null
  trap - EXIT

  stamp_tree
  # VLC's macOS build ships a cache generator inside the bundle.
  if [ -x "$app/../lib/vlc/vlc-cache-gen" ]; then
    "$app/../lib/vlc/vlc-cache-gen" "$stage/plugins"
    touch -d "$STAMP_DATE" "$stage/plugins/plugins.dat"
  fi
}

# Every file, one timestamp. Directories too: a tar records theirs and an
# extractor restores them.
stamp_tree() {
  echo "── freezing timestamps"
  find "$stage" -exec touch -h -d "$STAMP_DATE" {} +
}

write_notice() {
  # libmpv carries its own, because it is a different project under a different
  # licence and a notice naming VideoLAN beside an mpv binary is worse than no
  # notice at all.
  if [ "$payload" = "libmpv" ]; then
    cat > "$stage/NOTICE-NoMercy.txt" <<EOF
This directory contains an unmodified redistribution of libmpv, version
$version, built by the mpv-winbuild-cmake project and published at
$source_url

mpv is licensed under the GNU General Public License, version 2 or later; parts
of it are LGPL. This build is GPL. Sources for mpv are at
https://github.com/mpv-player/mpv and the build recipe that produced this
binary is at https://github.com/shinchiro/mpv-winbuild-cmake.

Nothing here is modified. libmpv is loaded as a shared library and is not
linked into any NoMercy binary, so it can be replaced: put a directory
containing your own build on the payload override described in the NoMercy
player documentation and the player will load yours instead of this one.
EOF
    touch -d "$STAMP_DATE" "$stage/NOTICE-NoMercy.txt"
    return
  fi

  cat > "$stage/NOTICE-NoMercy.txt" <<EOF
This directory contains an unmodified redistribution of libVLC and its plugins,
built from the official VideoLAN release for $platform, version $version.

libVLC and libVLCcore are licensed under the GNU Lesser General Public License,
version 2.1 or later. The plugin set carries a mixture of licences; COPYING.txt
in this directory is VideoLAN's own licence text for this distribution, and
AUTHORS.txt and THANKS.txt are its attribution files, both shipped verbatim.

Nothing here is modified. The binaries are exactly the bytes VideoLAN published;
the only files this project adds are this notice and plugins/plugins.dat, which
is a generated index of the plugins beside it and contains no code.

Sources for these binaries are available from VideoLAN at
https://www.videolan.org/vlc/download-sources.html and, for the exact release,
https://download.videolan.org/pub/videolan/vlc/$version/.

Because libVLC is loaded as a shared library and is not linked into any NoMercy
binary, it can be replaced: put a directory containing your own build of libVLC
on the payload override described in the NoMercy player documentation, and the
player will load yours instead of this one.
EOF
  touch -d "$STAMP_DATE" "$stage/NOTICE-NoMercy.txt"
}

out="$outdir/$payload-$version-$platform.tar.gz"
packed=no

case "$kind" in
  zip) build_windows ;;
  7z) build_windows_mpv ;;
  container) build_linux ;;
  dmg) build_macos ;;
  *) echo "unknown payload kind: $kind"; exit 1 ;;
esac

if [ "$packed" = "no" ]; then
  write_notice
  echo "── packing $(basename "$out")"
  # The previous archive goes first. Building twice into one outdir produced a
  # DIFFERENT digest the second time — measured, c159cf42 then 7421e397 — which
  # makes a script whose whole promise is byte-reproducibility unreproducible on
  # any machine that builds more than once. A release check comparing digests
  # would fail on a payload that is actually fine, and a bundling build that
  # trusts the digest fails outright.
  rm -f "$out"
  # Sorted, owner-stripped and with gzip's own timestamp suppressed, so the
  # same upstream produces the same bytes and therefore the same digest.
  tar --sort=name --owner=0 --group=0 --numeric-owner \
      --mtime="$STAMP_DATE" -C "$stage" -cf - . | gzip -n -9 > "$out"
fi

echo
echo "payload:  $out"
echo "size:     $(du -h "$out" | cut -f1)"
echo "sha256:   $(sha256sum "$out" | cut -d' ' -f1)"
echo
echo "Paste that digest into NativeArchives.kt and publish the archive as the"
echo "release asset it names."
