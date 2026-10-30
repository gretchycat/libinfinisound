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

InfiniFolder's normal and gapless preload paths give encoded sources to `AudioProcessing.prepareForOutput` and give only the resulting PCM WAVE files to MediaPlayer for output. Duration probing also goes through the library. Player code does not invoke the compressed-format extractor/codec or pass an original compressed file to MediaPlayer.

The app currently prepares the PCM file before playback, preserving its existing MediaPlayer gapless handoff. Standard codec processing uses bounded sample buffers, but decoded files consume disk space and preparation can increase startup/preload time. The RIFF writer rejects output over its 4 GiB size limit. A future output adapter can consume `PcmStream` directly to avoid full-file staging. Existing composite renderers still use their previous buffered synthesis limits.

The host owns source/cache leases and output files. `PcmStream.close()` releases decoder-owned handles. Other source permissions and playback lifetimes remain host responsibilities.

See [portability roadmap](docs/portability-roadmap.md), [architecture](docs/architecture.md) and [codec implementations](docs/third-party.md). iOS native binaries and desktop output integrations are future work, not provided by a JVM JAR.
