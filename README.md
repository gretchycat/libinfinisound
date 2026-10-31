# libinfinisound

Audio parsing, decoding, synthesis, metadata, playlists and signal analysis extracted from [InfiniFolder](https://github.com/gretchycat/infinifolder). Output devices, playback queues, UI, source staging and Cast routing are owned by the consuming application.

## Modules

| Module | Responsibility | Platform |
| --- | --- | --- |
| `audio-api` | Processing and PCM stream contracts, PCM/metadata models, playlists/CUE/CDDB, signal metrics | JVM, no Android dependency |
| `audio-core` | Portable MP3/AAC/FLAC/WAVE decoding, existing specialized decoders and synthesis, codec selection/fallback, PCM file preparation and signal analysis | JVM, no Android dependency |
| `audio-processing` | Android composition, MediaExtractor/MediaCodec decoder adapter, Android metadata and playhead analysis, optional tracker JNI packaging | Android API 26+ |

Portable MP3 uses JLayer; AAC uses our ADTS/MP4 demuxers and a JAAD synthesis adapter; FLAC and WAVE have library-owned streaming parsers/decoders. Each backend implements `PcmDecoder`. Third-party codec types and Android media objects do not appear in the PCM contracts. Existing MIDI, tracker, soundbank and chiptune renderers remain available through an adapter.

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

When the decoder knows the exact frame count, `AudioFileProcessor.openWaveSource` returns an `AudioWaveSource` after validating a bounded initial PCM block. The optional `FrameSeekablePcmStream` capability permits independent 4096-frame cache blocks: a cold read positions the decoder near the requested block and never exposes unwritten cache holes. Android's known-length standard codecs and the portable MPEG/WAVE decoders support frame positioning. MP3 positioning uses a lazy, bounded MPEG packet index and preroll, preserving encoder-delay alignment instead of relying on approximate timestamps. Other portable/composite decoders retain prefix decoding; unknown frame counts retain complete preparation. Stream metadata reports the actual access strategy, cached byte count, PCM precision/length/timing and input provenance. No duration is guessed from bitrate or silent samples.

The host can open borrowed views of that same PCM resource for output and visualizer analysis, sharing one decoder/cache. Closing a view does not close the owner. Closing the owner cancels reads and releases codec/file handles; the host removes its scratch file. Complete signal diagnostics are emitted only once all frame blocks have been validated, without counting repeated reads twice. On a late decoder/seek error the library reports the cause and retries its full preparation policy; output must retain the negotiated PCM format. Cancellation does not trigger retry. A retry can pause playback; a format-changing fallback is rejected rather than changing an already published WAVE header.

InfiniFolder adapts a borrowed PCM view to its existing Android MediaDataSource output edge. The new `AudioProcessing.openPlaybackSource` uses content plus valid MPEG timing to prefer the existing portable MPEG backend, measured substantially faster for playback read-ahead in the recorded environment. Android remains a fallback inside the library. Other sources retain platform-first policy, and the existing `openWaveSource`/explicit decoder-policy APIs retain their semantics. Player code never selects or calls a compressed codec directly.

The disk cache can grow to the whole decoded track, and RIFF output over 4 GiB is rejected. Encoded remote/container staging and legacy composite renderer limits are unchanged. Other hosts can consume `PcmStream` directly. Audible playback, interruption, seeking, visualizer alignment and linked gapless transitions require handset acceptance.

The host owns source/cache leases and output files. `PcmStream.close()` releases decoder-owned handles. Other source permissions and playback lifetimes remain host responsibilities.

See [portability roadmap](docs/portability-roadmap.md), [architecture](docs/architecture.md) and [codec implementations](docs/third-party.md). iOS native binaries and desktop output integrations are future work, not provided by a JVM JAR.

## InfiniStream compatibility

`audio-api` now depends on `libinfinistream:stream-core:0.1.0`. Clone the sibling repository at the workspace's pinned revision (foundation `1b68893`) before a standalone source build, or set `-PinfiniLibrariesDir`. `StreamAudioInput` accepts a known-length positional source without hidden spooling; `PcmStreamHandle` exposes actual typed PCM output metadata. `AudioStreamProcessor` advertises accepted/produced types and borrows encoded input while owning decoded output. Existing FileAudioInput callers retain file hints and use Stream positional I/O internally. Existing APIs, codecs and Android output behavior are preserved.

PCM output metadata preserves original source provenance/warnings and adds the audio-decode step while describing actual sample format/rate/channels; uncertain liveness remains unknown. Formats requiring file-relative banks or companion resources retain the File compatibility APIs until a common related-resource resolver is supplied; the app retains those file hints and behaviors.
