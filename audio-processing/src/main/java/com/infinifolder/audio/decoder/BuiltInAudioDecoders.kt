package com.infinifolder.audio.decoder

import com.infinifolder.audio.chiptune.ChiptunePlayer
import com.infinifolder.audio.AudioDecoder
import com.infinifolder.audio.AudioDecodeRequest
import com.infinifolder.audio.AudioProbe
import com.infinifolder.audio.model.AudioPcmData
import com.infinifolder.audio.synth.SoundFontReader
import com.infinifolder.audio.synth.WavetableSynthesizer
import com.infinifolder.audio.tracker.MidiSequencer
import com.infinifolder.audio.tracker.TrackerEngine
import java.io.File

internal object BuiltInAudioDecoders {

    fun isSupported(bytes: ByteArray, extension: String? = null): Boolean {
        val ext = extension?.lowercase() ?: ""
        if (ext in SUPPORTED_EXTENSIONS) return true

        return SunAuDecoder.canDecode(bytes) ||
                AiffDecoder.canDecode(bytes) ||
                VocDecoder.canDecode(bytes) ||
                IffAudioDecoder.canDecode(bytes) ||
                Wave64AndRf64Decoder.canDecode(bytes) ||
                CafDecoder.canDecode(bytes) ||
                SpecializedSampleDecoder.canDecode(bytes) ||
                TelephonyAudioDecoder.canDecode(bytes, ext) ||
                MidiSequencer.isMidi(bytes) ||
                SoundFontReader.isSoundFont(bytes) ||
                SoundFontReader.isGusPatch(bytes) ||
                TrackerEngine.canDecode(bytes) ||
                ChiptunePlayer.canDecode(bytes) ||
                AudiophileStreamDecoder.canDecode(bytes, ext) ||
                WmaDecoder.canDecode(bytes, ext) ||
                AlacAndMpeg2Decoder.canDecode(bytes, ext) ||
                CddaDecoder.canDecode(bytes, ext)
    }

