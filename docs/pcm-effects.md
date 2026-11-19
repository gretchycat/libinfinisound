# Portable PCM effects

`com.infinifolder.audio.processing.PcmEffects` (audio-core 0.2.1) processes finite,
interleaved float PCM without Android or synthesis dependencies. Functions borrow
the input and return a copy; samples outside the half-open frame range [from,to)
remain bit-identical. Rates are 8..192 kHz, channels 1..32, and the input budget is
16 million float samples. Invalid parameters fail explicitly. Processing observes
thread interruption and throws InterruptedIOException; inputs stay untouched.

- invert reverses polarity by multiplying selected samples by -1; time order stays.
- normalize accepts a target peak -60..0 dBFS, linked/independent channels and optional
  per-channel DC removal. Linked peaks preserve stereo balance; silence stays silent.
- filter accepts LOW_PASS, HIGH_PASS or NOTCH, frequency below Nyquist and Q 0.1..30.
  Each channel has independent state. The biquad uses the equations in the
  [W3C Audio EQ Cookbook](https://www.w3.org/TR/audio-eq-cookbook/); implementation
  is library-owned. Up to 100 ms of preceding audio warms filter state.
- noiseGate uses a linked peak detector, threshold -100..0 dBFS, reduction 0..100 dB,
  attack 0..500 ms, hold 0..5000 ms and release 1..5000 ms. The same gain envelope
  applies to all channels, preserving stereo balance. It reduces quiet passages;
  it is not learned-profile spectral denoising. Start gain is unity, with smoothing
  and up to 100 ms of preceding audio used for warm-up.

Filters and gates blend through up to 5 ms at selection edges adjoining untouched
audio to limit clicks. Effects do not clamp legitimate float over-range levels;
hosts choose output clipping. Non-finite/overflow results fail explicitly. Hosts
own selecting, history, preview, playback and saving; those are not library state.
