# Third-Party Notices

The JVM target of this library loads native binaries it did not build. This file
says which, under what licence, and what obligations they carry.

Nothing here applies to the Android or Apple targets. Android decodes through
Media3, which is a Maven dependency and carries its own notices; the Apple
targets use AVFoundation, which is Apple's.

## JNA

**Version:** 5.18.1
**Source:** https://github.com/java-native-access/jna
**Licence:** Apache License 2.0, taken under JNA's own dual licence — the
project offers LGPL 2.1-or-later and Apache-2.0 at the recipient's option, and
this library takes Apache-2.0.

JNA is how the JVM target calls libVLC. It is a `jvmMain` dependency of the
published `nomercy-player-core-kmp-jvm` artifact and carries no obligation
beyond attribution.

There is no Java wrapper around libVLC in this library. The binding is
`tv.nomercy.player.core.natives.libvlc`, written against libVLC's own published
C headers, and the functions it declares are the ones the two desktop backends
call and nothing else.

## libVLC

**Version:** 3.0.23
**Source:** https://www.videolan.org/vlc/
**Licence:** libVLC and libVLCcore are GNU Lesser General Public License v2.1 or
later. They are loaded dynamically and are replaceable — see below. The plugin
set shipped beside them is mixed and is audited below.

NoMercy publishes prebuilt payloads containing libVLC, its plugins and, on
Linux, the shared libraries they depend on. They are fetched by the running
library, checked against a digest and unpacked into a per-user cache.

| Platform | Built from | Contents |
| --- | --- | --- |
| `windows-x64` | The official VideoLAN redistributable `vlc-3.0.23-win64.zip` | `libvlc.dll`, `libvlccore.dll`, the full plugin set, `vlc-cache-gen.exe` |
| `linux-x64` | The Debian packages `libvlc5`, `libvlccore9`, `libvlc-bin`, `vlc-plugin-base` and their dependency closure | `libvlc.so.5`, `libvlccore.so.9`, the plugin set, every non-glibc shared object they need |

`tools/native-payloads.lock` pins the upstream each payload is built from and
its digest. `tools/build-native-payload.sh` is the script that builds one, and
it is the whole build: nothing is compiled and no source is patched.

### What is satisfied

The LGPL asks that a user be able to replace the library with their own build.
Two things provide that:

- libVLC is loaded as a shared library through JNA, by name, at runtime. It is
  not linked into any NoMercy binary, and no NoMercy code is derived from it.
- The payload can be replaced outright. `nomercy.player.natives.libvlc.dir`
  points the loader at any directory holding a libVLC build, and that one is
  used instead, with no digest check and no download.

Upstream licence and attribution files travel inside each payload verbatim:
`COPYING.txt`, `AUTHORS.txt` and `THANKS.txt` on Windows, and Debian's
`copyright` files under `licences/` on Linux. Each payload also carries
`NOTICE-NoMercy.txt` naming its upstream, its version and the only change made
to the binaries, which on Linux is an rpath written with `patchelf` and on
Windows is nothing at all.

The only file NoMercy adds to a payload beyond those notices is
`plugins/plugins.dat`, which is a generated index of the plugins beside it and
contains no code.

### The wrapper that used to be here

Until this change the JVM target reached libVLC through `uk.co.caprica:vlcj`,
which declares GPL v3 in the `<licenses>` block of the POM published to Maven
Central, as does `uk.co.caprica:vlcj-natives`. Apache-2.0 combines *into* GPLv3
and not the other way round, so the published JVM artifact was arguably a GPLv3
combined work wearing an Apache-2.0 licence file.

vlcj is gone. Nothing in this repository depends on it and nothing in the
published artifact carries it. The engine underneath is unchanged, because the
engine was never the problem: libVLC is LGPL-2.1-or-later and an Apache-2.0
library may load an LGPL shared library without inheriting anything from it.

### The plugin set, audited

