# Validation of portable/native decoding

Validated 2026-10-30 in the existing Termux/JVM and Android device runtime environment.

- 18 core checks passed, including no Android runtime on the codec classpath, real MP3/AAC/FLAC/WAVE samples, exact FLAC sample reconstruction through 32 bits, MPEG/MP4 timing, failed/unavailable platform codec fallback, cancellation and CRC failure cleanup.
- 44 viewer/audio checks and 4 storage playlist-directory checks passed: 66 checks in total.
- The standalone library AAR and both InfiniFolder/full and standalone-audio debug APKs built successfully.
- A generated Java harness was compiled to DEX and run with Android's `app_process64`, loading the built APK's library code. It decoded MP3, ADTS AAC, MP4 AAC, 16-bit FLAC, 24-bit FLAC and 32-bit FLAC under both portable-only and platform-first policy: 12 cases, with no audio output requested.

| Fixture | Portable backend | Platform-first selection | PCM frames |
| --- | --- | --- | --- |
| Chord MP3, 44.1 kHz stereo | Portable.MPEG | Android.MediaCodec | 66,150 |
| Chord ADTS AAC, 44.1 kHz stereo | Portable.AAC | Android.MediaCodec | 67,584 |
| Chord MP4 AAC, 44.1 kHz stereo | Portable.AAC | Android.MediaCodec | 66,150 |
| Chord FLAC, 44.1 kHz stereo | Portable.FLAC | Android.MediaCodec | 66,150 |
| 24-bit FLAC, 48 kHz stereo | Portable.FLAC | Portable.FLAC (precision fallback) | 5,003 |
| 32-bit FLAC, 48 kHz stereo | Portable.FLAC | Portable.FLAC (precision fallback) | 5,003 |

Portable/platform lossy PCM agreement was 53.29 dB for MP3 and 44.78 dB for AAC. FLAC PCM was identical. ADTS has no explicit musical edit list in this fixture; its decoded duration is retained rather than guessing silence trim. Both MP4 paths apply the declared musical duration and encoder delay, avoiding the additional AAC tail emitted by raw decoding.

The Android harness verifies parsing/decoding and backend loading on the device, including portable AAC without Java Sound playback APIs. It does not verify audible output, routing, seeking or gapless handoff. Those still need app playback acceptance. Desktop OS and native iOS execution were not tested; the portable JVM boundary and future native requirements are documented in the roadmap.

## Pull-based PCM output checkpoint

The core now has 33 passing tests, including 13 new PCM source cases: bounded opening independent of track length, positional/cursor independence, cache reuse, unknown-length preparation, exact MP3/WAVE/16–32-bit FLAC transport, AAC timing/header preservation, initial/late backend failure, cancellation, borrowed analysis views, invalid ranges, RIFF limits, silence and concurrent reads. Separate AAC synthesis calls use JAAD's evolving PNS state; the AAC check compares exact timing/header/length and nonempty signal, without claiming byte equality across separate decodes. The serializer byte comparisons remain exact for the other fixtures.

Full Android and portable-only standalone builds pass. The consuming application's gate reports 625 cases: 595 pass and the same 30 pre-migration failures, with no regressions or missing cases. Two new host transport tests pass. The preceding 27 non-rendering device checks repeat against the built APK.

Seven Android MediaExtractor checks consume the new PCM source (MP3, ADTS AAC, MP4 AAC, WAVE and 16/24/32-bit FLAC), including middle/backward reads and exact declared output length. High-resolution FLAC selects the precision-preserving portable backend. A generated two-minute MP3 prepares in MediaPlayer without starting output in 268 ms in the recorded local run: the PCM cache contains 135,016 of 21,168,044 bytes and the player reports exactly 120,000 ms. These timings are fixture/device observations, not a remote-storage latency guarantee.

The host provides `scripts/verify-audio-pull-runtime.py` and its Java harness for repeating those eight device checks. No audible output is requested. Seeking during actual playback, gapless timing, speed/routing, visualizer alignment and remote staging still require handset checks.

