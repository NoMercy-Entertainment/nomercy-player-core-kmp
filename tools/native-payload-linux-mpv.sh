#!/usr/bin/env bash
# Assemble a self-contained Linux libmpv payload and write it to stdout as a
# gzipped tar. Runs INSIDE the container; the archive never touches the host
# filesystem until it is already an archive.
#
# Streamed out rather than staged into a bind mount for the same reason the
# libVLC one is: the payload is full of symlinks — libmpv.so.2 pointing at
# libmpv.so.2.5.0 and so on for everything it closed over — and a bind mount
# onto a Windows filesystem cannot hold one.
#
# libmpv has no plugin tree, which is the whole difference from libVLC: one
# shared library plus the objects it needs, no cache to generate, no directory
# of three hundred and sixty-four modules to index. The closure and the $ORIGIN
# rpath are identical work, because a distribution package expects thirty
# libraries to already be on the machine at the versions that distribution chose
# — which is the "works on my machine" a payload exists to remove.
set -euo pipefail

stamp_epoch="${1:?usage: native-payload-linux-mpv.sh <stamp-epoch> <version>}"
version="${2:?version required}"
out=/payload
arch="$(uname -m)-linux-gnu"
libdir="/usr/lib/$arch"

log() { echo "$@" >&2; }

export DEBIAN_FRONTEND=noninteractive
log "── installing packages"
apt-get update -qq >/dev/null
apt-get install -y -qq --no-install-recommends libmpv2 patchelf >/dev/null 2>&1 ||
  apt-get install -y -qq --no-install-recommends libmpv1 patchelf >/dev/null

mkdir -p "$out"
cp -a "$libdir"/libmpv.so.* "$out/"

# Present on every glibc system, and copying them is actively harmful: a libc
# from the build image loaded beside the host's own would be two libcs in one
# process.
base_provided='^(ld-linux.*|libc|libm|libdl|libpthread|librt|libresolv|libutil|libgcc_s|libstdc\+\+|libanl)\.so'

log "── closing over dependencies"
while :; do
  before="$(find "$out" -name '*.so*' | wc -l)"
  while read -r needed path; do
    [[ "$needed" =~ $base_provided ]] && continue
    [ -f "$path" ] || continue
    [ -e "$out/$needed" ] && continue
    cp -aL "$path" "$out/$needed"
  done < <(find "$out" -name '*.so*' -type f -exec ldd {} + 2>/dev/null \
            | awk '/=> \//{print $1, $3}' | sort -u)
  after="$(find "$out" -name '*.so*' | wc -l)"
  [ "$before" = "$after" ] && break
done

# A literal dollar sign: $ORIGIN is resolved by the loader at load time, not by
# this shell. Everything sits in one directory here, so every object's way back
# to its siblings is itself.
log "── setting rpaths"
find "$out" -name '*.so*' -type f -exec patchelf --set-rpath '$ORIGIN' {} \;

mkdir -p "$out/licences"
for pkg in libmpv2 libmpv1; do
  [ -f "/usr/share/doc/$pkg/copyright" ] && cp "/usr/share/doc/$pkg/copyright" "$out/licences/$pkg.copyright"
done

cat > "$out/NOTICE-NoMercy.txt" <<EOF
This directory contains an unmodified redistribution of libmpv $version and the
shared libraries it depends on, taken from the Debian package libmpv.

mpv is licensed under the GNU General Public License, version 2 or later; parts
of it are LGPL. The libraries closed over beside it carry a mixture of licences;
the Debian copyright files reproduced verbatim in licences/ state each one.

The binaries are the bytes Debian published. The only change made to them is an
rpath of \$ORIGIN, written with patchelf so the payload resolves its own
libraries instead of the host's — no code is altered by that, only where the
loader looks.

Sources are available from Debian at https://sources.debian.org/src/mpv/ and
from the mpv project at https://github.com/mpv-player/mpv.

Because libmpv is loaded as a shared library and is not linked into any NoMercy
binary, it can be replaced: point the payload override at a directory holding
your own build and the player will load yours instead of this one.
EOF

log "── freezing timestamps"
find "$out" -exec touch -h -d "@$stamp_epoch" {} +

log "── payload: $(find "$out" -type f | wc -l) files, $(du -sh "$out" | cut -f1)"

tar --sort=name --owner=0 --group=0 --numeric-owner \
    --mtime="@$stamp_epoch" -C "$out" -cf - . | gzip -n -9
