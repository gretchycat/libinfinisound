package com.infinifolder.audio

import com.infinifolder.audio.decoder.*
import com.infinifolder.audio.model.AudioPcmData
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Small, independent fixtures: declared codecs must govern interpretation. */
class DecoderRejectionTest {
    private fun text(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun number(value: Long, width: Int, little: Boolean = false): ByteArray =
        ByteArray(width) { index -> (value ushr (8 * if (little) index else width - 1 - index)).toByte() }
    private fun be(value: Int, width: Int = 4) = number(value.toLong(), width)
    private fun le(value: Int, width: Int = 4) = number(value.toLong(), width, true)
    private val pcm = byteArrayOf(0x12, 0x34, 0xed.toByte(), 0xcc.toByte())
    private fun chunk(name: String, body: ByteArray) = text(name) + be(body.size) + body + ByteArray(body.size and 1)
    private fun form(type: String, body: ByteArray) = text("FORM") + be(body.size + 4) + text(type) + body
    private fun au(codec: Int = 3) = text(".snd") + be(24) + be(4) + be(codec) + be(44100) + be(1) + pcm
    private fun aifc(codec: String = "NONE") = form("AIFC",
        chunk("COMM", be(1, 2) + be(2) + be(16, 2) + byteArrayOf(0x40,0x0e,0xac.toByte(),0x44,0,0,0,0,0,0) + text(codec)) +
        chunk("SSND", be(0) + be(0) + pcm))
    private fun caf(codec: String = "lpcm") = text("caff") + be(1, 2) + be(0, 2) +
        text("desc") + number(32, 8) + ByteBuffer.allocate(8).putDouble(44100.0).array() + text(codec) +
        be(0) + be(2) + be(1) + be(1) + be(16) + text("data") + number(8, 8) + be(0) + pcm
    private fun fmt(codec: Int) = le(codec, 2) + le(1, 2) + le(44100) + le(88200) + le(2, 2) + le(16, 2)
    private fun rf64(codec: Int = 1) = text("RF64") + le(-1) + text("WAVE") + text("ds64") + le(28) +
        number(76, 8, true) + number(4, 8, true) + number(2, 8, true) + le(0) +
        text("fmt ") + le(16) + fmt(codec) + text("data") + le(-1) + pcm
    private fun guid(type: String) = text(type) + byteArrayOf(0x2e, 0x91.toByte()) + ByteArray(10)
    private fun w64(codec: Int = 1) = guid("riff") + number(112, 8, true) + guid("wave") +
        guid("fmt ") + number(40, 8, true) + fmt(codec) + guid("data") + number(28, 8, true) + pcm + ByteArray(4)
    private fun svx(codec: Int = 0) = form("8SVX", chunk("VHDR", be(2) + be(0) + be(0) + be(22050, 2) +
        byteArrayOf(1, codec.toByte()) + be(65536)) + chunk("BODY", byteArrayOf(0x12, 0xed.toByte())))
    private fun maud(codec: Int = 0, divisor: Int = 1) = form("MAUD", chunk("MHDR", be(2) + be(16, 2) + be(16, 2) +
        be(44100) + be(divisor, 2) + be(0, 2) + be(1, 2) + be(codec, 2) + ByteArray(12)) + chunk("MDAT", pcm))
    private fun voc(codec: Int = 4) = text("Creative Voice File\u001a") + le(26, 2) + ByteArray(4) +
        byteArrayOf(9) + le(16, 3) + le(44100) + byteArrayOf(16, 1) + le(codec, 2) + le(0) + pcm + byteArrayOf(0)

    private fun pair(valid: ByteArray, invalid: ByteArray, decode: (ByteArray) -> AudioPcmData?) {
        assertEquals(2, decode(valid)!!.samples.size)
        assertNull("Unknown codec was interpreted as PCM", decode(invalid))
        assertNull("Truncated payload was padded", decode(valid.copyOf(valid.size / 2)))
    }
    @Test fun auRequiresKnownEncoding() = pair(au(), au(0xffff), SunAuDecoder::decode)
    @Test fun aifcRequiresKnownCompression() = pair(aifc(), aifc("zzzz"), AiffDecoder::decode)
    @Test fun cafRequiresKnownFormat() = pair(caf(), caf("zzzz"), CafDecoder::decode)
    @Test fun wave64RequiresKnownFormat() = pair(w64(), w64(0xffff), Wave64AndRf64Decoder::decode)
    @Test fun rf64RequiresKnownFormat() = pair(rf64(), rf64(0xffff), Wave64AndRf64Decoder::decode)
    @Test fun svxRequiresKnownCompression() = pair(svx(), svx(127), IffAudioDecoder::decode)
    @Test fun maudRequiresKnownCompression() = pair(maud(), maud(0xffff), IffAudioDecoder::decode)
    @Test fun vocRequiresMatchingCodecAndWidth() = pair(voc(), voc(0xffff), VocDecoder::decode)
    @Test fun maudUsesClockDivisorAndRejectsZero() {
        assertEquals(22050, IffAudioDecoder.decode(maud(divisor = 2))!!.sampleRate)
        assertNull(IffAudioDecoder.decode(maud(divisor = 0)))
    }
    @Test fun pcmSampleValuesRemainExact() {
        for (decoded in listOf(SunAuDecoder.decode(au()), AiffDecoder.decode(aifc()), CafDecoder.decode(caf()), IffAudioDecoder.decode(maud())))
            assertArrayEquals(shortArrayOf(0x1234, -0x1234), decoded!!.samples)
    }
    @Test fun auRejectsIncompleteFramesAndUnknownWidth() {
        val bytes = au()
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(8, 3)
        assertNull(SunAuDecoder.decode(bytes))
        assertNull(SunAuDecoder.decode(au(7)))
    }
    @Test fun implementedCompandingAndFloatRemainAvailable() {
        for (codec in listOf(1, 2, 3, 4, 5, 6, 27)) {
            val width = when (codec) { 3 -> 2; 4 -> 3; 5, 6 -> 4; else -> 1 }
            val bytes = text(".snd") + be(24) + be(width * 2) + be(codec) + be(44100) + be(1) + ByteArray(width * 2)
            assertEquals("AU $codec", 2, SunAuDecoder.decode(bytes)!!.samples.size)
        }
    }
    private fun alac(bits: Int) {
        val bytes = javaClass.getResourceAsStream("/audio/boundary-$bits.m4a")!!.use { it.readBytes() }
        val decoded = AlacAndMpeg2Decoder.decode(bytes, "alac")!!
        assertEquals(48000, decoded.sampleRate)
        assertEquals(2, decoded.channels)
        assertEquals(5003 * 2, decoded.samples.size)
        for (frame in 0 until 5003) for (channel in 0..1) {
            val sample = ((frame * 7919L + channel * 17011L) % (1L shl bits)) - (1L shl (bits - 1))
            assertEquals("ALAC $bits frame=$frame channel=$channel", (sample shr (bits - 16)).toShort(), decoded.samples[frame * 2 + channel])
        }
        assertNull(AlacAndMpeg2Decoder.decode(bytes.copyOf(bytes.size / 2), "alac"))
    }
    @Test fun alac16RemainsSampleAccurateAcrossFrames() = alac(16)
    @Test fun alac24DownconvertsActualSamplesAcrossFrames() = alac(24)

}