## Audio frame seeking checkpoint

The core has 53 passing tests. Twenty new cases cover independent decoded blocks, valid sparse-cache reads, block boundaries/cursors, metrics without duplicate samples, seek/backend failure fallback, portable WAVE positioning, bounded MPEG packet indexing, short reads, malformed metadata, cancellation, content-based playback policy and sample-exact portable MPEG seek windows. No codec synthesis implementation or output device moved into the common API.

FrameSeekablePcmStream positions the decoder in its trimmed output timeline. Android's standard backend and portable MPEG/WAVE implement it. MPEG seeks use exact packet positions and preroll; the portable adapter accounts for startup frames omitted by the reservoir decoder. Other decoders retain explicit prefix access. Indexed caches return only valid blocks; metadata reports the actual strategy and cached bytes. The existing prepared-file and explicit backend APIs remain available.

In the recorded two-minute MP3 benchmark, full portable decoding took 777 ms versus 10,030 ms through Android. The library's playback policy therefore prefers portable MPEG for validated MPEG timing and retains Android fallback. Other formats retain platform-first playback. Complete sample buffers are copied from Android's native PCM buffers in bulk; codec input queues do not block ahead of ready output.

The host gate reports 645 cases: 615 pass, the same 30 pre-migration failures and no new failures or missing cases. The full APK, audio host, standalone Android Sound and portable-only builds pass. The preceding 35 device checks repeat; the long-track case now waits for actual MediaPlayer seek-complete callbacks in both directions and compares seeked windows byte-for-byte with linear portable decoding. The final recorded callback run completed seeks in 26–362 ms and prepared in 188 ms. These are local fixture/device observations. Audible seek transitions, route/speed changes, gapless handoffs and remote/provider behavior still need handset acceptance. The standard MediaDataSource PCM output edge is retained; no packet container or proxy descriptor is required.

## Trailing MP3 metadata and long seeks

The core now has 61 passing tests. Two synthesized regression cases failed before the fix: adding an ID3v1 footer shortened continuous MPEG decoding and prevented exact reads through the declared end after a seek. Both now pass. Six byte-view cases cover the constant three-byte footer probe, short reads, exact payload bounds, markers elsewhere, malformed reads, cancellation and ownership. The input file and encoder trim are retained.

A local owner-supplied MP3 reproduced a seek timeout: JLayer discarded its last packet at the ID3v1 tag, making the portable output 241 frames short and triggering full-file recovery. Removing only the footer from a temporary copy confirmed the cause. The library now hides that metadata from the codec. No owner-supplied recordings are distributed as fixtures; committed regressions derive from the synthesized chord.

The consuming app repeats native seek-complete checks on three affected files with muted playback and concurrent PCM analysis, comparing seek windows exactly against continuous decoding and rejecting full-preparation fallback. Its runtime script also generates fifteen-minute variable/320 kbps constant-bitrate MP3s, tests jumps beyond five minutes, and verifies automatic linked-player handoff with tagged synthesized tracks. These are local pipeline checks; audible continuity and other devices remain handset acceptance work.

The full APK, standalone audio host, standalone Android library and portable-only core build pass. The consuming application reports 653 cases: 623 pass and the same 30 known pre-migration failures, with no new failures or missing cases. The 27 preceding non-rendering checks, seven PCM extractor cases, two long-track seek cases, linked handoff case and three local affected-file cases pass against the same final APK. An additional exploratory long 24-bit FLAC probe exposed an existing Android seek/PCM-format fallback failure; it is retained as follow-up evidence and is not counted as passed.

## FLAC positioning and common encoded sources (2026-10-31)

