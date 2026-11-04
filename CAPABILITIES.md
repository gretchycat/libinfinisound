# Runtime capabilities — sound

Updated 2026-11-04.

## Linked dependencies

Stream via `audio-api`; JLayer and JAAD in `audio-core`; optional Android/native backends in `audio-processing`. Internal Guitar Pro module is a declared dependency.

Dependencies must be packaged/linked, not assumed to be present. Standalone source
builds resolve sibling repositories through includeBuild (or
`-PinfiniLibrariesDir=/path/to/libraries`). Android modules need SDK 36, min API 26.
A library dependency is allowed; an InfiniFolder application/UI dependency is not.

## Public discovery and execution

`SoundCapabilities.registry`, `PcmCapabilityHandler`, existing `AudioFileProcessor` and `AudioStreamProcessor`. PCM candidates are discovered on invocation, not captured in a fixed constructor list.

Use `RuntimeCapabilities.library("sound")` for the common query, probe, callback and
revision API. The schema and registration contract are documented in the linked
libinfinistream repository's `CAPABILITIES.md`. Providers ship a standard
`META-INF/services/io.github.gretchycat.infinistream.CapabilityProvider` resource.
Constructing a provider must not connect, open input or initialize the application.

Implement PcmDecoder, register its PcmCapabilityHandler and provider. Describe actual format facts and decoder tier. Metadata artwork exposes the common EncodedImageExtractor service. All facade and plugin decoding uses existing implementations.

Queries retain overlapping handlers. Availability is separate from metadata-only
identity. Resource probes are bounded/cancellable and can narrow operations; real
API execution still validates input. Use existing Stream sources, positional I/O,
owned outputs and typed processors; do not duplicate decoders in consumers.

## Limits

Portable and Android tiers retain fallback ordering. Legacy decoders remain a grouped CONDITIONAL adapter, including optional tracker/synth/companion-file cases. Android codec enumeration and native symbol/ABI probes report backend presence; source support and SoundFont availability can still be conditional. Nothing generates replacement audio on failure.

Discovery is for installed classpath components, not a downloaded code loader.
Android/native display and live remote behavior require device checks in addition
to JVM tests. Descriptive catalog exports do not prove runtime support.

## Standalone build and tests

```sh
gradle :audio-api:test :audio-core:test :audio-guitarpro:test :audio-processing:testDebugUnitTest :audio-processing:assembleDebug --offline
```

No browser process is required. Keep existing format regression suites; capability
tests invoke actual services/parsers rather than scripted success responses.

## Host audit record

InfiniFolder's 2026-11-04 runtime-discovery audit exercises this repository through
its standalone Gradle test task, and also builds the main and combined standalone
viewer APKs. The host `docs/runtime-capabilities.md` records exact commands, fresh
counts, failures repaired during the gate and unverified device paths. This is
separate from the historical extraction evidence in SOURCE_PROVENANCE.md.
