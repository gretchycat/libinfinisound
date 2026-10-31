# Portability roadmap

## Shared stream architecture

libinfinistream is the platform-neutral transport/metadata foundation for the library family. StreamAudioInput adapts its known-length positional SourceResources, PcmStreamHandle exposes actual typed PCM metadata, and AudioStreamProcessor declares encoded/PCM ports and pull ownership. FileAudioInput uses the shared positional implementation while retaining legacy file hints. Store opens resources; Vault is used only for containers; Sound consumes their sources without depending on either implementation or the application. Existing AudioInput/PcmStream APIs remain available during migration. JVM contracts do not imply native iOS binaries.

The required host targets are Android, Linux, Windows, macOS and iOS. Parsing/processing and audio output have separate contracts.

## Implemented foundation

`audio-api` and `audio-core` are JVM libraries without Android imports. Their standard codec paths operate on reopenable `AudioInput` byte sources and return bounded `PcmStream` blocks. Decoder implementations are registered behind one interface; Android MediaExtractor/MediaCodec is registered only by `audio-processing`. A host can select portable-only decoding. The existing Java/File-based compatibility APIs remain separate from the new byte-source/PCM interfaces.

The JVM core can be used by Linux, Windows and macOS JVM applications. This increment validates it on the available JVM and Android build environment; it does not claim runtime testing on each desktop OS. Output adapters for those systems are not included.

## Android

The app now uses the library for normal playback, preload, buffered transcoding and duration decoding. Original compressed files never go directly to the player. MediaPlayer consumes library-generated PCM for output; its queue, speed, routing, focus and gapless handoff remain in the host. Native codec behavior, interruption and gapless transitions still require device acceptance.

## Desktop hosts

Add host output adapters for Linux, Windows and macOS, consuming `PcmStream` directly. Register platform decoders where useful alongside portable codecs, without changing the consuming player. Package optional tracker JNI for each supported OS/CPU and validate high-resolution PCM, seeking, cancellation and gapless transitions.

## iOS and native macOS

The current core includes JVM implementations and is not an iOS binary. Move the platform-neutral stream/model contracts into a Kotlin common/native or C-compatible API, with File/FileDescriptor conveniences kept in a JVM bridge. Provide native implementations of byte access and codec adapters (including native replacements for the JVM JLayer/JAAD implementations), then add Core Audio/AudioToolbox output and optional platform decoding adapters. Validate and package iOS/macOS architectures with their native toolchains. JVM portability alone is not iOS support.

## Composite audio

Retain and extend MIDI/tracker/VGM metadata, instrument/voice control, subsongs, sample-bank resolution and incremental rendering through library-owned interfaces. Existing buffered renderer limits are preserved in this increment. Add streaming composite sessions and richer metadata without exposing backend-specific objects to players.

The current host can consume common known-length positional resources for playback and metadata, keeping explicit staging where a provider or companion-file renderer requires it. Portable FLAC now provides sample-exact positioning through 32 bits, with real Android precision checks and canonical output widths. These changes preserve the existing native linked-player mechanism; they do not implement desktop/iOS output or remove live-provider acceptance work.