    // Registration order is the original dispatcher order. Third-party types remain inside adapters.
    val adapters: List<AudioDecoder> = listOf(
        DecoderAdapter(
            name = "WavDecoder",
            accepts = { bytes, ext -> ext == "wav" || (bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") },
            render = { bytes, ext, file ->
                decodeStandardWav(bytes)
            }
        ),
        DecoderAdapter(
            name = "SunAuDecoder",
            accepts = { bytes, ext -> ext == "au" || ext == "snd" || SunAuDecoder.canDecode(bytes) },
            render = { bytes, ext, file ->
                SunAuDecoder.decode(bytes)
            }
        ),
        DecoderAdapter(
            name = "AiffDecoder",
            accepts = { bytes, ext -> ext in setOf("aiff", "aif", "aifc") || AiffDecoder.canDecode(bytes) },
            render = { bytes, ext, file ->
                AiffDecoder.decode(bytes)
            }
        ),
        DecoderAdapter(
            name = "VocDecoder",
            accepts = { bytes, ext -> ext == "voc" || VocDecoder.canDecode(bytes) },
            render = { bytes, ext, file ->
                VocDecoder.decode(bytes)
            }
        ),
        DecoderAdapter(
            name = "IffAudioDecoder",
            accepts = { bytes, ext -> ext in setOf("8svx", "svx", "16sv", "maud") || IffAudioDecoder.canDecode(bytes) },
            render = { bytes, ext, file ->
                IffAudioDecoder.decode(bytes)
            }
        ),
        DecoderAdapter(
            name = "Wave64AndRf64Decoder",
            accepts = { bytes, ext -> ext in setOf("w64", "rf64") || Wave64AndRf64Decoder.canDecode(bytes) },
            render = { bytes, ext, file ->
                Wave64AndRf64Decoder.decode(bytes)
            }
        ),
        DecoderAdapter(
            name = "CafDecoder",
            accepts = { bytes, ext -> ext == "caf" || CafDecoder.canDecode(bytes) },
            render = { bytes, ext, file ->
                CafDecoder.decode(bytes)
            }
        ),
        DecoderAdapter(
            name = "SpecializedSampleDecoder",
            accepts = { bytes, ext -> ext in setOf("avr", "ircam", "sph", "pvf", "wve", "htk") || SpecializedSampleDecoder.canDecode(bytes) },
            render = { bytes, ext, file ->
                SpecializedSampleDecoder.decode(bytes)
            }
        ),
        DecoderAdapter(
            name = "TelephonyAudioDecoder",
            accepts = { bytes, ext -> ext in setOf("adx", "amr", "awb", "g722", "gsm") || TelephonyAudioDecoder.canDecode(bytes, ext) },
            render = { bytes, ext, file ->
                TelephonyAudioDecoder.decode(bytes, ext)
            }
        ),
        DecoderAdapter(
            name = "MidiSequencer",
            accepts = { bytes, ext -> ext in setOf("mid", "midi", "kar", "rmi") || MidiSequencer.isMidi(bytes) },
            render = { bytes, ext, file ->
                MidiSequencer.renderToPcm(bytes)
            }
        ),
        DecoderAdapter(
            name = "SoundFontReader.SF2",
            accepts = { bytes, ext -> ext == "sf2" || SoundFontReader.isSoundFont(bytes) },
            render = { bytes, ext, file ->
                SoundFontReader.loadSf2(bytes)?.let { bank ->
                    val synth = WavetableSynthesizer(44100)
                    synth.soundFont = bank
                    synth.noteOn(0, 60, 100)
                    synth.noteOn(0, 64, 100)
                    synth.noteOn(0, 67, 100)
                    synth.renderToPcm(2500L)
                }
            }
        ),
        DecoderAdapter(
            name = "SoundFontReader.SFZ",
            accepts = { bytes, ext -> ext == "sfz" },
            render = { bytes, ext, file ->
                val text = String(bytes, Charsets.UTF_8)
                val bank = SoundFontReader.parseSfz(text, file?.parentFile)
                val synth = WavetableSynthesizer(44100)
                synth.soundFont = bank
                synth.noteOn(0, 60, 100)
                synth.noteOn(0, 64, 100)
                synth.noteOn(0, 67, 100)
                synth.renderToPcm(2500L)
            }
        ),
        DecoderAdapter(
            name = "SoundFontReader.PAT",
            accepts = { bytes, ext -> ext == "pat" || SoundFontReader.isGusPatch(bytes) },
            render = { bytes, ext, file ->
                SoundFontReader.loadGusPatch(bytes)?.let { bank ->
                    val synth = WavetableSynthesizer(44100)
                    synth.soundFont = bank
                    synth.noteOn(0, 60, 100)
                    synth.renderToPcm(2500L)
                }
            }
        ),
        DecoderAdapter(
            name = "TrackerEngine",
            accepts = { bytes, ext -> ext in setOf("mod", "s3m", "xm", "it") || TrackerEngine.canDecode(bytes) },
            render = { bytes, ext, file ->
                TrackerEngine.renderToPcm(bytes)
            }
        ),
        DecoderAdapter(
            name = "ChiptunePlayer",
            accepts = { bytes, ext -> ext in setOf("dro", "imf", "vgm", "vgz", "ym", "nsf", "spc", "sid") || ChiptunePlayer.canDecode(bytes) },
            render = { bytes, ext, file ->
                ChiptunePlayer.renderToPcm(bytes)
            }
        ),
        DecoderAdapter(
            name = "AudiophileStreamDecoder",
            accepts = { bytes, ext -> ext in setOf("dsf", "dff", "tta", "wv", "ra", "ac3", "eac3", "mlp", "thd") || AudiophileStreamDecoder.canDecode(bytes, ext) },
            render = { bytes, ext, file ->
                AudiophileStreamDecoder.decode(bytes, ext)
            },
            describe = { ext -> when (ext) {
                "dff" -> "DSD64 (2.8 MHz 1-bit) → 44.1 kHz • Stereo • DSDIFF (SACD)"
                "dsf" -> "DSD64 (2.8 MHz 1-bit) → 44.1 kHz • Stereo • Sony DSF"
                else -> null
            } }
        ),
        DecoderAdapter(
            name = "WmaDecoder",
            accepts = { bytes, ext -> ext == "wma" || WmaDecoder.canDecode(bytes, ext) },
            render = { bytes, ext, file ->
                WmaDecoder.decode(bytes)
            }
        ),
        DecoderAdapter(
            name = "AlacAndMpeg2Decoder",
            accepts = { bytes, ext -> ext in setOf("mp2", "alac") || ext.endsWith("alac.m4a") || AlacAndMpeg2Decoder.canDecode(bytes, ext) },
            render = { bytes, ext, file ->
                AlacAndMpeg2Decoder.decode(bytes, ext, file)
            }
        ),
        DecoderAdapter(
            name = "CddaDecoder",
            accepts = { bytes, ext -> ext == "cdda" || CddaDecoder.canDecode(bytes, ext) },
            render = { bytes, ext, file ->
                CddaDecoder.decode(bytes, ext)
            },
            describe = { "CD-DA Red Book (44.1 kHz 16-bit) • Stereo • Raw PCM" }
        )
    )

    private class DecoderAdapter(
        override val name: String,
        private val accepts: (ByteArray, String) -> Boolean,
        private val render: (ByteArray, String, File?) -> AudioPcmData?,
        private val describe: (String) -> String? = { null }
    ) : AudioDecoder {
        override fun matches(probe: AudioProbe): Boolean = accepts(probe.bytes, probe.normalizedExtension)
        override fun decode(request: AudioDecodeRequest): AudioPcmData? =
            render(request.bytes, request.probe.normalizedExtension, request.file)
        override fun sourceFormat(request: AudioDecodeRequest): String? = describe(request.probe.normalizedExtension)
    }

    private fun decodeStandardWav(bytes: ByteArray): AudioPcmData? {
        var pos = 12
        var channels = 2
        var sampleRate = 44100
        var bits = 16
        var formatTag = 1
        var dataOffset = -1
        var dataLen = 0

        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = (bytes[pos + 4].toInt() and 0xFF) or
                    ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 7].toInt() and 0xFF) shl 24)
            val chunkDataPos = pos + 8
            pos = chunkDataPos + chunkSize.coerceAtLeast(0)

