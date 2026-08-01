# The machine the proof runs on: a JVM, a display, a sound stack, and no VLC.
#
# The display and the sound stack are here because a desktop has them, not
# because the payload needs them. Without a display libVLC creates no video
# output and stops the decoder, and the run fails for a reason that has nothing
# to do with whether libVLC was found — which would make the proof measure the
# container instead of the library.
#
# What is deliberately absent is everything VLC: no vlc, no libvlc, no
# libvlccore, no plugin directory, no libass. verify-in-container.sh asserts
# that before it runs anything, so this file cannot quietly grow one.
FROM eclipse-temurin:21-jdk

# libasound2 was renamed libasound2t64 in the 64-bit-time_t transition, and the
# base image has moved across it before. Both names are tried so this file does
# not break on an image bump for a reason unrelated to anything it is proving.
#
# libXtst, libXrender, libXext and libXi used to be here and are gone. They were
# AWT's, not libVLC's: the previous binding built an overlay in the media
# player's constructor, which touched java.awt.Rectangle, which loaded the whole
# toolkit — so the desktop engine could not be CONSTRUCTED on a Linux machine
# without X client libraries, whether or not anything was ever drawn. The direct
# binding touches no AWT at all, and tools/verify-in-container.sh now proves that
# on an image with nothing installed on top of the JDK.
RUN apt-get update -qq \
 && apt-get install -y -qq --no-install-recommends \
      xvfb libx11-6 libfreetype6 libfontconfig1 \
 && (apt-get install -y -qq --no-install-recommends libasound2t64 \
     || apt-get install -y -qq --no-install-recommends libasound2) \
 && rm -rf /var/lib/apt/lists/*

# A null sink, so the audio output opens and the decoder keeps going. A machine
# with no sound card is a normal machine; one where the audio output errors on
# every frame is not what a desktop user has.
RUN printf 'pcm.!default { type null }\nctl.!default { type null }\n' > /etc/asound.conf
