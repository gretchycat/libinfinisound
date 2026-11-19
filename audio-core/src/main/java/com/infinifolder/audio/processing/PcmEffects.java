package com.infinifolder.audio.processing;

import java.io.InterruptedIOException;
import java.util.Objects;

/** Portable, non-destructive float PCM effects. Frame ranges are half-open. */
public final class PcmEffects {
    private PcmEffects() {}
    public enum Filter { LOW_PASS, HIGH_PASS, NOTCH }
    public static final int MAX_SAMPLES = 16_000_000;

    private static void cancelled(int frame) throws InterruptedIOException {
        if ((frame & 1023) == 0 && Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Audio processing cancelled");
    }
    private static float[] copy(float[] input, int channels, int from, int to) throws InterruptedIOException {
        Objects.requireNonNull(input);
        if (channels < 1 || channels > 32 || input.length > MAX_SAMPLES || input.length % channels != 0 || from < 0 || to <= from || to > input.length / channels)
            throw new IllegalArgumentException("Invalid PCM channels, selection or sample budget");
        for (int i = 0; i < input.length; i++) {
            cancelled(i);
            if (!Float.isFinite(input[i])) throw new IllegalArgumentException("PCM must be finite");
        }
        return input.clone();
    }
    private static void rate(int sampleRate) {
        if (sampleRate < 8000 || sampleRate > 192000) throw new IllegalArgumentException("Sample rate must be 8..192 kHz");
    }
    private static float finite(double value) {
        float out = (float)value;
        if (!Float.isFinite(out)) throw new IllegalArgumentException("Audio processing exceeded float range; lower the input gain");
        return out;
    }
    /** Reverse polarity, without reversing time or changing levels. */
    public static float[] invert(float[] input, int channels, int from, int to) throws InterruptedIOException {
        float[] out = copy(input, channels, from, to);
        for (int f = from; f < to; f++) {
            cancelled(f);
            for (int ch = 0; ch < channels; ch++) out[f * channels + ch] = -out[f * channels + ch];
        }
        return out;
    }
    /** Linked normalization preserves channel balance; optional DC removal is per channel. */
    public static float[] normalize(float[] input, int channels, int from, int to, double targetDb, boolean linked, boolean removeDc) throws InterruptedIOException {
        if (!Double.isFinite(targetDb) || targetDb < -60 || targetDb > 0) throw new IllegalArgumentException("Target peak must be -60..0 dBFS");
        float[] out = copy(input, channels, from, to);
        double[] mean = new double[channels], peak = new double[channels];
        if (removeDc) {
            for (int f = from; f < to; f++) { cancelled(f); for (int ch = 0; ch < channels; ch++) mean[ch] += input[f * channels + ch]; }
        }
        if (removeDc) for (int ch = 0; ch < channels; ch++) mean[ch] /= to - from;
        double largest = 0;
        for (int f = from; f < to; f++) {
            cancelled(f);
            for (int ch = 0; ch < channels; ch++) {
                peak[ch] = Math.max(peak[ch], Math.abs(input[f * channels + ch] - mean[ch]));
                largest = Math.max(largest, peak[ch]);
            }
        }
        double target = Math.pow(10, targetDb / 20);
        for (int f = from; f < to; f++) {
            cancelled(f);
            for (int ch = 0; ch < channels; ch++) {
                double p = linked ? largest : peak[ch];
                out[f * channels + ch] = p == 0 ? 0 : finite((input[f * channels + ch] - mean[ch]) / p * target);
            }
        }
        return out;
    }
    /** Second-order RBJ biquad; Q controls resonance/notch width. Channels never share filter state. */
    public static float[] filter(float[] input, int channels, int sampleRate, int from, int to, Filter kind, double hz, double q) throws InterruptedIOException {
        rate(sampleRate); Objects.requireNonNull(kind);
        if (!Double.isFinite(hz) || hz <= 0 || hz >= sampleRate / 2.0 || !Double.isFinite(q) || q < .1 || q > 30)
            throw new IllegalArgumentException("Filter frequency must be below Nyquist; Q must be 0.1..30");
        float[] out = copy(input, channels, from, to);
        double w = 2 * Math.PI * hz / sampleRate, cos = Math.cos(w), alpha = Math.sin(w) / (2 * q);
        double a0 = 1 + alpha, a1 = -2 * cos / a0, a2 = (1 - alpha) / a0;
        double b0, b1, b2;
        switch (kind) {
            case LOW_PASS -> { b0 = (1 - cos) / 2 / a0; b1 = (1 - cos) / a0; b2 = b0; }
            case HIGH_PASS -> { b0 = (1 + cos) / 2 / a0; b1 = -(1 + cos) / a0; b2 = b0; }
            case NOTCH -> { b0 = 1 / a0; b1 = -2 * cos / a0; b2 = b0; }
            default -> throw new IllegalArgumentException("Unknown filter");
        }
        double[] z1 = new double[channels], z2 = new double[channels];
        // Borrow up to 100 ms of preceding audio to warm state, without changing that audio.
        int warm = Math.max(0, from - sampleRate / 10);
        for (int f = warm; f < to; f++) {
            cancelled(f);
            for (int ch = 0; ch < channels; ch++) {
                int i = f * channels + ch; double x = input[i], y = b0 * x + z1[ch];
                z1[ch] = b1 * x - a1 * y + z2[ch]; z2[ch] = b2 * x - a2 * y;
                if (f >= from) out[i] = finite(y);
            }
        }
        softenEdges(input, out, channels, sampleRate, from, to);
        return out;
    }
    /** Linked stereo/multichannel gate. Below-threshold audio is reduced, never replaced with generated sound. */
    public static float[] noiseGate(float[] input, int channels, int sampleRate, int from, int to,
            double thresholdDb, double reductionDb, double attackMs, double holdMs, double releaseMs) throws InterruptedIOException {
        rate(sampleRate);
        if (!Double.isFinite(thresholdDb) || thresholdDb < -100 || thresholdDb > 0 || !Double.isFinite(reductionDb) || reductionDb < 0 || reductionDb > 100
                || !Double.isFinite(attackMs) || attackMs < 0 || attackMs > 500 || !Double.isFinite(holdMs) || holdMs < 0 || holdMs > 5000
                || !Double.isFinite(releaseMs) || releaseMs < 1 || releaseMs > 5000)
            throw new IllegalArgumentException("Invalid gate threshold, reduction or envelope times");
        float[] out = copy(input, channels, from, to);
        double threshold = Math.pow(10, thresholdDb / 20), closed = Math.pow(10, -reductionDb / 20);
        double attack = attackMs == 0 ? 0 : Math.exp(-1000 / (attackMs * sampleRate));
        double release = Math.exp(-1000 / (releaseMs * sampleRate)), gain = 1;
        int holdFrames = (int)Math.round(holdMs * sampleRate / 1000), remaining = 0;
        int warm = Math.max(0, from - sampleRate / 10);
        for (int f = warm; f < to; f++) {
            cancelled(f); double peak = 0;
            for (int ch = 0; ch < channels; ch++) peak = Math.max(peak, Math.abs((double)input[f * channels + ch]));
            if (peak >= threshold) remaining = holdFrames;
            double target = peak >= threshold || remaining > 0 ? 1 : closed;
            if (peak < threshold && remaining > 0) remaining--;
            double coefficient = target > gain ? attack : release;
            gain = target + coefficient * (gain - target);
            if (f >= from) for (int ch = 0; ch < channels; ch++) out[f * channels + ch] = finite(input[f * channels + ch] * gain);
        }
        softenEdges(input, out, channels, sampleRate, from, to);
        return out;
    }
    private static void softenEdges(float[] input, float[] out, int channels, int rate, int from, int to) throws InterruptedIOException {
        int ramp = Math.min(rate / 200, (to - from) / 2);
        if (ramp < 2) return;
        for (int f = from; f < to; f++) {
            cancelled(f);
            double mix = 1;
            if (from > 0) mix = Math.min(mix, (double)(f - from) / (ramp - 1));
            if (to < input.length / channels) mix = Math.min(mix, (double)(to - f - 1) / (ramp - 1));
            if (mix < 1) for (int ch = 0; ch < channels; ch++) { int i = f * channels + ch; out[i] = finite(input[i] * (1 - mix) + out[i] * mix); }
        }
    }
}