            when (chunkId) {
                "fmt " -> {
                    if (chunkDataPos + 16 <= bytes.size) {
                        val bb = java.nio.ByteBuffer.wrap(bytes, chunkDataPos, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        formatTag = bb.short.toInt() and 0xFFFF
                        channels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        bb.int // byteRate
                        bb.short // blockAlign
                        bits = bb.short.toInt() and 0xFFFF
                    }
                }
                "data" -> {
                    dataOffset = chunkDataPos
                    dataLen = if (chunkSize > 0) chunkSize.coerceAtMost(bytes.size - dataOffset) else (bytes.size - dataOffset)
                    break
                }
            }
        }

        if (dataOffset < 0 || dataLen <= 0) return null

        return when {
            formatTag == 3 -> AudioPcmData.from32BitFloat(bytes, sampleRate, channels, bigEndian = false, offset = dataOffset, length = dataLen)
            bits == 8 -> AudioPcmData.from8BitUnsigned(bytes.copyOfRange(dataOffset, dataOffset + dataLen), sampleRate, channels)
            bits == 16 -> AudioPcmData.from16BitLittleEndian(bytes, sampleRate, channels, dataOffset, dataLen)
            bits == 24 -> AudioPcmData.from24Bit(bytes, sampleRate, channels, bigEndian = false, offset = dataOffset, length = dataLen)
            bits == 32 -> {
                val count = dataLen / 4
                val samples = ShortArray(count)
                val bb = java.nio.ByteBuffer.wrap(bytes, dataOffset, dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) samples[i] = (bb.int shr 16).toShort()
                AudioPcmData(sampleRate, channels, samples, 16)
            }
            else -> AudioPcmData.from16BitLittleEndian(bytes, sampleRate, channels, dataOffset, dataLen)
        }
    }

    val SUPPORTED_EXTENSIONS = setOf(
        "au", "snd", "aiff", "aif", "aifc", "voc", "8svx", "svx", "16sv", "maud",
        "w64", "rf64", "caf", "avr", "ircam", "sph", "pvf", "wve", "htk",
        "adx", "amr", "awb", "g722", "gsm", "wma", "mp2", "alac", "mid", "midi", "kar", "rmi", "sf2", "sfz", "pat",
        "mod", "s3m", "xm", "it", "dro", "imf", "vgm", "vgz", "ym", "nsf", "spc", "sid",
        "dsf", "dff", "tta", "wv", "ra", "ac3", "eac3", "mlp", "thd",
        "m3u", "m3u8", "pls", "xspf", "cue"
    )
}
