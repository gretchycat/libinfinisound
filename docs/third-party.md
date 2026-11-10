# Codec implementations

- MPEG audio: `javazoom:jlayer:1.0.1`, the existing JLayer dependency, now used for MP3 as well as Layer II. Its types remain private to the adapter.
- AAC synthesis: `com.tianscar.javasound:jaad:0.9.4`, Apache License 2.0. The adapter uses JAAD's `Decoder`/`Receiver` interface, without calling its optional Java Sound playback integration. The Apache license text is included at `META-INF/libinfinisound/JAAD-LICENSE.txt` in the core JAR. The dependency's [published metadata](https://central.sonatype.com/artifact/com.tianscar.javasound/jaad/0.9.4) and Maven sources identify the upstream implementation.
- AAC transport/container parsing: libinfinisound's ADTS and ISO-BMFF sample-table/edit-list readers. Codec selection and conversion are independent of the output device.
- FLAC: libinfinisound's parser and decoder, implemented from [RFC 9639](https://www.rfc-editor.org/rfc/rfc9639.html), with header/frame CRC verification, constant/verbatim/fixed/LPC reconstruction, Rice and escape residuals, wasted bits and stereo decorrelation.
- Tracker rendering: the existing libopenmpt JNI bridge and Kotlin fallback; packaging and load names are retained.
- ALAC: the existing bundled Peter McQuillan implementation with its source notices retained.

FLAC test fixtures use FFmpeg/libFLAC as independent encoders; those tools are not runtime dependencies.

## Android PCM fallback and optional MIDI bank

- `org.videolan.android:libvlc-all:3.7.7`: LGPL-2.1-or-later, the same artifact as
  InfiniVideo. The Sound adapter dynamically calls libVLC's public media-input
  and decoded-PCM callbacks; it opens no device output. Codec objects remain
  inside the adapter. `libVlcAar` permits a compatible source-built replacement.
  Notices and upstream source references are packaged in `third-party/libvlc-pcm`.
No MIDI bank is bundled. FluidLite can load a caller-supplied SF2/SF3 file when
an application configures one; ownership and licensing remain with the caller.

All above asset paths are relative to `audio-processing/src/main/assets`.
The Android bridge builds with CMake/NDK outside Termux; the existing Termux
source build compiles the bridge for arm64. This does not claim implemented
Linux, Windows, macOS or iOS backend packaging.

The Android synthesis adapter additionally resolves the pinned AAR's optional
[FluidLite](https://github.com/divideconcept/FluidLite) and
[Game Music Emu](https://github.com/libgme/game-music-emu) C interfaces. Their
original licenses and notices ship in `third-party/fluidlite` and
`third-party/game-music-emu`. Shared-bank borrowing uses FluidLite's public
add/remove SoundFont APIs; the resident owner outlives each borrowing synth.
For unclocked pull decoding, the adapter optionally resolves FFmpeg's public C
API from the same libVLC binary. Vendored public headers match Termux package
`ffmpeg 8.1.2-5` (libavutil 60.26.102, libavcodec 62.28.102, libavformat 62.12.102).
Their original copyright/license comments are retained. ABI mismatch or missing
symbols falls back to the libVLC public callback adapter; no new codec binary is
added. Provenance and the LGPL text ship in `third-party/ffmpeg`. Codec objects
and header types remain private to the native adapter.
