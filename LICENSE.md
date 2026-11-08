# License status

The upstream InfiniFolder baseline (9233495) declares no original-code license.
The owner has been asked to choose one. No redistribution license is inferred
from public GitHub visibility. Original-code licensing is pending; third-party
notices and licenses remain applicable and must be preserved.

## Bundled codec and soundfont notices

The Android module now depends on libVLC-Android 3.7.7 (LGPL-2.1-or-later),
including its FluidLite MIDI synthesizer, through a dynamic PCM-only adapter.
Its license and upstream source references are packaged under
`audio-processing/src/main/assets/third-party/libvlc-pcm/`.

FluidR3Mono_GM.sf3, version 2.312, is included under the MIT license. It is the
mono conversion of FluidR3 by Michael Cowgill, based on Frank Wen's MIT-licensed
bank, and includes additional credited instruments. The complete attribution,
COPYING, original README and source references are in
`audio-processing/src/main/assets/third-party/fluidr3/`. These notices do not
change the license status of original InfiniSound code above.

The optional FluidLite/GME/FFmpeg C APIs supplied by the same libVLC binary have
additional original notices under `third-party/fluidlite`,
`third-party/game-music-emu` and `third-party/ffmpeg`. Vendored FFmpeg public
headers retain their original copyright/license comments.
