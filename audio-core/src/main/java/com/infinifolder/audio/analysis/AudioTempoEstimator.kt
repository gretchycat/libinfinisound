package com.infinifolder.audio.analysis

import kotlin.math.sqrt

/** A tempo estimate plus one newly detected low-band transient, if present. */
data class AudioTempoUpdate(
    val bpm: Float?,
    val onsetSequence: Long?,
    val onsetStrength: Float,
)

/**
 * Estimates musical tempo from successive normalized low-frequency analysis bands.
 * The estimate is intentionally conservative: it is null until a repeating pulse
 * has enough history and correlation confidence. Position discontinuities reset it.
 */
class AudioTempoEstimator(
    private val minimumBpm: Float = 70f,
    private val maximumBpm: Float = 180f,
) {
    private val history = FloatArray(HISTORY_SIZE)
    private val ordered = FloatArray(HISTORY_SIZE)
    private var historyCount = 0
    private var nextIndex = 0
    private var lastObservedPositionMs = -1L
    private var lastSamplePositionMs = -1L
    private var previousLowEnergy = 0f
    private var sampleCountSinceEstimate = 0
    private var lowConfidenceEstimates = 0
    private var estimatedBpm: Float? = null
    private var onsetSequence = 0L

    init {
        require(minimumBpm.isFinite() && maximumBpm.isFinite() && minimumBpm in 40f..180f && maximumBpm in 70f..220f)
        require(minimumBpm < maximumBpm)
    }

    /** Feed one analysis snapshot and its media playback position in milliseconds. */
    @Synchronized
    fun update(positionMs: Long, normalizedBands: FloatArray): AudioTempoUpdate {
        require(positionMs >= 0L)
        if (lastObservedPositionMs >= 0L) {
            val delta = positionMs - lastObservedPositionMs
            if (delta == 0L) return noOnset()
            if (delta < 0L || delta > MAX_POSITION_GAP_MS) reset()
        }
        lastObservedPositionMs = positionMs

        if (lastSamplePositionMs >= 0L) {
            val sampleDelta = positionMs - lastSamplePositionMs
            if (sampleDelta < MIN_SAMPLE_INTERVAL_MS) return noOnset()
            if (sampleDelta > MAX_SAMPLE_INTERVAL_MS) clearHistory()
        }
        lastSamplePositionMs = positionMs

        val lowEnergy = lowBandEnergy(normalizedBands)
        val onset = (lowEnergy - previousLowEnergy - ONSET_DEADBAND).coerceAtLeast(0f)
        previousLowEnergy = lowEnergy
        val onsetStrength = if (onset >= MIN_ONSET) {
            (onset / (1f - ONSET_DEADBAND)).coerceIn(0f, 1f)
        } else 0f
        val eventId = if (onsetStrength > 0f) ++onsetSequence else null

        history[nextIndex] = onset
        nextIndex = (nextIndex + 1) % HISTORY_SIZE
        historyCount = (historyCount + 1).coerceAtMost(HISTORY_SIZE)
        sampleCountSinceEstimate++

        if (historyCount < MINIMUM_HISTORY_SIZE || sampleCountSinceEstimate < ESTIMATE_INTERVAL_SAMPLES) {
            return AudioTempoUpdate(estimatedBpm, eventId, onsetStrength)
        }
        sampleCountSinceEstimate = 0

        val (candidateBpm, confidence) = correlate() ?: return expireLowConfidence(eventId, onsetStrength)
        if (confidence < MIN_CONFIDENCE) return expireLowConfidence(eventId, onsetStrength)
        lowConfidenceEstimates = 0
        estimatedBpm = estimatedBpm?.let { current ->
            val bounded = candidateBpm.coerceIn(current * (1f - MAX_TEMPO_STEP), current * (1f + MAX_TEMPO_STEP))
            current * (1f - TEMPO_SMOOTHING) + bounded * TEMPO_SMOOTHING
        } ?: candidateBpm
        return AudioTempoUpdate(estimatedBpm, eventId, onsetStrength)
    }

    @Synchronized
    fun reset() {
        history.fill(0f)
        ordered.fill(0f)
        historyCount = 0
        nextIndex = 0
        lastObservedPositionMs = -1L
        lastSamplePositionMs = -1L
        previousLowEnergy = 0f
        sampleCountSinceEstimate = 0
        lowConfidenceEstimates = 0
        estimatedBpm = null
    }

    private fun expireLowConfidence(eventId: Long?, onsetStrength: Float): AudioTempoUpdate {
        lowConfidenceEstimates++
        if (lowConfidenceEstimates >= MAX_LOW_CONFIDENCE_ESTIMATES) estimatedBpm = null
        return AudioTempoUpdate(estimatedBpm, eventId, onsetStrength)
    }

    private fun noOnset() = AudioTempoUpdate(estimatedBpm, null, 0f)

    private fun correlate(): Pair<Float, Float>? {
        val start = (nextIndex - historyCount + HISTORY_SIZE) % HISTORY_SIZE
        var mean = 0.0
        var maxValue = 0f
        for (i in 0 until historyCount) {
            val value = history[(start + i) % HISTORY_SIZE]
            ordered[i] = value
            mean += value
            maxValue = maxOf(maxValue, value)
        }
        if (maxValue < MIN_ONSET) return null
        mean /= historyCount

        var bestBpm = 0f
        var bestCorrelation = -1f
        val minimumLag = (SAMPLES_PER_SECOND * 60f / maximumBpm).toInt().coerceAtLeast(1)
        val maximumLag = (SAMPLES_PER_SECOND * 60f / minimumBpm).toInt().coerceAtMost(historyCount / 2)
        for (lag in minimumLag..maximumLag) {
            val bpm = SAMPLES_PER_SECOND * 60f / lag
            if (bpm !in minimumBpm..maximumBpm) continue
            var cross = 0.0
            var leftEnergy = 0.0
            var rightEnergy = 0.0
            for (i in lag until historyCount) {
                val left = ordered[i] - mean.toFloat()
                val right = ordered[i - lag] - mean.toFloat()
                cross += left * right
                leftEnergy += left * left
                rightEnergy += right * right
            }
            if (leftEnergy <= 1e-8 || rightEnergy <= 1e-8) continue
            val correlation = (cross / sqrt(leftEnergy * rightEnergy)).toFloat()
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation
                bestBpm = bpm
            }
        }
        return if (bestBpm > 0f) bestBpm to bestCorrelation else null
    }

    private fun lowBandEnergy(bands: FloatArray): Float {
        val count = minOf(LOW_BAND_COUNT, bands.size)
        if (count == 0) return 0f
        var sum = 0f
        for (i in 0 until count) {
            val band = bands[i]
            sum += if (band.isFinite()) band.coerceIn(0f, 1f) else 0f
        }
        return sum / count
    }

    private fun clearHistory() {
        history.fill(0f)
        ordered.fill(0f)
        historyCount = 0
        nextIndex = 0
        lastSamplePositionMs = -1L
        previousLowEnergy = 0f
        sampleCountSinceEstimate = 0
        lowConfidenceEstimates = 0
        estimatedBpm = null
    }

    private companion object {
        const val HISTORY_SECONDS = 8
        const val SAMPLES_PER_SECOND = 30f
        const val HISTORY_SIZE = HISTORY_SECONDS * SAMPLES_PER_SECOND.toInt()
        const val MINIMUM_HISTORY_SIZE = 150 // five seconds at the analyzer's nominal 30 Hz
        const val ESTIMATE_INTERVAL_SAMPLES = 15 // update twice per second
        const val LOW_BAND_COUNT = 4
        const val ONSET_DEADBAND = 0.025f
        const val MIN_ONSET = 0.06f
        const val MIN_CONFIDENCE = 0.16f
        const val MAX_POSITION_GAP_MS = 250L
        const val MIN_SAMPLE_INTERVAL_MS = 20L
        const val MAX_SAMPLE_INTERVAL_MS = 100L
        const val MAX_LOW_CONFIDENCE_ESTIMATES = 6
        const val MAX_TEMPO_STEP = 0.08f
        const val TEMPO_SMOOTHING = 0.25f
    }
}
