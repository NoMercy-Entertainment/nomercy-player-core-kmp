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

# The same instant in the one spelling both touches accept.
#
# `touch -d @<epoch>` is GNU only: BSD touch on macOS answered "out of range or
# illegal time specification" and took the whole payload down after it had
# already built. `-t CCYYMMDDhhmm.SS` is understood by both, and TZ=UTC0 in
# front of it is what keeps a runner in Amsterdam and one in UTC writing the
# same second. 1700000000 is 2023-11-14T22:13:20Z.
readonly STAMP_TOUCH=202311142213.20

# GNU tar and GNU coreutils, wherever this host keeps them.
#
# `--sort=name --owner=0 --mtime` and `sha256sum` do not exist on macOS; tar
# there is bsdtar and the digest tool is `shasum`. Reproducibility is the entire
# promise of this script, so the GNU tar is REQUIRED rather than approximated --
# Homebrew installs it as gtar, and the CI job that builds a macOS payload
# installs gnu-tar for exactly this reason.
# Homebrew's own directories are searched by name, because a launchd-started CI
# runner does not inherit a login shell's PATH -- the same reason the workflow
# calls brew by absolute path.
tar_bin() {
  local candidate
  for candidate in gtar /opt/homebrew/bin/gtar /usr/local/bin/gtar; do
    if command -v "$candidate" >/dev/null 2>&1; then echo "$candidate"; return; fi
  done
  echo tar
}
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

pin() {
  local key="$1"
  sed -n "s/^${key//./\\.}=//p" "$lock" | head -1
}

# A platform may pin its own, because one payload can come from two upstreams:
# libmpv is upstream's dev package on Windows and Debian's package on Linux.
version="$(pin "$payload.$platform.version")"
[ -n "$version" ] || version="$(pin "$payload.version")"
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
  actual="$(sha256_of "$into")"
  if [ -n "$expected" ] && [ "$actual" != "$expected" ]; then
    echo "digest mismatch for $url"
    echo "  expected $expected"
    echo "  actual   $actual"
    exit 1
  fi
  echo "── upstream digest $actual"
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
# macOS: VLC ships as a .dmg carrying VLC.app. hdiutil is macOS-only, so this
# path only runs there.
# ---------------------------------------------------------------------------
# Linux: assembled in a container from the distribution's own package, because
# nobody publishes a portable libmpv build either.
#
# The container writes the finished archive to stdout, so the whole payload —
# symlinks included — arrives as one file and never has to survive a bind mount
# onto a filesystem that cannot represent one.
# ---------------------------------------------------------------------------
build_linux_mpv() {
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
  MSYS_NO_PATHCONV=1 docker run --rm -i \
    -v "$here/tools/native-payload-linux-mpv.sh:/assemble.sh:ro" \
    "$image" bash /assemble.sh "$STAMP_EPOCH" "$version" > "$out"

  echo "$digest" > "$work/provenance.txt"
  packed=yes
}

# ---------------------------------------------------------------------------
# libmpv on macOS: Homebrew's build, closed over and re-pointed at itself.
#
# On the Mac, always. otool and install_name_tool are macOS-only, and a Mach-O
# dependency closure cannot be computed anywhere else — which is the same reason
# the libVLC macOS payload is built there and not in a container.
build_macos_mpv() {
  [ "$(uname -s)" = "Darwin" ] || { echo "the macos libmpv payload must be built on macOS"; exit 1; }

  local prefix="${HOMEBREW_PREFIX:-$HOME/homebrew}"
  bash "$here/tools/native-payload-macos-mpv.sh" "$STAMP_EPOCH" "$version" "$prefix" "$stage"
  packed=no
}

