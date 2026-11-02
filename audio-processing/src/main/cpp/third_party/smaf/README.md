# Yamaha SMAF player source

Source: https://github.com/akustikrausch/yamaha-smaf-player
Revision: 627f7ad8583ce34c5fec0269ef9778167af080d6
License: Apache-2.0; full license retained in LICENSE. Author: Andreas Wendorf.
Only the engine sources are included, built from source for Android; no ROM,
proprietary SDK or precompiled binary is included.

Local changes to ma_player.cpp: reject the duration/event ceiling instead of
returning a truncated score; reject truncated Huffman input instead of a prefix.
JNI imposes a 4 MiB encoded-input limit, 32 outer score/audio tracks, and a fixed
44100 Hz stereo output timeline with a two-second release tail. Scores whose
upstream timeline exceeds ten minutes are rejected.

Supported engine paths: HandyPhone and Mobile Standard score tracks (including
Huffman sequences), custom FM voices and embedded sampled voices. Preset timbres
use the engine's ROM-free approximations. MA-7 streaming, MFi and some bulk wave
banks/higher waveshapes are outside this engine's support. Do not advertise this
as bit-exact Yamaha hardware emulation.
