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
