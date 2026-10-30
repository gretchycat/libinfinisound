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
