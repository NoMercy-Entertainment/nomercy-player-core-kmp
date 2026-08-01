#!/usr/bin/env bash
# Assemble the Linux payload from a libVLC built WITHOUT GPL contribs, and write
# it to stdout as a gzipped tar. Runs inside the image vlc-lgpl.dockerfile built.
#
# The sibling of native-payload-linux.sh, which takes Debian's packages. That one
# ships whatever licence Debian's build carried, and Debian builds FFmpeg with
# --enable-gpl like VideoLAN does; this one packages the tree compiled here with
# contrib/bootstrap --disable-gpl, so the decoder is LGPL.
#
# Everything after the source is the same discipline as the Debian path and for
# the same reasons: closed over so it loads on a machine that is not this one,
# $ORIGIN rpaths so nobody is asked to set LD_LIBRARY_PATH, one frozen timestamp
# so two builds of the same pin produce the same digest, and the archive
# streamed out rather than staged, because a payload full of symlinks cannot
# survive a bind mount onto a Windows filesystem.
set -euo pipefail

stamp_epoch="${1:?usage: native-payload-linux-lgpl.sh <stamp-epoch> <version>}"
version="${2:?version required}"
built=/opt/vlc-lgpl
out=/payload

log() { echo "$@" >&2; }

[ -d "$built/lib/vlc/plugins" ] || { log "no build at $built"; exit 1; }

mkdir -p "$out/plugins"
cp -a "$built"/lib/libvlc.so.5* "$built"/lib/libvlccore.so.9* "$out/"
cp -a "$built"/lib/vlc/plugins/. "$out/plugins/"
[ -x "$built/lib/vlc/vlc-cache-gen" ] && cp "$built/lib/vlc/vlc-cache-gen" "$out/"

# The refusal, repeated at packaging time. The image already fails the build if a
# GPL-only plugin was produced; this says the same thing about the bytes actually
# being packaged, because those are two different claims and only the second one
# is about what ships.
for gpl in video_filter/libpostproc_plugin.so codec/libx264_plugin.so codec/libx265_plugin.so; do
  if [ -e "$out/plugins/$gpl" ]; then
    log "refusing to package $gpl: this tree was built GPL"
    exit 1
  fi
done

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

log "── setting rpaths"
find "$out" -maxdepth 1 -name '*.so*' -type f -exec patchelf --set-rpath '$ORIGIN' {} \;
find "$out/plugins" -name '*.so' -type f -exec patchelf --set-rpath '$ORIGIN/../..' {} \;

mkdir -p "$out/licences"
cp /src/vlc-"$version"/COPYING.LIB "$out/licences/libvlc-LGPL-2.1.txt" 2>/dev/null || true
cp /src/vlc-"$version"/COPYING "$out/licences/vlc-COPYING.txt" 2>/dev/null || true

cat > "$out/NOTICE-NoMercy.txt" <<EOF
This directory contains libVLC $version and its plugins, built from VideoLAN's
published source with the contrib tree bootstrapped --disable-gpl.

It is not the redistributable VideoLAN publishes. That one is configured GPL:
contrib/src/ffmpeg/rules.mak adds --enable-postproc only inside its GPL branch,
and the same branch adds --enable-gpl to FFmpeg, which makes libavcodec_plugin —
the decoder a player actually loads — GPLv2-or-later. This build does not set
that flag, so FFmpeg is LGPL and so is the plugin that links it.

What the flag would have added is libpostproc and the x264 and x265 ENCODERS. A
player calls none of them, and every decoder is unaffected. The build fails
rather than continues if any of those three plugins is produced, and this payload
is refused if one is present.

libVLC and libVLCcore are licensed under the GNU Lesser General Public License,
version 2.1 or later; licences/ carries VideoLAN's own text. The application
binary is not built at all (--disable-vlc), nor are the Qt, skins2 and Lua
interfaces, which is where most of the GPL in an official payload lives.

The only change made to the compiled binaries is an rpath of \$ORIGIN, written
with patchelf so the payload resolves its own libraries instead of the host's.
No code is altered by that, only where the loader looks. plugins/plugins.dat is
a generated index and contains no code.

Sources are at https://www.videolan.org/vlc/download-sources.html, and the exact
configuration used here is in tools/vlc-lgpl.dockerfile in this repository.

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
