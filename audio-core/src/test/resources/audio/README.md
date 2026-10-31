# Codec fixtures

Chord fixtures are InfiniFolder's synthesized C-major piano fixtures from `filesystem-test-images`; no recorded or personal audio is used.

`mono.flac`, `stereo.flac` and `high-resolution.flac` were generated with FFmpeg 8.1.2. `full-resolution.flac` uses the reference libFLAC encoder's 32-bit mode. Each contains 5,003 deterministic frames at 48 kHz: signed sample `((frame * 7919 + channel * 17011) % (1 << bits)) - (1 << (bits - 1))`. Mono/stereo use 16-bit input; high-resolution and full-resolution use 24-bit and 32-bit stereo. Tests reconstruct samples independently and compare them exactly. Test execution needs neither FFmpeg nor libFLAC.

`boundary-16.m4a` and `boundary-24.m4a` are synthetic 48 kHz stereo ALAC
fixtures (5,003 frames, FFmpeg ALAC encoding). Channel samples are exactly
`((frame * 7919 + channel * 17011) % 2^bits) - 2^(bits-1)`.
They cover full/short packets, EOF and accurate conversion to 16-bit PCM.
