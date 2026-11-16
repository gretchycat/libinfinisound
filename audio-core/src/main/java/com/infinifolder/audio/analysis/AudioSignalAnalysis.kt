package com.infinifolder.audio.analysis

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** Measurements from decoded playback PCM and Android's packed real FFT format. */
object AudioSignalAnalysis {
    private const val FFT_SIZE = 2048
    private const val FLOOR_DB = -72.0
    private const val AMPLITUDE_FLOOR = 1e-8

    data class Level(val rms: Float, val peak: Float)

    fun waveform(samples: ByteArray, output: FloatArray): Level {
        if (samples.isEmpty()) { output.fill(0f); return Level(0f, 0f) }
        var squares = 0.0
        var peak = 0f
        for (sample in samples) {
            val value = ((sample.toInt() and 0xff) - 128) / 128f
            squares += value * value
            peak = maxOf(peak, kotlin.math.abs(value))
        }
        for (i in output.indices) {
            val index = (i.toLong() * samples.size / output.size).toInt()
            output[i] = ((samples[index].toInt() and 0xff) - 128) / 128f
        }
        return Level(sqrt(squares / samples.size).toFloat(), peak)
    }

    data class StereoLevel(
        val left: Level,
        val right: Level,
        val stereo: Boolean,
        /** Side energy divided by mid plus side energy; 0 is mono, 1 is wide. */
        val stereoWidth: Float = 0f,
        /** -1 is left-heavy, +1 is right-heavy. */
        val stereoBalance: Float = 0f,
        val zeroCrossingRate: Float = 0f,
        val spectralCentroidHz: Float = 0f,
    )

    /** Analyze decoded PCM without using an audio capture device. */
    fun pcm(samples: FloatArray, channels: Int, sampleRateHz: Int, waveform: FloatArray, bands: FloatArray): StereoLevel {
        val frames = if (channels > 0) samples.size / channels else 0
        if (frames == 0 || sampleRateHz <= 0) {
            waveform.fill(0f); bands.fill(0f)
            return StereoLevel(Level(0f, 0f), Level(0f, 0f), false)
        }

        var sumLeft = 0.0; var sumRight = 0.0
        var peakLeft = 0f; var peakRight = 0f
        var sumMid = 0.0; var sumSide = 0.0
        var crossingsLeft = 0
        var crossingsRight = 0
        var previousLeft = 0f
        var previousRight = 0f
        for (frame in 0 until frames) {
            val base = frame * channels
            val l = finiteSample(samples[base])
            val r = finiteSample(samples[base + if (channels > 1) 1 else 0])
            sumLeft += l * l; sumRight += r * r
            peakLeft = maxOf(peakLeft, kotlin.math.abs(l)); peakRight = maxOf(peakRight, kotlin.math.abs(r))
            val mid = (l + r) * 0.5f
            val side = (l - r) * 0.5f
            sumMid += mid * mid; sumSide += side * side
            if (frame > 0) {
                if ((l >= 0f) != (previousLeft >= 0f)) crossingsLeft++
                if ((r >= 0f) != (previousRight >= 0f)) crossingsRight++
            }
            previousLeft = l; previousRight = r
        }
        for (i in waveform.indices) {
            val frame = (i.toLong() * frames / waveform.size).toInt()
            val base = frame * channels
            val l = finiteSample(samples[base])
            val r = finiteSample(samples[base + if (channels > 1) 1 else 0])
            waveform[i] = (l + r) * 0.5f
        }

        // Analyze the most recent 2048 frames. Two independent spectra retain
        // out-of-phase stereo content instead of cancelling it in a mono fold.
        val count = minOf(FFT_SIZE, frames)
        val firstFrame = frames - count
        val leftRe = DoubleArray(FFT_SIZE)
        val leftIm = DoubleArray(FFT_SIZE)
        val rightRe = DoubleArray(FFT_SIZE)
        val rightIm = DoubleArray(FFT_SIZE)
        var windowSum = 0.0
        for (i in 0 until count) {
            val window = if (count <= 1) 1.0 else 0.5 - 0.5 * kotlin.math.cos(2.0 * Math.PI * i / (count - 1))
            windowSum += window
            val base = (firstFrame + i) * channels
            leftRe[i] = finiteSample(samples[base]).toDouble() * window
            rightRe[i] = finiteSample(samples[base + if (channels > 1) 1 else 0]).toDouble() * window
        }
        fft(leftRe, leftIm)
        if (channels > 1) fft(rightRe, rightIm)

        bands.fill(0f)
        val lowHz = 20.0
        val highHz = minOf(20000.0, sampleRateHz / 2.0)
        var spectralPower = 0.0
        var spectralMoment = 0.0
        if (highHz > lowHz && bands.isNotEmpty() && windowSum > 0.0) {
            val ratio = highHz / lowHz
            for (band in bands.indices) {
                val lowerHz = lowHz * ratio.pow(band.toDouble() / bands.size)
                val upperHz = lowHz * ratio.pow((band + 1.0) / bands.size)
                val firstBin = ceil(lowerHz * FFT_SIZE / sampleRateHz).toInt().coerceIn(1, FFT_SIZE / 2)
                val endBin = ceil(upperHz * FFT_SIZE / sampleRateHz).toInt().coerceIn(firstBin, FFT_SIZE / 2)
                var energy = 0.0
                var binCount = 0
                for (bin in firstBin until endBin) {
                    val leftAmplitude = hypot(leftRe[bin], leftIm[bin]) * 2.0 / windowSum
                    val rightAmplitude = if (channels > 1) hypot(rightRe[bin], rightIm[bin]) * 2.0 / windowSum else leftAmplitude
                    val power = (leftAmplitude * leftAmplitude + rightAmplitude * rightAmplitude) * 0.5
                    energy += power
                    val frequency = bin.toDouble() * sampleRateHz / FFT_SIZE
                    spectralPower += power
                    spectralMoment += frequency * power
                    binCount++
                }
                if (binCount > 0) bands[band] = amplitudeToLevel(sqrt(energy / binCount))
            }
        }

        val leftRms = sqrt(sumLeft / frames).toFloat()
        val rightRms = sqrt(sumRight / frames).toFloat()
        val midRms = sqrt(sumMid / frames)
        val sideRms = sqrt(sumSide / frames)
        val width = if (midRms + sideRms <= 1e-12) 0f else (sideRms / (midRms + sideRms)).toFloat().coerceIn(0f, 1f)
        val balance = if (leftRms + rightRms <= 1e-12f) 0f else ((rightRms - leftRms) / (rightRms + leftRms)).coerceIn(-1f, 1f)
        return StereoLevel(
            Level(leftRms, peakLeft),
            Level(rightRms, peakRight),
            channels > 1,
            width,
            balance,
            if (frames <= 1) 0f else (crossingsLeft + crossingsRight).toFloat() / (2 * (frames - 1)),
            if (spectralPower <= 1e-20) 0f else (spectralMoment / spectralPower).toFloat(),
        )
    }