The core now has 69 passing cases, including eight new FLAC seek cases. They compare exact mono/stereo 16/24/32-bit samples, pending-buffer resets, fixed/variable blocks, bounded cold far reads, one-byte input reads, corrupt targets, cancellation/close and a complete valid-looking frame embedded inside verbatim samples. Seek candidates validate frame CRCs and the following frame's sample-number link. The existing reconstruction algorithms remain.

The reproduced fifteen-minute 24-bit failure is repaired. Android raw extraction on this device returned 16-bit data; changing its format hint did not change those bytes. The backend now rejects actual precision loss before publishing PCM. Wider codec containers normalize to the source's canonical width for format-stable fallback. Portable FLAC fills independent PCM blocks instead of decoding skipped prefixes.

The host gate reports 683 cases: 653 pass and the same 30 known pre-migration failures, with no new failures or missing cases. Full app, standalone audio/image hosts, Android Sound/Canvas and portable Sound/Stream builds pass. Thirty new cases span FLAC, shared resource ownership/cursors, audio input selection/cancellation, unsupported provider access and authoritative image-source selection.

The same final APK passes 46 pipeline cases: the preceding 27 checks, seven PCM extractor cases, three fifteen-minute seek cases (variable/320 kbps MPEG and 24-bit FLAC), file/shared-source tagged gapless handoff, three direct positional metadata/decode cases, explicit staging fallback and three local owner-supplied MP3 cases. Positional MPEG/FLAC windows match continuous portable decoding exactly; AAC validates its exact frame count/tags and PCM reads without claiming cross-backend sample equality. Native seek checks run muted with concurrent analysis, retain the local 1500 ms budget and wait for the requested target's callback because older completions may arrive after a listener change. Original recordings remain outside source control.

Live remote latency, visualizer/image UI and audible high-resolution playback still require handset acceptance. A cold MPEG seek still scans encoded packet headers. Provider timeouts and expensive archive extraction adapters retain their limits. The known long-FLAC failure recorded in the preceding checkpoint is now resolved; no universal codec/network or native iOS claim is made.

## Substitute-audio rejection correction (2026-10-31)

Six new core tests reject the former fixed-chord paths, including RealMedia
headers without an extension, assert that failed preparation publishes no WAVE,
and verify missing sample failures plus exact SFZ sample bytes/rate. Core total:
75 passing cases. The host corrects three old fixture expectations that treated
placeholder synthesis as decoding; its regenerated JVM report records those
unavailable codecs as failures rather than claiming successful device playback.
The 830-case host gate retains the same 30 pre-migration failures. Packaged-APK
checks reject invalid common inputs and missing soundbank samples and confirm
AMR-NB/WB still decode through actual Android.MediaCodec backends.

## Declared codecs and ALAC input boundaries (2026-10-31)

Unknown codec identifiers in AU, AIFC, CAF, RF64/Wave64, IFF and VOC now reject
instead of interpreting their payload as PCM. Header/chunk/frame validation
preserves supported PCM, float and companding paths. MAUD reads its actual clock
divisor and compression fields; VOC rejects unimplemented timing blocks and
format changes rather than silently skipping or relabeling them. Registered
backends may still decode a source after another backend rejects it.

ALAC input helpers fill short reads, detect EOF without inventing zero/stale
header bytes and implement forward positioning, retaining the demuxer's explicit
reopen fallback for backward positioning. Frames reject unsupported predictors,
representations and out-of-packet bit reads. The adapter correctly reduces 24-bit
little-endian output to 16-bit PCM. New independent synthetic ALAC fixtures
compare every sample across full and short packets at both precisions; malformed
header/frame tests check rejection before output. Original BSD notices remain.

This checkpoint has 95 passing core cases (20 new boundary/format tests). The
consuming app's 867-case gate preserves all earlier outcomes, including its 30
known storage failures. The packaged Android audit rejects all nine altered audio
inputs, including standard WAVE, and all nine original audio controls retain
identical PCM SHA-256 hashes. Native playback/seek/gapless validation is recorded
by the consuming application's checkpoint; JVM checks do not establish sound
output on a device.
