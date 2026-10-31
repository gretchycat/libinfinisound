# libinfinisound

Audio parsing, decoding, synthesis, metadata, playlists and signal analysis extracted from [InfiniFolder](https://github.com/gretchycat/infinifolder). Output devices, playback queues, UI, source staging and Cast routing are owned by the consuming application.

## Modules

| Module | Responsibility | Platform |
| --- | --- | --- |
| `audio-api` | Processing and PCM stream contracts, PCM/metadata models, playlists/CUE/CDDB, signal metrics | JVM, no Android dependency |
| `audio-core` | Portable MP3/AAC/FLAC/WAVE decoding, existing specialized decoders and synthesis, codec selection/fallback, PCM file preparation and signal analysis | JVM, no Android dependency |
| `audio-processing` | Android composition, MediaExtractor/MediaCodec decoder adapter, Android metadata and playhead analysis, optional tracker JNI packaging | Android API 26+ |

Portable MP3 uses JLayer; AAC uses our ADTS/MP4 demuxers and a JAAD synthesis adapter; FLAC and WAVE have library-owned streaming parsers/decoders. Each backend implements `PcmDecoder`. Third-party codec types and Android media objects do not appear in the PCM contracts. Existing MIDI, tracker and soundbank renderers remain available through an adapter.

## Build

JDK 17 or later and Gradle are needed. Build and test the portable JARs without an Android SDK:

```sh
gradle -PportableOnly=true :audio-core:build
```

Build the Android adapter with Android SDK 36 configured through `ANDROID_HOME` or an untracked `local.properties` containing `sdk.dir=/path/to/android-sdk`:

```sh
gradle :audio-core:test :audio-processing:assembleDebug
```

Use `--offline` when dependencies are cached. The portable artifacts are in `audio-api/build/libs/` and `audio-core/build/libs/`; the Android AAR is in `audio-processing/build/outputs/aar/`.

In Termux, optional tracker JNI packaging uses installed clang, patchelf, libopenmpt and its dependencies from the Termux prefix. Configure native AAPT2 in Gradle as needed. Outside this configuration the existing Kotlin tracker fallback is retained; no new NDK build path is introduced here.

## Import into another build

Pin the repository as a Git submodule and add this to the host's `settings.gradle.kts`:

```kotlin
includeBuild("libraries/libinfinisound")
```

Choose one dependency:

```kotlin
// Contracts and playlist parsing only:
implementation("io.github.gretchycat.libinfinisound:audio-api:0.2.0")
// Portable processing for a desktop JVM:
implementation("io.github.gretchycat.libinfinisound:audio-core:0.2.0")
// Android composition, including the portable core and API:
implementation("io.github.gretchycat.libinfinisound:audio-processing:0.2.0")
```

Gradle substitutes these coordinates with the pinned source. Artifacts have not been published to Maven Central. The consuming Git submodule commit pins the implementation.

## Streaming and platform selection

```kotlin
import com.infinifolder.audio.*
import com.infinifolder.audio.stream.FileAudioInput

val processor = AudioFileProcessor() // portable backend registry
processor.openPcm(FileAudioInput(sourceFile), DecoderPreference.PORTABLE_ONLY).use { pcm ->
    val samples = IntArray(4096 * pcm.format.channels)
    while (true) {
        val count = pcm.read(samples, 0, samples.size)
        if (count < 0) break
        // Forward these interleaved, left-aligned signed PCM samples to your output adapter.
    }
}
```

Android callers use `AudioProcessing.openPcm` or `AudioProcessing.prepareForOutput`. The Android composition registers the platform decoder inside the library. Preferences are `PLATFORM_FIRST`, `PORTABLE_FIRST` and `PORTABLE_ONLY`; existing specialized renderers retain their previous priority. A failed prepared-file decode is discarded before another backend starts from the source's beginning. Cancellation closes decoder handles and discards partial PCM. A successful silent stream is valid audio.

The PCM stream carries its sample rate, channel count, precision and optional exact frame count. Samples are left-aligned in 32-bit integers. FLAC decoding preserves up to 32-bit source samples. The old `AudioProcessor.decode`/`AudioPcmData` buffered API remains available as a 16-bit compatibility API; it is not the high-precision streaming API.

MP3 Xing/LAME timing and supported MP4 edit lists trim encoder delay and padding using metadata, never by detecting zero-valued samples. Complex MP4 edit lists or unsupported AAC features can fall back to the Android backend.

## InfiniFolder integration and current costs

InfiniFolder's normal and gapless preload paths use `AudioProcessing.openPlaybackSource`. They give only the resulting common PCM WAVE ByteSource to MediaPlayer's output adapter. Duration probing also goes through the library. Player code does not invoke the compressed-format extractor/codec or pass an original compressed file to MediaPlayer. The existing `prepareForOutput` API remains available.

When the decoder knows the exact frame count, `AudioFileProcessor.openWaveSource` returns an `AudioWaveSource` after validating a bounded initial PCM block. The optional `FrameSeekablePcmStream` capability permits independent 4096-frame cache blocks: a cold read positions the decoder near the requested block and never exposes unwritten cache holes. Android's known-length standard codecs and the portable MPEG/FLAC/WAVE decoders support frame positioning. MP3 positioning uses a lazy, bounded MPEG packet index and preroll, preserving encoder-delay alignment instead of relying on approximate timestamps. Other portable/composite decoders retain prefix decoding; unknown frame counts retain complete preparation. Stream metadata reports the actual access strategy, cached byte count, PCM precision/length/timing and input provenance. No duration is guessed from bitrate or silent samples.

The portable MPEG adapter excludes a validated trailing ID3v1 tag from its private byte view. JLayer otherwise rejects the final audio packet when its look-ahead sees metadata instead of a packet or EOF. This reads only three footer bytes, retains the original source and metadata, and preserves the declared encoder delay/padding and musical frame count.

The host can open borrowed views of that same PCM resource for output and visualizer analysis, sharing one decoder/cache. Closing a view does not close the owner. Closing the owner cancels reads and releases codec/file handles; the host removes its scratch file. Complete signal diagnostics are emitted only once all frame blocks have been validated, without counting repeated reads twice. On a late decoder/seek error the library reports the cause and retries its full preparation policy; output must retain the negotiated PCM format. Cancellation does not trigger retry. A retry can pause playback; a format-changing fallback is rejected rather than changing an already published WAVE header.

InfiniFolder adapts a borrowed PCM view to its existing Android MediaDataSource output edge. The new `AudioProcessing.openPlaybackSource` uses content plus valid MPEG timing to prefer the existing portable MPEG backend, measured substantially faster for playback read-ahead in the recorded environment. Android remains a fallback inside the library. Other sources retain platform-first policy, and the existing `openWaveSource`/explicit decoder-policy APIs retain their semantics. Player code never selects or calls a compressed codec directly.

The disk cache can grow to the whole decoded track, and RIFF output over 4 GiB is rejected. The host now uses demonstrated known-length positional sources directly. Local files retain their native path; CUE slicing, legacy companion-file renderers, archives whose seek adapter extracts complete entries, and unsupported sources retain explicit staging. Legacy composite renderer limits are unchanged. Other hosts can consume `PcmStream` directly. Audible playback, interruption, seeking, visualizer alignment and linked gapless transitions require handset acceptance.

The host owns source/cache leases and output files. `PcmStream.close()` releases decoder-owned handles. Other source permissions and playback lifetimes remain host responsibilities.

See [portability roadmap](docs/portability-roadmap.md), [architecture](docs/architecture.md) and [codec implementations](docs/third-party.md). iOS native binaries and desktop output integrations are future work, not provided by a JVM JAR.

## InfiniStream compatibility

`audio-api` now depends on `libinfinistream:stream-core:0.1.0`. Clone the sibling repository at the workspace's pinned revision (foundation `1b68893`) before a standalone source build, or set `-PinfiniLibrariesDir`. `StreamAudioInput` accepts a known-length positional source without hidden spooling; `PcmStreamHandle` exposes actual typed PCM output metadata. `AudioStreamProcessor` advertises accepted/produced types and borrows encoded input while owning decoded output. Existing FileAudioInput callers retain file hints and use Stream positional I/O internally. Existing APIs, codecs and Android output behavior are preserved.

PCM output metadata preserves original source provenance/warnings and adds the audio-decode step while describing actual sample format/rate/channels; uncertain liveness remains unknown. Formats requiring file-relative banks or companion resources retain the File compatibility APIs until a common related-resource resolver is supplied; the app retains those file hints and behaviors.

## Positional inputs and FLAC precision

The Android composition exposes `openPlaybackSource(SourceResource, scratch, observer, cancelled)`, `probeFormat(SourceResource, ...)` and `readTags(SourceResource, ...)`. Parsing and Android codec access remain inside Sound. The host owns the encoded resource and scratch file; decoder-opened handles are closed as their codecs finish. A `SharedPositionalResource` lets metadata readers, codecs and indexed restarts borrow one provider handle safely. `requiresLocalFileForPlayback` retains the existing renderer/file-hint policy; the host performs any required staging. File overloads remain available.

Portable FLAC supports exact output-frame seeking using coded sample/frame numbers, CRC-validated frame resynchronization, the following frame's sample-number link, a binary byte search and at most 4096 retained anchors. Fixed and variable block streams work without a seek table. Seeking decodes candidate/target frames rather than the complete PCM prefix. Subframe reconstruction is retained. Android's raw extractor output is never relabeled as wider PCM by modifying a format hint; reduced precision selects the portable fallback. Wider codec containers normalize to the FLAC source's canonical 16/24/32-bit output width, retaining all source bits and a stable fallback format.

Direct positional input removes mandatory encoded-file staging where supported. It does not promise constant network latency: a cold exact MPEG seek still scans intervening encoded packet headers; FLAC uses coded sample positions. Provider timeouts and live remote acceptance remain host/backend concerns. PCM cache memory stays bounded, disk caches can grow to a complete track, and the RIFF size limit remains.

## Decode failures never substitute audio

Unsupported WMA, chiptune, telephony and advanced-audio stubs no longer generate
a fixed chord. DSD and ADX retain their existing sample conversion; actual
registered platform codecs remain available. Unimplemented chiptune emulation
reports unsupported. SFZ requires readable, decodable referenced WAV samples and
uses their actual rate/channels; sampleless GUS patches fail. MIDI note synthesis
and previews of valid sample banks remain input-driven. No decoder fallback may
replace failed input with a test signal.

## libVLC PCM fallback and FluidR3 MIDI

The Android composition registers libVLC 3.7.7 as an additional `PcmDecoder` after
existing codecs. MP3/FLAC selection, exact positioning and linked output retain
their existing paths. libVLC accepts the same reopenable `AudioInput` interface,
uses bounded positional input reads and returns signed 16-bit decoded PCM through
`PcmStream`. Compatibility PCM callbacks feed a bounded queue; no libVLC device output,
video surface, network source URL or caller-owned encoded-file staging is used.
A source must actually yield decoded audio before it is accepted. Invalid sources
still reject. The code depends on the upstream libVLC artifact, not InfiniVideo;
Gradle deduplicates the identical upstream dependency in a combined application.

Container durations are not treated as exact PCM lengths. This backend currently
has no exact frame-seek/length API: the existing explicit PCM preparation path
finishes decoding before publishing its WAVE to an output player. Encoded bytes
are read through callbacks; this is not a claim of lazy PCM playback for libVLC
formats. Existing known-length fast paths remain lazy and positioned.

Call `AudioProcessing.initialize(context)` to enable the bundled FluidR3_GM
bank for MIDI. First MIDI use streams the three consecutive bz2 asset fragments
to one private, atomically published, SHA-256-verified SF2 file. Original SF2:
148,398,306 bytes; compressed assets: 118,540,984 bytes. Java never holds the entire
SF2 byte array. Later uses reuse the verified disk cache. The native FluidLite
adapter keeps the loaded bank resident for the process lifetime, with separate
synthesizer/voice state per stream. All calls touching the shared bank are
serialized; closing one stream does not unload the bank used by another.
The portable SMF 0/1 and RMID timeline parser supplies a finite frame total
(including a two-second release tail), so MIDI PCM is rendered lazily in blocks.
FluidLite interprets actual GM presets, note programs and channel-10 percussion. Core/portable-only synthesis remains separately available. A caller
may instead configure a caller-owned SF2 file with `setMidiSoundFont(file)`;
diagnostics identify the supplied filename, rather than claiming the bundled bank.

LibVLC, FluidR3 and bzip2 dependencies retain their licenses/notices and pinned
source references; see `docs/third-party.md`. Outside Termux the callback bridge
has an Android CMake/NDK build. The Termux build packages its arm64 bridge. No
native desktop/iOS binding or full codec-conformance guarantee is claimed.

## Audio loading and playlist timing

`AudioProcessing.readDuration` and common-source `readTags` inspect metadata or
parsed event timing; they never start unbounded full-track rendering to calculate
a duration. Untimed native files at most 64 KiB may be counted within ten seconds
of PCM and a 250 ms work budget; otherwise timing stays unknown.
MIDI timing does not load the sound bank. Portable codecs and libopenmpt provide
known timing where available; native fallback inspection opens only enough PCM
to identify the stream and read its duration, with a startup deadline. Missing
timing stays unknown. Container estimates remain distinct from exact PCM totals.

The preferred native adapter directly pulls frames through the FFmpeg C API
already exported by the pinned libVLC binary. It explicitly drains the codec at
encoded EOF and has no player/presentation clock. Public headers are bundled,
and the exact header ABI is checked before accessing codec structs. If that API
is unavailable, the original libVLC callback decoder remains a compatibility
fallback. Unknown-length formats still need a complete PCM spool for WAVE
publication; the direct adapter prepares it without playback timestamp pacing.
Existing MP3/FLAC selection and positioned caching are retained.

The optional GME C API in the pinned AAR provides actual NSF/NSFE, SPC and related
game-music emulation. Finite tracks use their file-supplied length and fade;
nonlooping VGM does not acquire an extra eight seconds. Timed loops use their
intro plus two loop repetitions and a supplied/default fade. Untimed loops retain
the explicit 150-second playback cap plus eight-second fade, which is not returned
as file metadata. Information-only GME sessions inspect timing without starting
emulation. VGZ is inflated within a 32 MiB limit before entering GME. Emulation
warnings fail through the common decoder contract instead of publishing damaged
command execution as success. Other formats still require a real decoder.

Duration fallback also inspects native container/stream timing for portable legacy
formats, including those Android cannot inspect. It does not render a full song
to populate playlist metadata.

The optional FluidLite/GME symbols are resolved at runtime and fail explicitly
when absent from a replacement AAR. Portable-only policy excludes these native
backends. Desktop/iOS packaging is still separate work.