    /** Android's FFT is packed as DC, Nyquist, then complex bins 1..N/2-1. */
    fun spectrum(fft: ByteArray, sampleRateHz: Float, output: FloatArray) {
        output.fill(0f)
        val n = fft.size
        if (n < 4 || output.isEmpty() || sampleRateHz <= 0f) return
        val maxBin = n / 2 - 1
        val lowHz = maxOf(20.0, sampleRateHz.toDouble() / n)
        val highHz = minOf(20000.0, sampleRateHz / 2.0)
        if (highHz <= lowHz) return
        val ratio = highHz / lowHz
        for (band in output.indices) {
            val lowerHz = lowHz * ratio.pow(band.toDouble() / output.size)
            val upperHz = lowHz * ratio.pow((band + 1.0) / output.size)
            val first = ceil(lowerHz * n / sampleRateHz).toInt().coerceIn(1, maxBin)
            val end = ceil(upperHz * n / sampleRateHz).toInt().coerceIn(first, maxBin + 1)
            var power = 0.0
            var count = 0
            for (bin in first until end) {
                val magnitude = hypot(fft[bin * 2].toDouble(), fft[bin * 2 + 1].toDouble()) / 128.0
                power += magnitude * magnitude
                count++
            }
            if (count > 0) output[band] = amplitudeToLevel(sqrt(power / count))
        }
    }

    /** A consistent -72 dBFS to 0 dBFS display scale for every frequency band. */
    private fun amplitudeToLevel(amplitude: Double): Float {
        if (!amplitude.isFinite() || amplitude <= AMPLITUDE_FLOOR) return 0f
        val db = 20.0 * log10(amplitude)
        return ((db - FLOOR_DB) / -FLOOR_DB).toFloat().coerceIn(0f, 1f)
    }

    private fun finiteSample(value: Float): Float = if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val size = real.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val realValue = real[i]; real[i] = real[j]; real[j] = realValue
                val imaginaryValue = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = imaginaryValue
            }
        }
        var length = 2
        while (length <= size) {
            val angle = -2.0 * Math.PI / length
            val stepReal = kotlin.math.cos(angle); val stepImaginary = kotlin.math.sin(angle)
            for (start in 0 until size step length) {
                var twiddleReal = 1.0; var twiddleImaginary = 0.0
                for (k in 0 until length / 2) {
                    val a = start + k; val b = a + length / 2
                    val tr = twiddleReal * real[b] - twiddleImaginary * imaginary[b]
                    val ti = twiddleReal * imaginary[b] + twiddleImaginary * real[b]
                    real[b] = real[a] - tr; imaginary[b] = imaginary[a] - ti
                    real[a] += tr; imaginary[a] += ti
                    val next = twiddleReal * stepReal - twiddleImaginary * stepImaginary
                    twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
                    twiddleReal = next
                }
            }
            length *= 2
        }
    }
}
