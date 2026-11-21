# Tempo and transient estimation

AudioTempoEstimator lives in the portable audio-core module. It consumes the
already-normalized first four spectrum bands and media playback position in
milliseconds. It performs no PCM decoding or FFT; the host's existing analyzer
continues to own those operations.

## Implemented behavior

- Samples are expected near 30 Hz, matching PlaybackPcmAnalyzer. Duplicate
  positions are ignored. A backward seek or a gap over 250 ms clears the
  estimate/history; sample gaps over 100 ms also clear the correlation history.
- The estimator tracks low-band energy rises above a deadband as short transient
  events. Each emitted transient has an increasing sequence ID and normalized
  strength in [0,1]. IDs do not reset across calls to reset(), so a consumer
  can safely use them for event deduplication.
- A normalized autocorrelation over up to eight seconds estimates 70–180 BPM.
  It needs five seconds of history and a confidence threshold before returning a
  BPM. Estimates are smoothed and large changes are bounded.
- Silence or weak/non-periodic input yields no BPM. After repeated low-confidence
  windows, an earlier estimate expires. reset() clears tempo history and
  estimate but keeps event IDs monotonic.

The result is a visual tempo cue, not a musical transcription, downbeat detector,
or authoritative BPM tag. Dense syncopation, half/double-time ambiguity, a weak
bass line, device codec differences, or changing playback speed can make the
estimate unavailable or approximate. Consumers should use authored BPM
metadata when available, and treat null as the normal fallback.

## Infinimedia adapter

Infinimedia feeds the analyzer's existing normalized 16 bands and playback
position into this estimator. The adapter forwards the optional BPM to
AudioFrameMetadata.bpm and maps fresh transient IDs to timestamped
FxAudioEvents. libinfinifx scales rotozoom's configured rotation speed around
120 BPM and uses each recent transient to pulse zoom/rotation once. The audio
engine remains independent of InfiniMedia and InfiniFX.

## Build and test

    ./gradlew :audio-core:test --no-daemon --max-workers=1 --console=plain

The portable test feeds synthetic 120 BPM low-band pulses, checks the tempo
range and increasing transient IDs, then checks silence and a backward seek.
