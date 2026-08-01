#!/usr/bin/env bash
# Assemble a self-contained Linux libVLC payload and write it to stdout as a
# gzipped tar. Runs INSIDE the container; the archive never touches the host
# filesystem until it is already an archive.
#
# Streaming it out rather than staging into a bind mount is not a style choice.
# A Linux payload is full of symlinks — libvlc.so.5 pointing at libvlc.so.5.6.0
# and so on for every library it closed over — and a bind mount onto a Windows
# filesystem cannot hold one. Staged that way the payload silently becomes a
# directory of duplicated files, or the copy fails halfway.
#
# Linux is the platform with no official portable build: VideoLAN ships source
# and distribution packages, and a distribution package is the opposite of
# self-contained — it expects thirty shared libraries to already be on the
# machine, at the versions that distribution chose. A payload that assumed the
# same would work on the distribution it was built on and nowhere else, which is
# the "works on my machine" this whole exercise exists to remove.
#
# So the packages are installed, and then closed over: every shared object the
# VLC libraries and plugins need is copied in beside them, except the ones a
# glibc system always has. Each copied object then gets an rpath of $ORIGIN, so
# the dynamic linker resolves siblings out of the payload without anybody
# setting LD_LIBRARY_PATH — an environment variable a library has no business
# asking a consumer's application to set.
set -euo pipefail

stamp_epoch="${1:?usage: native-payload-linux.sh <stamp-epoch> <version>}"
version="${2:?version required}"
out=/payload
arch="$(uname -m)-linux-gnu"
libdir="/usr/lib/$arch"

log() { echo "$@" >&2; }

export DEBIAN_FRONTEND=noninteractive
log "── installing packages"
apt-get update -qq >/dev/null
apt-get install -y -qq --no-install-recommends \
  libvlc5 libvlccore9 libvlc-bin vlc-plugin-base vlc-plugin-video-output \
  vlc-plugin-video-splitter vlc-plugin-visualization \
  patchelf >/dev/null

mkdir -p "$out/plugins"
cp -a "$libdir"/libvlc.so.5* "$libdir"/libvlccore.so.9* "$out/"
cp -a "$libdir"/vlc/plugins/. "$out/plugins/"
[ -x "$libdir/vlc/vlc-cache-gen" ] && cp "$libdir/vlc/vlc-cache-gen" "$out/"

# Present on every glibc system, and copying them is actively harmful: a libc
# from the build image loaded beside the host's own would be two libcs in one
# process. libstdc++ and libgcc are borderline, and left out for the same
# reason.
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

# Written with a literal dollar sign: $ORIGIN is resolved by the loader at load
# time, not by this shell. A plugin sits two directories down, so its way back
# to the libraries is ../.. rather than the same directory.
log "── setting rpaths"
find "$out" -maxdepth 1 -name '*.so*' -type f -exec patchelf --set-rpath '$ORIGIN' {} \;
find "$out/plugins" -name '*.so' -type f -exec patchelf --set-rpath '$ORIGIN/../..' {} \;

mkdir -p "$out/licences"
for pkg in libvlc5 libvlccore9 vlc-plugin-base; do
  [ -f "/usr/share/doc/$pkg/copyright" ] && cp "/usr/share/doc/$pkg/copyright" "$out/licences/$pkg.copyright"
done

cat > "$out/NOTICE-NoMercy.txt" <<EOF
This directory contains an unmodified redistribution of libVLC $version, its
plugins, and the shared libraries they depend on, taken from the Debian
packages libvlc5, libvlccore9 and vlc-plugin-base.

libVLC and libVLCcore are licensed under the GNU Lesser General Public License,
version 2.1 or later. The plugin set and the libraries closed over beside it
carry a mixture of licences; the Debian copyright files reproduced verbatim in
licences/ state each one.

The binaries are the bytes Debian published. The only change made to them is an
rpath of \$ORIGIN, written with patchelf so that the payload resolves its own
libraries instead of the host's — no code is altered by that, only where the
loader looks. plugins/plugins.dat is a generated index of the plugins beside it
and contains no code.

Sources for these binaries are available from Debian at
https://sources.debian.org/src/vlc/ and from VideoLAN at
https://www.videolan.org/vlc/download-sources.html.

Because libVLC is loaded as a shared library and is not linked into any NoMercy
binary, it can be replaced: point the payload override at a directory holding
your own build and the player will load yours instead of this one.
EOF

log "── freezing timestamps"
find "$out" -exec touch -h -d "@$stamp_epoch" {} +

if [ -x "$out/vlc-cache-gen" ]; then
  log "── generating plugins.dat"
  ( cd "$out" && ./vlc-cache-gen ./plugins ) >&2
  touch -d "@$stamp_epoch" "$out/plugins/plugins.dat"
else
  log "!! no vlc-cache-gen: this payload will index its plugins on every launch"
fi

log "── payload: $(find "$out" -type f | wc -l) files, $(du -sh "$out" | cut -f1)"

tar --sort=name --owner=0 --group=0 --numeric-owner \
    --mtime="@$stamp_epoch" -C "$out" -cf - . | gzip -n -9