# ---------------------------------------------------------------------------
# Android: mpv-android's own buildscripts, in a container, cross-compiled for
# arm64.
#
# No NOTICE is written for this one by the generic path below, because the
# staged tree is exported from the image whole and the container writes it —
# see the dockerfile. What matters here is that the export is a `docker create`
# and a `docker cp` rather than a bind mount: the build runs on Windows as
# often as not, and a bind mount there flattens the execute bit these carry.
# ---------------------------------------------------------------------------
build_android_mpv() {
  command -v docker >/dev/null || { echo "docker is required to build the android payload"; exit 1; }

  # The ABI mpv-android's buildscripts name, which is not the one Android names.
  # Two of the three devices on this desk are armeabi-v7a only — a Galaxy A13 and
  # a Nokia streaming box — so an arm64-only payload is untestable on the fleet
  # and unusable on the television.
  local abi
  case "$platform" in
    android-arm64) abi=arm64 ;;
    android-arm) abi=armv7l ;;
    *) echo "unknown android platform $platform"; exit 1 ;;
  esac

  local image="nomercy/libmpv-android:$abi"
  echo "── building $image (this takes tens of minutes on a cold cache)"
  docker build -f "$here/$source_url" --build-arg "ABIS=$abi" -t "$image" --target payload "$here" >&2

  # A scratch image has no shell, so it cannot be `docker run`. Creating a
  # container from it never starts anything, and `docker export` then hands us
  # its whole filesystem — which for this image is exactly the payload.
  local container
  container="$(docker create "$image" /nothing)"
  docker export "$container" -o "$work/export.tar"
  docker rm "$container" >/dev/null

  tar -xf "$work/export.tar" -C "$stage" --wildcards '*.so'
  rm -f "$work/export.tar"

  [ -f "$stage/libmpv.so" ] || { echo "the image carried no libmpv.so"; exit 1; }
  stamp_tree
}

# Every file, one timestamp. Directories too: a tar records theirs and an
# extractor restores them.
stamp_tree() {
  echo "── freezing timestamps"
  find "$stage" -exec env TZ=UTC0 touch -h -t "$STAMP_TOUCH" {} +
}

write_notice() {
  # Resolved before the heredoc, not inside it. bash 3.2 -- which is still what
  # /bin/bash is on macOS, and so what the Mac runner uses -- cannot parse a
  # `case` inside a command substitution inside a heredoc, and reports it as a
  # syntax error on the heredoc's first line rather than on the case.
  local built_by
  case "$kind" in
    7z) built_by="the mpv-winbuild-cmake project and published at $source_url" ;;
    brew) built_by="Homebrew from $source_url" ;;
    android-container) built_by="mpv-android's buildscripts, in a container, from $source_url" ;;
    *) built_by="$source_url" ;;
  esac

    cat > "$stage/NOTICE-NoMercy.txt" <<EOF
This directory contains an unmodified redistribution of libmpv, version
$version, built by $built_by

mpv is licensed under the GNU General Public License, version 2 or later; parts
of it are LGPL. This build is GPL. Sources for mpv are at
https://github.com/mpv-player/mpv and the build recipe that produced this
binary is at https://github.com/shinchiro/mpv-winbuild-cmake.

Nothing here is modified. libmpv is loaded as a shared library and is not
linked into any NoMercy binary, so it can be replaced: put a directory
containing your own build on the payload override described in the NoMercy
player documentation and the player will load yours instead of this one.
EOF
  TZ=UTC0 touch -t "$STAMP_TOUCH" "$stage/NOTICE-NoMercy.txt"

}

out="$outdir/$payload-$version-$platform.tar.gz"
packed=no

case "$kind" in
  7z) build_windows_mpv ;;
  container) build_linux_mpv ;;
  brew) build_macos_mpv ;;
  android-container) build_android_mpv ;;
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
  echo "── tar: $(tar_bin)"
  "$(tar_bin)" --sort=name --owner=0 --group=0 --numeric-owner \
      --mtime="$STAMP_DATE" -C "$stage" -cf - . | gzip -n -9 > "$out"
fi

# Exiting 0 with no archive is the worst answer this script can give.
#
# It happened: the caller copied build/payloads/<name> into the resource tree,
# got "the source file doesn't exist", and the script's own output was never
# printed because the script had not failed. Whatever went wrong upstream of
# here, the sentence naming it belongs in THIS script's output, where the caller
# already knows to look.
[ -f "$out" ] || { echo "the build produced no archive at $out"; exit 3; }

echo
echo "payload:  $out"
echo "size:     $(du -h "$out" | cut -f1)"
echo "sha256:   $(sha256_of "$out")"
echo
echo "Paste that digest into NativeArchives.kt and publish the archive as the"
echo "release asset it names."
