package com.infinifolder.audio

import com.infinifolder.audio.chiptune.ChiptunePlayer
import com.infinifolder.audio.decoder.*
import com.infinifolder.audio.stream.MemoryAudioInput
import com.infinifolder.audio.synth.SoundFontReader
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NoSubstituteAudioTest {
    @get:Rule val temporary = TemporaryFolder()

    private val unsupported = mapOf(
        "rm" to ".RMF", "rmvb" to ".RMF", "ra" to ".RMF", "tta" to "TTA1", "wv" to "wvpk",
        "ac3" to "\u000bw", "eac3" to "\u000bw", "mlp" to "MLP", "thd" to "TrueHD",
        "amr" to "#!AMR\n", "awb" to "#!AMR-WB\n", "gsm" to "GSM", "g722" to "G722",
        "dro" to "DBRAWOPL", "imf" to "IMF", "vgm" to "Vgm ", "vgz" to "VGZ",
        "ym" to "YM5!LeOnArD!", "nsf" to "NESM\u001a", "spc" to "SNES-SPC700 Soun", "sid" to "PSID"
    )
    private fun header(text: String) = text.toByteArray(Charsets.ISO_8859_1).copyOf(128)

    @Test fun unsupportedCodecsNeverProducePcmThroughEitherPortableEntryPoint() {
        var decoded = 0
        val observer = object : AudioDecodeObserver {
            override fun decoded(result: AudioDecodeResult) { decoded++ }
        }
        for ((ext, signature) in unsupported) {
            val request = AudioDecodeRequest(header(signature), ext)
            assertFalse("No portable codec for .$ext", PortableAudioProcessing.isSupported(request.probe))
            assertNull(ext, PortableAudioProcessing.decodeLegacy(request, observer))
            assertNull(ext, PortableAudioProcessing.decode(request, observer))
        }
        assertEquals(0, decoded)
    }

    @Test fun realMediaHeaderCannotTriggerTheOldChordEvenWithoutAnExtension() {
        for (value in listOf(0, 19, 127)) {
            val bytes = header(".RMF").apply { fill(value.toByte(), 16, size) }
            for (ext in listOf(null, "rm", "rmvb", "ra")) {
                assertNull(PortableAudioProcessing.decodeLegacy(AudioDecodeRequest(bytes, ext)))
                assertFalse(AudiophileStreamDecoder.canDecode(bytes, ext))
                assertNull(AudiophileStreamDecoder.decode(bytes, ext))
            }
        }
    }

    @Test fun compatibilityStubsDoNotInventPcm() {
        val asf = byteArrayOf(0x30, 0x26, 0xb2.toByte(), 0x75, 0x8e.toByte(), 0x66, 0xcf.toByte(), 0x11,
            0xa6.toByte(), 0xd9.toByte(), 0, 0xaa.toByte(), 0, 0x62, 0xce.toByte(), 0x6c).copyOf(128)
        assertFalse(WmaDecoder.canDecode(asf, "wma"))
        assertNull(WmaDecoder.decode(asf))
        assertNull(PortableAudioProcessing.decodeLegacy(AudioDecodeRequest(asf, "wma")))
        assertFalse(ChiptunePlayer.canDecode(header("Vgm ")))
        assertNull(ChiptunePlayer.renderToPcm(header("Vgm ")))
        for (ext in listOf("amr", "awb", "gsm", "g722")) {
            assertFalse(TelephonyAudioDecoder.canDecode(header("#!AMR\n"), ext))
            assertNull(TelephonyAudioDecoder.decode(header("#!AMR\n"), ext))
        }
        for (length in 0..3) assertNull(AudiophileStreamDecoder.decode(ByteArray(length), "ra"))
    }

    @Test fun failedPreparationDoesNotPublishAWaveFile() {
        val input = MemoryAudioInput(header(".RMF"), "unsupported.rm")
        val output = File(temporary.root, "output.wav")
        val processor = AudioFileProcessor()
        assertThrows(IOException::class.java) { processor.openWaveSource(input, output) }
        assertFalse(output.exists())
        assertThrows(IOException::class.java) { processor.prepare(input, output) }
        assertFalse(output.exists())
    }

    @Test fun missingOrInvalidSoundbankSamplesFailWithoutSyntheticReplacement() {
        assertThrows(IOException::class.java) { SoundFontReader.parseSfz("<region> sample=missing.wav", temporary.root) }
        File(temporary.root, "invalid.wav").writeBytes(header("not a waveform"))
        assertThrows(IOException::class.java) { SoundFontReader.parseSfz("<region> sample=invalid.wav", temporary.root) }
        assertThrows(IOException::class.java) { SoundFontReader.parseSfz("<region> sample=missing.wav") }
        assertThrows(IOException::class.java) { SoundFontReader.parseSfz("<group>", temporary.root) }
        val patch = header("GF1PATCH110")
        assertTrue(SoundFontReader.isGusPatch(patch))
        assertNull(SoundFontReader.loadGusPatch(patch))
        assertNull(PortableAudioProcessing.decodeLegacy(AudioDecodeRequest(patch, "pat")))
        assertNull(PortableAudioProcessing.decodeLegacy(AudioDecodeRequest(
            "<region> sample=missing.wav".toByteArray(), "sfz", File(temporary.root, "instrument.sfz"))))
    }

    @Test fun sfzUsesTheReferencedWaveformAndItsActualSampleRate() {
        val samples = shortArrayOf(0, 12000, -8000, 3000, -1000, 100, -20, 0)
        val wave = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples.size * 2)
        samples.forEach { wave.putShort(it) }
        File(temporary.root, "sample.wav").writeBytes(wave.array())
        val bank = SoundFontReader.parseSfz("<region> sample=sample.wav pitch_keycenter=69", temporary.root)
        val sample = bank.instruments.single().samples.single()
        assertEquals(8000, sample.sampleRate)
        assertEquals(69, sample.originalPitch)
        assertArrayEquals(samples, sample.pcmSamples)
    }
}
