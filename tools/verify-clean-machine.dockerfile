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
# libXtst and friends are AWT's, not libVLC's. vlcj builds an OverlayApi in the
# EmbeddedMediaPlayer constructor, which touches java.awt.Rectangle, which loads
# the whole AWT toolkit — so the desktop engine cannot be CONSTRUCTED on a Linux
# machine without X client libraries, regardless of whether anything is ever
# drawn. Every Linux desktop has them; a bare JDK image does not.
RUN apt-get update -qq \
 && apt-get install -y -qq --no-install-recommends \
      xvfb libxtst6 libxrender1 libxext6 libxi6 libx11-6 libfreetype6 libfontconfig1 \
 && (apt-get install -y -qq --no-install-recommends libasound2t64 \
     || apt-get install -y -qq --no-install-recommends libasound2) \
 && rm -rf /var/lib/apt/lists/*

# A null sink, so the audio output opens and the decoder keeps going. A machine
# with no sound card is a normal machine; one where the audio output errors on
# every frame is not what a desktop user has.
RUN printf 'pcm.!default { type null }\nctl.!default { type null }\n' > /etc/asound.conf
