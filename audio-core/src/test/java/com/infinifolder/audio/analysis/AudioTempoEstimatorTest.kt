package com.infinifolder.audio.analysis

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTempoEstimatorTest {
    @Test fun detectsStableTwoBeatPerSecondBassPulsesAndEmitsUniqueOnsets() {
        val estimator = AudioTempoEstimator()
        val bands = FloatArray(16)
        var estimate: Float? = null
        val eventIds = mutableListOf<Long>()
        for (positionMs in 0L..12_000L step 33L) {
            bands.fill(0.04f)
            if (positionMs % 500L < 90L) {
                for (band in 0 until 4) bands[band] = 0.9f
            }
            val update = estimator.update(positionMs, bands)
            estimate = update.bpm
            update.onsetSequence?.let(eventIds::add)
        }
        assertNotNull("a stable 120 BPM pulse should produce an estimate", estimate)
        assertTrue("estimate=$estimate", estimate!! in 115f..125f)
        assertTrue("expected repeated transient events, got ${eventIds.size}", eventIds.size >= 15)
        assertTrue("event ids should increase once per transient", eventIds.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun silenceHasNoTempoOrEventsAndBackwardSeekClearsPreviousEstimate() {
        val estimator = AudioTempoEstimator()
        val silence = FloatArray(16)
        for (positionMs in 0L..8_000L step 33L) {
            val update = estimator.update(positionMs, silence)
            assertNull(update.bpm)
            assertNull(update.onsetSequence)
        }
        assertNull(estimator.update(8_033L, silence).bpm)
        assertNull(estimator.update(1_000L, silence).bpm)
    }
}
