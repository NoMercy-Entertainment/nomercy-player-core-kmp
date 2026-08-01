# Third-Party Notices

The JVM target of this library loads native binaries it did not build. This file
says which, under what licence, and what obligations they carry.

Nothing here applies to the Android or Apple targets. Android decodes through
Media3, which is a Maven dependency and carries its own notices; the Apple
targets use AVFoundation, which is Apple's.

## libVLC

**Version:** 3.0.23
**Source:** https://www.videolan.org/vlc/
**Licence:** libVLC and libVLCcore are GNU Lesser General Public License v2.1 or
later. The plugin set shipped beside them is mixed; see the caveat below.

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

- libVLC is loaded as a shared library through JNA. It is not linked into any
  NoMercy binary, and no NoMercy code is derived from it.
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

### Two questions that need a decision, not an assumption

**1. This library is published as Apache-2.0 and its JVM variant links vlcj,
which declares GPL v3.**

`uk.co.caprica:vlcj:4.11.0` and `uk.co.caprica:vlcj-natives:4.8.3` both declare
`GPL v3` in the `<licenses>` block of the POMs published to Maven Central. That
is the Java binding, not libVLC itself, and it is a `jvmMain` dependency of the
published `nomercy-player-core-kmp-jvm` artifact.

Apache-2.0 is compatible with GPLv3 in one direction only: Apache-2.0 code may
be combined into a GPLv3 work, and the combination is then distributed under
GPLv3. It does not run the other way. So the JVM artifact as published today is
arguably a GPLv3 combined work wearing an Apache-2.0 licence file.

This predates the native payloads and is not caused by them. It has three
possible answers and they are business decisions:

- relicense the JVM variant, or the library, to GPLv3;
- replace vlcj with a binding that is not GPL, which means writing the JNA
  binding to libVLC directly, against libVLC's own LGPL headers;
- confirm with counsel that the current arrangement is acceptable.

**2. The Windows payload carries the full official plugin set, which VideoLAN
distribute as GPLv2-or-later, not LGPL.**

libVLC itself is LGPL, but the binary VLC distribution as a whole is GPL because
some of its plugins are. Shipping the whole set is the reason the desktop engine
can open a disc rip that nothing else will touch, and trimming it to an
LGPL-only subset would cost exactly that.

If question 1 is answered with GPLv3, note that GPLv2-only components cannot be
combined with GPLv3 ones, so the two questions have to be answered together
rather than in sequence.

Neither question is answered here, and no position is implied by the code. Both
need a call before this ships.