Both payloads carry VideoLAN's plugins. What follows is measured rather than
assumed, and the measurements are reproducible from the VLC 3.0.23 source
tarball and the payload archives.

**What the player actually loads.** Driven through the real backend on a real
file, libVLC 3.0.23 selected: `filesystem` (access), `mkv` (demux), `avcodec`
(video decoder), `opus` (audio decoder), `swscale` and `yuvp` (video
converters), `scaletempo` (audio filter), `samplerate` (resampler),
`float_mixer` (audio volume), `freetype` (text renderer), plus the platform's
own audio output and the video output. The desktop player replaces the video
output with `vmem`, which is what puts the picture in a buffer instead of a
window.

That is a couple of dozen modules out of 363 in the Windows payload.

**What licence those modules carry.** Every implementation file under
`modules/` in the VLC 3.0.23 source was classified by the licence in its own
header: 750 carry LGPL-2.1-or-later, 345 carry a GPL notice, and 50 carry
neither. The GPL is concentrated in parts a library consumer never loads — the
Qt, skins2 and macOS interfaces account for 259 of those files on their own,
with the control modules, the Lua bindings, the RTSP server and the streaming
output making up most of the rest. Every module in the list above has an
LGPL-2.1-or-later header.

Two caveats on that number. A handful of files are Bison-generated parsers
whose GPL notice is the skeleton's and carries Bison's own exception —
`codec/webvtt/CSSGrammar.c` is one, and the WebVTT decoder is not GPL because of
it. And a module's header says nothing about the third-party library it links,
which is where the real finding is.

**The one GPL-only plugin the player needs, named.** `libavcodec_plugin` is the
decoder in the list above, and in these payloads it is GPLv2-or-later rather
than LGPL. The evidence is in the payload itself: `libpostproc_plugin` ships
beside it, VLC's `contrib/src/ffmpeg/rules.mak` only adds `--enable-postproc`
inside `ifdef GPL`, and that same branch adds `--enable-gpl` to the FFmpeg the
decoder is built from. So the FFmpeg in VideoLAN's official redistributable is
GPL-configured, and the plugin that links it is GPL.

It is named here rather than shipped quietly, and the functionality is not
being quietly dropped either: `avcodec` is what decodes nearly everything a
desktop client is handed.

**Whether the payload can be trimmed.** Yes, and it should be, but not in this
change — the archives are pinned by digest and republishing them is its own
piece of work. What can go without costing the player anything it uses:

- `plugins/gui/` — the Qt and skins2 interfaces, GPL, and by far the largest
  single saving.
- `plugins/control/`, `plugins/lua/` — GPL, and a library has no interface to
  control.
- `plugins/stream_out/`, `plugins/mux/`, `plugins/access_output/` — the
  streaming-server half of VLC.
- `codec/libx264`, `codec/libx26410b`, `codec/libx265`, `video_filter/libpostproc`
  — GPL encoders and a GPL filter, none of which a player calls.
- `plugins/visualization/`, `plugins/video_splitter/` and most of
  `plugins/video_filter/`.

Trimming those leaves the demuxers, decoders, access modules, `vmem` and the
audio outputs — the set above plus everything needed to open a disc rip, which
is the reason libVLC was chosen. It does not resolve the `libavcodec_plugin`
finding: that needs an FFmpeg built without `--enable-gpl`, which means building
the contrib rather than repackaging VideoLAN's redistributable.

### The obligation this leaves

libVLC and libVLCcore are LGPL-2.1-or-later, loaded dynamically, and replaceable
through `nomercy.player.natives.libvlc.dir` — the LGPL's requirement is met by
construction.

The GPL-licensed plugins that travel in the payload are separate programs that
this library does not link. Redistributing them still carries GPLv2's
source-availability requirement, which the payload's own `NOTICE-NoMercy.txt`
answers by naming VideoLAN's source download for the exact release. Trimming the
set to the modules above would remove most of that obligation; the
`libavcodec_plugin` finding is what would remain, and it needs a decision.
