#!/usr/bin/env bash
# Assemble a self-contained macOS libmpv payload. Runs ON the Mac, because
# otool and install_name_tool are macOS-only and a dylib closure cannot be
# computed from anywhere else.
#
#   tools/native-payload-macos-mpv.sh <stamp-epoch> <version> <brew-prefix> <out-dir>
#
# The same job the Linux assembler does with patchelf, in Mach-O's terms. A
# Homebrew dylib records the ABSOLUTE path of every library it needs — the
# /opt/homebrew or ~/homebrew it was built against — so a payload copied
# somewhere else loads nothing at all on a machine that has no Homebrew, which
# is every machine a consumer of this library runs. install_name_tool rewrites
# each of those to @loader_path so the payload resolves its own siblings.
#
# Written to a file rather than into an ssh argument: a shell inside a remote
# command is a shell nobody can run on its own when it breaks, and this one has
# a dependency walk in it.
set -euo pipefail

stamp_epoch="${1:?usage: native-payload-macos-mpv.sh <stamp-epoch> <version> <brew-prefix> <out-dir>}"
version="${2:?version required}"
prefix="${3:?homebrew prefix required}"
out="${4:?output directory required}"

log() { echo "$@" >&2; }

rm -rf "$out"
mkdir -p "$out"

root="$prefix/opt/mpv/lib"
[ -f "$root/libmpv.dylib" ] || { log "no libmpv.dylib under $root"; exit 1; }

# The VERSIONED file, once. libmpv.dylib is a symlink to libmpv.2.dylib, and
# dereferencing both names copies four and a half megabytes of the same library
# twice — measured: 65 MB with the duplicate, and the loader only ever opens the
# versioned name, which is what JNA asks for as "mpv.2".
versioned="$(basename "$(readlink "$root/libmpv.dylib" 2>/dev/null || echo libmpv.dylib)")"
cp -L "$root/$versioned" "$out/$versioned"

# Everything it needs, transitively, except what macOS itself provides. System
# libraries live under /usr/lib and /System and must NOT be copied: two copies
# of libSystem in one process is the Mach-O version of the two-libc problem.
log "── closing over dependencies"
while :; do
  before="$(find "$out" -name '*.dylib' | wc -l)"
  while read -r needed; do
    case "$needed" in
      /usr/lib/*|/System/*|@*) continue ;;
    esac
    name="$(basename "$needed")"
    [ -e "$out/$name" ] && continue
    [ -f "$needed" ] || continue
    cp -L "$needed" "$out/$name"
  done < <(find "$out" -name '*.dylib' -type f -exec otool -L {} + | awk '/^\t/{print $1}' | sort -u)
  after="$(find "$out" -name '*.dylib' | wc -l)"
  [ "$before" = "$after" ] && break
done

# @loader_path, so each object finds its siblings beside itself wherever the
# payload is unpacked. The id is rewritten too: a dylib whose own id still says
# /opt/homebrew is one that anything linking against it will look for there.
log "── rewriting install names"
for object in "$out"/*.dylib; do
  install_name_tool -id "@loader_path/$(basename "$object")" "$object" 2>/dev/null || true
  while read -r needed; do
    case "$needed" in
      /usr/lib/*|/System/*|@*) continue ;;
    esac
    install_name_tool -change "$needed" "@loader_path/$(basename "$needed")" "$object" 2>/dev/null || true
  done < <(otool -L "$object" | awk '/^\t/{print $1}')

  # Every rewrite invalidates the ad-hoc signature macOS put on the binary, and
  # an unsigned dylib is refused by the loader on arm64 — silently enough that
  # it reads as a missing library. Re-signing ad-hoc is what makes the payload
  # loadable at all on Apple silicon.
  codesign --force --sign - "$object" 2>/dev/null || true
done

mkdir -p "$out/licences"
[ -f "$prefix/opt/mpv/LICENSE.GPL" ] && cp "$prefix/opt/mpv/LICENSE.GPL" "$out/licences/mpv.LICENSE.GPL"

cat > "$out/NOTICE-NoMercy.txt" <<EOF
This directory contains an unmodified redistribution of libmpv $version and the
dynamic libraries it depends on, built by Homebrew from the mpv formula.

mpv is licensed under the GNU General Public License, version 2 or later; parts
of it are LGPL. The libraries beside it carry a mixture of licences.

The binaries are the bytes Homebrew produced. The only change made to them is
that their install names were rewritten to @loader_path with install_name_tool,
so the payload resolves its own libraries instead of a Homebrew that is not on
the machine, and each was then re-signed ad-hoc because rewriting invalidates
the signature. No code is altered by either step.

Sources are at https://github.com/mpv-player/mpv.

Because libmpv is loaded as a shared library and is not linked into any NoMercy
binary, it can be replaced: point the payload override at a directory holding
your own build and the player will load yours instead of this one.
EOF

log "── freezing timestamps"
find "$out" -exec touch -h -t "$(date -r "$stamp_epoch" +%Y%m%d%H%M.%S)" {} +

log "── payload: $(find "$out" -type f | wc -l) files, $(du -sh "$out" | cut -f1)"
