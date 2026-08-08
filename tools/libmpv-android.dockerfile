# libmpv for Android, built from source with every decoder a player needs.
#
# There is no published libmpv AAR to depend on — the coordinates other projects
# use are not on Maven Central — so it is built here, the same way the Linux
# payload is: in a container, from pinned sources, with the flags written down
# rather than remembered.
#
# NO PLAYBACK COMPROMISES. Hardware decoding stays on (mpv picks mediacodec
# through hwdec at run time) and the software decoders stay in beside it, which
# is the whole point: a High 10 stream that no MediaCodec on the device will
# accept is decoded in software, exactly as the browser does it, instead of
# ending playback. Encoders are the only thing left out — a player never runs
# one, and x264/x265/SVT-AV1 are encoders. Every DECODER is enabled: H.264
# through High 10 and High 4:4:4, HEVC through Main 10, AV1 through libdav1d,
# VP9, VC-1, MPEG-2, ProRes, and the audio set including TrueHD, DTS and FLAC.
#
# mpv-android's buildscripts drive the dependency chain (ffmpeg, libass,
# freetype, fribidi, harfbuzz, libplacebo, shaderc) because that ordering is
# theirs and reproducing it by hand is how a build goes subtly wrong for a week.
FROM debian:bookworm-slim AS build

ENV DEBIAN_FRONTEND=noninteractive

# The toolchain the buildscripts expect. openjdk and the SDK come with them
# because the scripts fetch and place both themselves.
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends \
        git curl unzip zip build-essential pkg-config ninja-build cmake \
        python3 python3-pip python3-setuptools autoconf automake libtool \
        python3-venv \
        nasm yasm wget xz-utils ca-certificates openjdk-17-jdk-headless \
        sudo \
    && rm -rf /var/lib/apt/lists/*

# sudo is INSTALLED rather than patched out of upstream's scripts.
#
# download-sdk.sh calls it to place the SDK, and a container runs as root
# with no sudo on it. Theirs is the recipe upstream tests; editing their
# scripts here is how a build quietly drifts from the one that works.

# meson from pip rather than apt.
#
# Debian bookworm ships 1.0.1 and fontconfig in mpv's dependency set requires
# >= 1.11.0, so the build died on the first meson project it reached with a
# message about a version rather than about anything mpv.
RUN pip3 install --break-system-packages --no-cache-dir 'meson>=1.11'

WORKDIR /build

ARG BUILDSCRIPTS_REF=master
RUN git clone --depth 1 --branch "${BUILDSCRIPTS_REF}" \
        https://github.com/mpv-android/mpv-android.git mpv-android

WORKDIR /build/mpv-android/buildscripts

# Only the native side. The scripts can build the whole APK and we want the
# shared libraries and nothing else — an APK would carry a UI nobody here runs.
#
# arm64 first because it is every current phone and the only ABI this has been
# proven on; the others follow the same recipe and are built by the same call.
ARG ABIS="arm64"

# download.sh FIRST, and its failure must stop the build.
#
# It fetches the NDK, the SDK and every source tarball. Without it buildall.sh
# has nothing to build, exits successfully having built nothing, and the image
# exports an empty payload with every layer reporting DONE — which is how this
# "succeeded" twice while producing no libmpv at all.
# apt-get update inside the same layer, because download.sh installs its own
# build dependencies (gperf and friends) and the apt lists were deleted above to
# keep the image small — "E: Unable to locate package gperf", exit 100.
# Assume-Yes for every apt the scripts run.
#
# DEBIAN_FRONTEND is not enough: download.sh calls `sudo apt-get install`
# without -y, apt asks "Do you want to continue? [Y/n]", and a container with
# no stdin answers Abort — exit 1, from a line whose last output was about
# ninja. An apt.conf drop-in settles it once instead of per call.
RUN echo 'APT::Get::Assume-Yes "true";' > /etc/apt/apt.conf.d/90nomercy

# apt-get update in the same layer, because the lists were deleted above to
# keep the image small and download.sh installs its own build dependencies.
RUN apt-get update -qq && ./download.sh && rm -rf /var/lib/apt/lists/*

RUN ./buildall.sh --arch "${ABIS}"

# The payload: libmpv and everything it links, per ABI.
#
# `test -f` at the end rather than `|| true`: an empty payload that only fails
# on a device is the failure mode this whole file already had once.
RUN mkdir -p /payload && \
    find /build/mpv-android/buildscripts -path "*prefix*" -name "libmpv.so" -print0 \
      | xargs -0 -I{} cp {} /payload/ && \
    find /build/mpv-android/buildscripts -path "*prefix*" -name "*.so" -print0 \
      | xargs -0 -I{} cp -n {} /payload/ 2>/dev/null; \
    test -f /payload/libmpv.so

# Stripped here, where the NDK that produced them is, rather than on the host.
#
# Unstripped the set is 108 MB against 30 MB stripped, and the debug information
# is only readable by the toolchain that emitted it — so the machine holding
# that toolchain is the machine that should be doing this.
RUN find /build/mpv-android/buildscripts -path "*toolchain*" -name "llvm-strip" | head -1 \
      > /tmp/strip-path && \
    xargs -a /tmp/strip-path -I{} sh -c '{} --strip-unneeded /payload/*.so'

FROM scratch AS payload
COPY --from=build /payload /
