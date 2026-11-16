package com.infinifolder.audio.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSignalAnalysisTest {
    @Test fun monoWaveformLevelsUseAllSamples() {
        val out = FloatArray(4)
        val level = AudioSignalAnalysis.waveform(byteArrayOf(128.toByte(), 192.toByte(), 64, 128.toByte()), out)
        assertEquals(0.35355f, level.rms, 0.0001f)
        assertEquals(0.5f, level.peak, 0.0001f)
        assertEquals(0.5f, out[1], 0.0001f)
        assertEquals(-0.5f, out[2], 0.0001f)
    }
    @Test fun spectrumTracksFrequencyAndCaptureRate() {
        val fft = ByteArray(1024)
        fft[20] = 100 // complex bin 10: 468.75 Hz at 48 kHz
        val lowRate = FloatArray(16)
        val highRate = FloatArray(16)
        AudioSignalAnalysis.spectrum(fft, 24000f, lowRate)
        AudioSignalAnalysis.spectrum(fft, 48000f, highRate)
        assertTrue(lowRate.maxOrNull()!! > 0f)
        assertTrue(highRate.maxOrNull()!! > 0f)
        assertTrue(highRate.indices.maxByOrNull { highRate[it] }!! > lowRate.indices.maxByOrNull { lowRate[it] }!!)
    }
    @Test fun silenceProducesNoSpectrumOrLevel() {
        val bands = FloatArray(16) { 1f }
        AudioSignalAnalysis.spectrum(ByteArray(1024), 44100f, bands)
        assertTrue(bands.all { it == 0f })
        val level = AudioSignalAnalysis.waveform(ByteArray(128) { 128.toByte() }, FloatArray(8))
        assertEquals(0f, level.rms, 0f)
        assertEquals(0f, level.peak, 0f)
    }
    @Test fun decodedPcmReportsTrueStereoAndFrequency() {
        val rate = 48000
        val frames = 1024
        val samples = FloatArray(frames * 2)
        for (i in 0 until frames) {
            samples[i * 2] = (0.8 * kotlin.math.sin(2 * Math.PI * 16 * i / frames)).toFloat()
            samples[i * 2 + 1] = 0f
        }
        val bands = FloatArray(16)
        val levels = AudioSignalAnalysis.pcm(samples, 2, rate, FloatArray(128), bands)
        assertTrue(levels.stereo)
        assertEquals(0.5657f, levels.left.rms, 0.002f)
        assertEquals(0f, levels.right.rms, 0f)
        assertTrue(bands.maxOrNull()!! > 0.3f)
        assertTrue(bands.count { it > 0.01f } < bands.size)
    }
    @Test fun decodedMonoAndSilenceAreHonest() {
        val waveform = FloatArray(16) { 1f }
        val bands = FloatArray(16) { 1f }
        val levels = AudioSignalAnalysis.pcm(FloatArray(1024), 1, 44100, waveform, bands)
        assertTrue(!levels.stereo)
        assertEquals(0f, levels.left.rms, 0f)
        assertEquals(0f, levels.right.rms, 0f)
        assertTrue(waveform.all { it == 0f } && bands.all { it == 0f })
    }

    @Test fun pcmSpectrumPlacesSinesInIncreasingLogFrequencyBands() {
        val rate = 48000
        val frames = 2048
        val frequencies = listOf(50.0, 100.0, 440.0, 1000.0, 5000.0, 12000.0)
        val strongestBands = frequencies.map { frequency ->
            val pcm = FloatArray(frames) { index -> (0.45 * kotlin.math.sin(2 * Math.PI * frequency * index / rate)).toFloat() }
            val bands = FloatArray(16)
            AudioSignalAnalysis.pcm(pcm, 1, rate, FloatArray(32), bands)
            assertTrue("$frequency Hz should be visible", bands.maxOrNull()!! > 0.35f)
            bands.indices.maxByOrNull { bands[it] }!!
        }
        assertTrue("frequency bands should increase with pitch: $strongestBands", strongestBands.zipWithNext().all { it.first < it.second })
    }

    @Test fun pcmBandLevelsUseAConsistentDecibelRange() {
        fun level(amplitude: Double): Float {
            val pcm = FloatArray(2048) { index -> (amplitude * kotlin.math.sin(2 * Math.PI * 1000.0 * index / 48000)).toFloat() }
            val bands = FloatArray(16)
            AudioSignalAnalysis.pcm(pcm, 1, 48000, FloatArray(32), bands)
            return bands.maxOrNull()!!
        }
        val quiet = level(0.05)
        val loud = level(0.5)
        assertTrue("a 20 dB amplitude increase should produce a predictable graph rise: quiet=$quiet loud=$loud", loud - quiet in 0.22f..0.34f)
    }

    @Test fun antiPhaseStereoIsRetainedAndReportsWidth() {
        val pcm = FloatArray(2048 * 2)
        for (i in 0 until 2048) {
            val sample = (0.35 * kotlin.math.sin(2 * Math.PI * 440.0 * i / 48000)).toFloat()
            pcm[i * 2] = sample
            pcm[i * 2 + 1] = -sample
        }
        val bands = FloatArray(16)
        val levels = AudioSignalAnalysis.pcm(pcm, 2, 48000, FloatArray(32), bands)
        assertTrue(levels.stereo)
        assertEquals(1f, levels.stereoWidth, 0.001f)
        assertEquals(0f, levels.stereoBalance, 0.001f)
        assertTrue(bands.maxOrNull()!! > 0.4f)
        assertTrue(levels.spectralCentroidHz in 350f..550f)
        assertTrue(levels.zeroCrossingRate in 0.015f..0.025f)
    }
}
