# libinfinisound

Audio parsing, decoding, synthesis, metadata, playlists and signal analysis extracted from [InfiniFolder](https://github.com/gretchycat/infinifolder). Audio output, player queues, UI, storage/cache ownership and Cast routing belong to the consuming application.

Codec adapters retain libopenmpt with its Kotlin tracker fallback, JLayer MPEG Layer II decoding, bundled Java ALAC decoding, MIDI and soundbank synthesis, chiptune rendering and the other existing format parsers. Third-party codec types do not appear in the common interface. This repository move changes no format support or rendering behavior.

## Modules

| Module | Responsibility |
| --- | --- |
| `audio-api` | Processing contracts, PCM, metadata inputs/results, playlist/CUE/CDDB parsing, signal metrics; no third-party codecs. |
| `audio-processing` | Default codec composition, decoders and synthesizers, metadata probing, PCM/spectrum analysis, optional tracker JNI. Depends on `audio-api`. |

These are Android libraries targeting Java 17, compile SDK 36 and Android API 26 or later. Processing uses Android media APIs for metadata and streamed analysis; the contracts use Kotlin/JDK types.

## Build

Install Gradle, JDK 17 or later and Android SDK 36. Set `ANDROID_HOME` or create an untracked `local.properties` containing `sdk.dir=/path/to/android-sdk`.

```sh
gradle :audio-api:assembleDebug :audio-processing:assembleDebug
gradle :audio-processing:testDebugUnitTest
```

AARs are written to each module's `build/outputs/aar/` directory. Dependency versions are in the root build; `--offline` works with a populated dependency cache.

In Termux, the native build uses installed `clang`, `patchelf`, libopenmpt and its dependencies from the Termux prefix. Configure the native AAPT2 override in Gradle as needed. JNI sources, the build script and packaged native dependencies belong to `audio-processing`. Outside that configuration the existing Kotlin tracker fallback remains available; this move adds no new NDK build path.

## Import into another build

Check out this repository at a pinned revision, for example as a Git submodule. In the host's `settings.gradle.kts`:

```kotlin
includeBuild("libraries/libinfinisound")
```

Then select the dependency needed by each consumer:

```kotlin
// Contracts and playlist parsing:
implementation("io.github.gretchycat.libinfinisound:audio-api:0.1.0")

// Full processing, including the API:
implementation("io.github.gretchycat.libinfinisound:audio-processing:0.1.0")
```

Gradle substitutes these coordinates with the checked-out source. These are composite-build project coordinates; this repository has not published artifacts to Maven Central. InfiniFolder pins its implementation with a submodule commit.

## Use

```kotlin
import com.infinifolder.audio.AudioDecodeRequest
import com.infinifolder.audio.AudioProbe
import com.infinifolder.audio.AudioProcessing
import com.infinifolder.audio.AudioProcessor

val processor: AudioProcessor = AudioProcessing
val supported = processor.isSupported(AudioProbe(headerBytes, extension))
val result = processor.decode(AudioDecodeRequest(bytes, extension, stagedFile))
val pcm = result?.pcm
```

`AudioDecoderRegistry` accepts ordered `AudioDecoder` adapters. Future MIDI, tracker, VGM and other composite-audio implementations can use the same interface. Current codecs retain their buffered rendering and duration limits; this move adds no incremental composite rendering.

The caller owns input files, descriptors and staging leases. Detection accepts header bytes, and `AudioProcessing.createAnalyzer` streams analysis blocks near a supplied playhead. Silent PCM is valid audio. Decode diagnostics are per request and have no dependency on a playback service.

See [architecture and extraction history](docs/architecture.md) for adapter details, source lifetimes and validation. Existing third-party source notices are retained.
