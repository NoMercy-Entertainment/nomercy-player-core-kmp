# libVLC and its plugins, built from source with no GPL contribs.
#
# The redistributable VideoLAN publishes is built GPL: contrib/src/ffmpeg/rules.mak
# only adds --enable-postproc inside `ifdef GPL`, and that same branch adds
# --enable-gpl to FFmpeg. So the decoder the desktop player actually loads —
# libavcodec_plugin — is GPLv2-or-later in every official payload, and a library
# redistributing it carries the copyleft obligation into whatever links it.
#
# What that flag buys is libpostproc and the x264/x265 ENCODERS. A player calls
# none of them. Every decoder — H.264, HEVC, AV1, VP9, AAC, FLAC, Opus, DTS,
# TrueHD — is LGPL and is still here. So this is not a reduced build; it is the
# same playback with the encoders left out.
#
# `contrib/bootstrap --disable-gpl` is the switch. It leaves GPL undefined for
# every rules.mak in the contrib tree, so the GPL-only dependencies are neither
# fetched nor built and FFmpeg is configured without --enable-gpl.
#
# Built in layers on purpose: the contribs take the bulk of the time and change
# only when the pin does, so iterating on what gets packaged does not rebuild
# them.
FROM debian:bookworm-slim AS build

ARG VLC_VERSION=3.0.23
ARG VLC_SHA256=e891cae6aa3ccda69bf94173d5105cbc55c7a7d9b1d21b9b21666e69eff3e7e0

ENV DEBIAN_FRONTEND=noninteractive

# The contrib tree builds ~80 dependencies from source and each wants its own
# generator. Installed in one layer so a change below does not refetch them.
#
# libtool-bin and libltdl-dev are not optional extras: the `libtool` package
# alone installs the script and not the m4 macros, and mpg123's configure.ac
# calls LT_SYS_MODULE_EXT, which aclocal then cannot expand — reported as
# "possibly undefined macro" several hundred lines away from anything about
# libtool.
RUN apt-get update && apt-get install -y --no-install-recommends \
      autoconf autoconf-archive automake autopoint bison build-essential \
      ca-certificates cmake curl file flex gettext git gperf help2man \
      libltdl-dev libtool libtool-bin m4 meson nasm ninja-build patch \
      pkg-config protobuf-compiler python3 python3-setuptools ragel tar \
      texinfo unzip wget xz-utils yasm zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src

# Pinned by the digest VideoLAN publishes beside the tarball, checked before a
# single byte of it is executed.
RUN curl -sSLf --max-time 900 -o vlc.tar.xz \
      "https://get.videolan.org/vlc/${VLC_VERSION}/vlc-${VLC_VERSION}.tar.xz" \
    && echo "${VLC_SHA256}  vlc.tar.xz" | sha256sum -c - \
    && tar xf vlc.tar.xz \
    && rm vlc.tar.xz

WORKDIR /src/vlc-${VLC_VERSION}

# The whole point of this image, in one flag.
#
# --disable-gpl also drops a handful of contribs a player does not decode with
# (the GPL samplerate and postproc paths); VLC falls back to its own LGPL
# implementations for those, which is what the LGPL build is for.
#
# --enable-ad-clauses is not a loophole, it is the other half of the same switch.
# FreeType is dual-licensed FTL or GPLv2 and VLC's contrib gates the FTL arm
# behind this flag, because the FTL asks to be credited in documentation. So
# --disable-gpl alone refuses to build FreeType and takes the text renderer —
# subtitles — with it. bootstrap itself refuses --enable-ad-clauses together with
# GPLv2, which is the proof these belong together: the pair resolves to
# "Lesser GPL version 2.1, with advertisement clauses". The credit is carried in
# the payload's licences/ directory.
RUN mkdir -p contrib/native && cd contrib/native \
    && ../bootstrap --disable-gpl --enable-ad-clauses --disable-xcb --disable-sout \
    && make fetch -j"$(nproc)" \
    && make -j"$(nproc)"

# The library and its plugins, not the application. --disable-vlc skips the
# `vlc` binary itself, which is the GPL part of this source tree: libvlc and
# libvlccore are LGPL-2.1-or-later and are what a consumer links.
#
# The interfaces are dropped here rather than trimmed out of the payload later,
# because a plugin that is never built cannot be shipped by accident. Qt, skins2
# and Lua together are 259 of the GPL files in the official payload.
#
# No X11 either, and not as a size decision: the desktop player renders through
# libVLC's vmem callbacks into a Compose surface, and the headless verification
# already proves the payload loads on a machine carrying no libX11 at all. An X
# output would be a dependency nothing calls and one more thing to close over.
RUN ./bootstrap \
    && ./configure \
        --prefix=/opt/vlc-lgpl \
        --disable-vlc \
        --disable-qt --disable-skins2 --disable-lua --disable-ncurses \
        --disable-nls \
        --disable-x264 --disable-x265 --disable-postproc \
        --disable-sout --disable-vlm --disable-srt --disable-sdl-image \
        --disable-dbus --disable-udev \
        --disable-xcb --disable-xvideo --disable-wayland --without-x \
        --disable-alsa --disable-jack --disable-pulse \
        --enable-avcodec --enable-avformat --enable-swscale \
    && make -j"$(nproc)" \
    && make install

# Proof rather than intent, run inside the build so a GPL-configured FFmpeg can
# never reach the packaging stage. libavcodec reports its own licence, and the
# postproc plugin is the tell that --enable-gpl was set.
RUN set -eu; \
    if [ -e /opt/vlc-lgpl/lib/vlc/plugins/video_filter/libpostproc_plugin.so ]; then \
      echo "libpostproc_plugin was built: this is a GPL configuration" >&2; exit 1; \
    fi; \
    if [ -e /opt/vlc-lgpl/lib/vlc/plugins/codec/libx264_plugin.so ] \
       || [ -e /opt/vlc-lgpl/lib/vlc/plugins/codec/libx265_plugin.so ]; then \
      echo "a GPL encoder plugin was built" >&2; exit 1; \
    fi; \
    echo "no GPL-only plugin was produced"
