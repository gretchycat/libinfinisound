# Architecture and extraction history

Extracted 2026-10-30 from `viewer-audio`, following the [InfiniFolder library separation audit](https://github.com/gretchycat/infinifolder/blob/main/docs/library-separation-audit.md). This increment preserves the existing format implementations and playback policy. It adds no formats or synthesis features.

## Modules and consumers

| Module | Owns | Dependencies |
| --- | --- | --- |
| `audio-api` | `AudioProcessor`, codec adapter contract, decode request/result/events, PCM, metadata source and result types, playlist/CUE and CDDB record parsing, signal metric types | Kotlin/JDK types; Android library packaging with no Android types in the public contracts |
| `audio-processing` | Default codec composition, decoders, MIDI/tracker/chiptune rendering, soundbanks, PCM and spectrum analysis, Android metadata and analysis adapters | `audio-api`, private JLayer dependency, bundled ALAC implementation, optional libopenmpt JNI |
| `viewer-audio` | Player service, MediaPlayer/AudioTrack output, gapless queue/preload/handoff, UI/visualizer presentation, diagnostics history, source staging and leases, Cast/DLNA | `audio-processing`, existing viewer/storage integration and output dependencies |
| `storage-engine` | Existing playlist-backed virtual directories and source resolution | `audio-api` for playlist parsing; no dependency on codec implementations |

The full application and standalone audio host already consume `viewer-audio`, which now consumes these libraries. `audio-processing` has no dependency on an Activity, player service, storage runtime, cache, Cast service or output device. It remains an Android-compatible library because metadata and streamed analysis use Android media decoders. The small API does not require those implementations.

## Common codec interface

Use `AudioProcessing` as an `AudioProcessor`:

```kotlin
val processor: AudioProcessor = AudioProcessing
val supported = processor.isSupported(AudioProbe(headerBytes, extension))
val decoded = processor.decode(AudioDecodeRequest(bytes, extension, stagedFile))
val pcm = decoded?.pcm
```

Every default format is registered through `AudioDecoder`. Each adapter translates its underlying parser or synthesizer to `AudioPcmData` and descriptive metadata. The tracker adapter retains libopenmpt and its Kotlin fallback; MPEG Layer II still uses JLayer, and ALAC uses the existing bundled Java implementation. No third-party codec types occur in the common contracts. Existing decoder implementation entry points remain available for compatibility and fixture checks; hosts should use the common interface.

`AudioDecoderRegistry` accepts an ordered list of adapters and implements `AudioProcessor`. This permits future MIDI, tracker, VGM and other composite-audio implementations behind the same interface. The default composition retains the original dispatch order, extension and magic rules, synthesis settings, render duration caps, SFZ sample lookup, and fallback after null or failed decodes. The existing support hint and extension list are preserved, including playlist hints; a support hint does not guarantee a successful PCM decode.

The result includes the selected adapter name and source-format description. Per-request `AudioDecodeObserver` events provide successful decode, parser warnings, and exhausted fallback. The viewer's existing `UniversalAudioDecoder` is now a thin adapter from these events to `AudioPlaybackDiagnostics`, preserving existing engine labels and the `reportDiagnostics = false` preload behavior. The reusable library has no global playback report.

## Sources, processing and lifetimes

`AudioDecodeRequest` preserves the existing buffered decode path. Its optional file is borrowed for ALAC random access and SFZ relative sample lookup. The caller retains the staging lease until decoding finishes. This increment does not impose buffered decoding on ordinary MediaPlayer playback or change memory limits. Incremental rendering/seekable composite sessions can be added later; the existing buffered synthesizers have not been replaced with purported streaming implementations.

Format detection accepts header bytes without decoding a track. `AudioMetadataReader` borrows a local file, file descriptor or seekable byte source and releases its Android decoder before returning. The host closes descriptors, provider handles and staging leases. The content-URI fallback is an explicit Android edge adapter. Metadata results include encoded artwork; bitmap decoding, resizing and folder-artwork lookup stay in the viewer.

`AudioProcessing.createAnalyzer` returns an `AudioAnalysisSession`. It decodes PCM blocks around a playhead supplied by the host. It owns no output device and does not load a complete track into memory. Cancellation, sample callbacks and failure callbacks retain the previous analyzer behavior. The viewer connects failures to the existing diagnostic log and measurements to its visualizer views.

Signal health calculations now live in `AudioPcmHealth`; their metrics and thresholds are unchanged. Silent PCM remains a successful decode. Output completion continues to come from the existing player/decoder completion paths.

CDDB record parsing is also shared; local sidecar discovery, online lookup and its cache remain host-owned.

Both previous playlist parser packages now use the same unchanged implementation in `audio-api`. `com.infinifolder.playlist.PlaylistParser` remains a compatibility facade for storage and viewer callers. Playlist resolution, recursive expansion, CUE byte ranges, ordering, queue state and cache ownership remain with their existing hosts.

## Native packaging and validation

The existing Termux `buildOpenMptNative` task, JNI source, load names, dependent `.so` files and native test library path now belong to `audio-processing`. Consumer rules preserve the JNI class/method linkage during minification. This extraction does not add a new native build path: outside the existing Termux configuration, the prior optional-native/Kotlin-fallback behavior remains.

The original audio fixture and viewer integration tests remain in `viewer-audio`. Independent signal-analysis tests moved with their implementation; registry contract tests cover failed-codec fallback, successful silence, diagnostics and unsupported input. Device acceptance still needs local/network gapless transitions, CUE boundaries, seeking, interruption and Cast; JVM checks and APK builds cannot establish those runtime behaviors.

Validation on 2026-10-30 in the existing Termux environment:

- Built `:audio-api:assembleDebug` and `:audio-processing:assembleDebug` as separate AARs.
- Built `:app:assembleDebug` and `:viewer-host:assembleDebug -Pviewer=audio` offline.
- Passed 53 selected JVM checks: 7 processing/analysis contracts, 42 viewer/decoder/playlist/queue/Cast/CDDB checks, and 4 storage playlist-directory checks. The report-generating playback diagnostic fixture was not run; its historical report is unchanged.
- Confirmed the processing AAR and both APKs contain the same seven expected ARM64 tracker JNI/dependency libraries. The API AAR contains no native libraries.
- Confirmed neither audio library references viewer or storage-runtime classes; `git diff --check` passed.

These checks do not constitute device playback verification. Existing compiler deprecation and Termux AAPT2 diagnostics were emitted; both builds completed successfully.
