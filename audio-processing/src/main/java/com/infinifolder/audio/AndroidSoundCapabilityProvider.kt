package com.infinifolder.audio

import io.github.gretchycat.infinistream.*

class AndroidSoundCapabilityProvider : CapabilityProvider {
    override val library="sound"
    override fun handlers():List<CapabilityHandler> = listOf(
        AndroidAudioIdentityHandler(),
        MagicAudioIdentityHandler(HandlerDescriptor("sound.android.audio-signatures", "sound", "Android audio codec signatures",
            androidAudioSignatureFormats(), setOf(Operations.IDENTIFY),
            limitations = listOf("Recognizes documented audio frame/container signatures; playback availability is device-dependent"),
            priority = 20, attributes = mapOf("presentation" to "audio"))),
        PlaybackOnlyPcmCapabilityHandler(AndroidPcmDecoder,listOf(FormatSupport(setOf("audio/*"),AndroidSoundFormats.extensions)),"platform",
            runtime={
                val types=android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos.orEmpty()
                    .filter { !it.isEncoder }.flatMap { it.supportedTypes.toList() }.filter { it.startsWith("audio/") }
                if(types.isEmpty())HandlerAvailability(AvailabilityState.UNAVAILABLE,"No Android audio decoders reported")
                else HandlerAvailability(AvailabilityState.CONDITIONAL,"Android demuxer and codec must accept the specific stream",types.associateWith{true})
            },limitations=listOf("Container/codec combinations depend on the device")),
        PcmCapabilityHandler(SmafPcmDecoder,listOf(FormatSupport(setOf("application/vnd.smaf","application/x-smaf"),setOf("mmf","smaf"),
            magic=listOf(MagicSignature("4d4d4d44")))),"preferred",runtime={native("com.infinifolder.audio.SmafNative")},
            limitations=listOf("4 MiB supported SMAF score/FM/sample subset")),
        PcmCapabilityHandler(MidiPcmDecoder,listOf(FormatSupport(setOf("audio/midi","audio/x-midi","application/x-guitar-pro","application/x-gtp"),setOf("mid","midi","kar","rmi","gp3"))),"preferred",
            runtime={if(MidiPcmDecoder.soundFont==null)HandlerAvailability(AvailabilityState.UNAVAILABLE,"No MIDI SoundFont configured")
                else native("com.infinifolder.audio.FluidLiteNative") { FluidLiteNative.available() }},limitations=listOf("Readable configured SoundFont and FluidLite symbols required; GP3 subset")),
        PcmCapabilityHandler(GameMusicPcmDecoder,gameMusicFormats(),"preferred",
            runtime={native("com.infinifolder.audio.GameMusicNative") { GameMusicNative.available() }},limitations=listOf("Optional GME symbols; finite policy for untimed tracks")),
        MagicAudioIdentityHandler(HandlerDescriptor("sound.gamemusic.signatures", "sound", "Game music signatures",
            (gameMusicFormats() + retroMusicIdentityFormats()).filter { it.magic.isNotEmpty() }, setOf(Operations.IDENTIFY),
            limitations = listOf("Recognizes documented emulator music headers; playback requires the optional GME backend"),
            priority = 20, attributes = mapOf("presentation" to "audio"))),
        PlaybackOnlyPcmCapabilityHandler(LibVlcAvDecoder,listOf(FormatSupport(setOf("audio/*"))),"fallback",runtime={native("com.infinifolder.audio.LibVlcAvNative") { LibVlcAvNative.available() }},limitations=listOf("Bundled optional FFmpeg symbols; per-input validation required")),
        PlaybackOnlyPcmCapabilityHandler(LibVlcPcmDecoder,listOf(FormatSupport(setOf("audio/*"))),"fallback",runtime={native("com.infinifolder.audio.LibVlcPcmNative") { LibVlcPcmNative.available() }},limitations=listOf("libVLC codec availability and per-input validation required"))
    )
    private fun native(name:String,symbolProbe:(()->Boolean)?=null):HandlerAvailability {
        Class.forName(name,true,javaClass.classLoader)
        if(symbolProbe?.invoke()==false)return HandlerAvailability(AvailabilityState.UNAVAILABLE,"Required native codec symbols or ABI are unavailable",mapOf(name to true,"codecSymbols" to false))
        return HandlerAvailability(AvailabilityState.CONDITIONAL,"Native bridge/symbol checks passed; per-input and configured-resource validation occurs on open",mapOf(name to true,"codecSymbols" to true))
    }
}

/** Compatibility wrapper for broad playback backends that are not safe identity parsers. */
private class PlaybackOnlyPcmCapabilityHandler(
    decoder: PcmDecoder, formats: List<FormatSupport>, tier: String,
    runtime: () -> HandlerAvailability, limitations: List<String>
) : PcmCapabilityHandler(decoder, formats, tier, runtime, limitations) {
    override val descriptor = super.descriptor.copy(operations = super.descriptor.operations - Operations.IDENTIFY)
}

/** Uses Android's actual container demuxer without instantiating a PCM codec. */
private class AndroidAudioIdentityHandler : CapabilityHandler {
    override val descriptor = HandlerDescriptor("sound.android.identity", "sound", "Android audio container identification",
        listOf(FormatSupport(setOf("audio/*"), AndroidSoundFormats.extensions, magic = listOf(
            MagicSignature("52494646", description = "RIFF audio candidate"),
            MagicSignature("664c6143", description = "FLAC stream"),
            MagicSignature("4f676753", description = "Ogg stream"),
            MagicSignature("66747970", 4, description = "ISO Base Media container"),
            MagicSignature("3026b2758e66cf11a6d900aa0062ce6c", description = "ASF container"),
            MagicSignature("1a45dfa3", description = "EBML container"),
            MagicSignature("2e524d46", description = "RealMedia container"),
            MagicSignature("494433", description = "ID3-tagged audio"),
            MagicSignature("fff0", description = "ADTS AAC stream"),
            MagicSignature("fff1", description = "ADTS AAC stream"),
            MagicSignature("fff8", description = "ADTS AAC stream"),
            MagicSignature("fff9", description = "ADTS AAC stream")))),
        setOf(Operations.IDENTIFY), metadata = setOf("codecMime"),
        limitations = listOf("Android MediaExtractor container support varies by device; bounded prefix only"),
        attributes = mapOf("presentation" to "audio"))

    override fun probe(context: CapabilityProbeContext): HandlerProbe {
        if (context.resource.source == null) return super.probe(context)
        val bytes = context.prefix()
        if (bytes.isEmpty()) return HandlerProbe(MatchConfidence.NONE, emptySet())
        // These portable parsers already validate their complete codec header;
        // don't spin up Android's demuxer a second time during icon/list scans.
        val prefixText = if (bytes.size >= 12) String(bytes, 0, 12, Charsets.US_ASCII) else ""
        val byte0 = bytes[0].toInt() and 255
        val byte1 = if (bytes.size > 1) bytes[1].toInt() and 255 else 0
        if (prefixText.startsWith("fLaC") || prefixText.startsWith("OggS") ||
            prefixText.startsWith("RIFF") && prefixText.substring(8, 12) == "WAVE" ||
            prefixText.startsWith("ID3") || byte0 == 0xff && (byte1 and 0xe0) == 0xe0)
            return HandlerProbe(MatchConfidence.NONE, emptySet())
        val extractor = android.media.MediaExtractor()
        val dataSource = object : android.media.MediaDataSource() {
            override fun getSize(): Long = bytes.size.toLong()
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                require(position >= 0 && offset >= 0 && size >= 0 && size <= buffer.size - offset)
                if (size == 0) return 0
                if (position >= bytes.size) return -1
                val count = minOf(size, bytes.size - position.toInt())
                bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                return count
            }
            override fun close() {}
        }
        return try {
            extractor.setDataSource(dataSource)
            val track = extractor.firstAudioTrack() ?: return HandlerProbe(MatchConfidence.NONE, emptySet())
            val codec = track.second.getString(android.media.MediaFormat.KEY_MIME)
                ?.takeIf { it.startsWith("audio/") && it != "audio/raw" }
                ?: return HandlerProbe(MatchConfidence.NONE, emptySet())
            val mime = when {
                codec == "audio/mp4a-latm" && bytes.size >= 12 && String(bytes, 4, 4, Charsets.US_ASCII) == "ftyp" -> "audio/mp4"
                codec == "audio/mp4a-latm" -> "audio/aac"
                codec in setOf("audio/vorbis", "audio/opus", "audio/flac") && bytes.size >= 4 &&
                    String(bytes, 0, 4, Charsets.US_ASCII) == "OggS" -> "audio/ogg"
                else -> codec
            }
            HandlerProbe(MatchConfidence.VERIFIED, setOf(Operations.IDENTIFY),
                "Android demuxer found a readable audio track", mapOf("mimeType" to mime, "codecMime" to codec))
        } catch (cancel: java.io.InterruptedIOException) {
            throw cancel
        } catch (_: Exception) {
            HandlerProbe(MatchConfidence.NONE, emptySet())
        } catch (_: LinkageError) {
            HandlerProbe(MatchConfidence.NONE, emptySet())
        } finally {
            runCatching { extractor.release() }
            runCatching { dataSource.close() }
        }
    }
}

object AndroidSoundFormats { val extensions = setOf("ogg","oga","opus","mka","weba","mp4","3gp","3gpp","3g2","amr","awb","wma","ra","ape","ac3","dts","tta","mlp","thd","eac3","wv","a52") }

private fun androidAudioSignatureFormats() = listOf(
    // Audio-only extensions share generic containers with video. Keep their
    // extension-specific identity when the underlying container signature is valid.
    FormatSupport(setOf("audio/mp4"), setOf("m4a", "m4b", "m4r"), magic = listOf(
        MagicSignature("66747970", 4, description = "ISO Base Media audio container"))),
    FormatSupport(setOf("audio/alac"), setOf("alac"), magic = listOf(
        MagicSignature("66747970", 4, description = "ISO Base Media ALAC container"))),
    FormatSupport(setOf("audio/3gpp"), setOf("3ga"), magic = listOf(
        MagicSignature("66747970", 4, description = "3GPP audio container"))),
    FormatSupport(setOf("audio/x-matroska"), setOf("mka"), magic = listOf(
        MagicSignature("1a45dfa3", description = "Matroska audio container"))),
    FormatSupport(setOf("audio/webm"), setOf("weba"), magic = listOf(
        MagicSignature("1a45dfa3", description = "WebM audio container"))),
    FormatSupport(setOf("audio/x-pn-realaudio"), setOf("ra"), magic = listOf(
        MagicSignature("2e524d46", description = "RealAudio/RealMedia container"))),
    FormatSupport(setOf("audio/amr"), setOf("amr"), magic = listOf(MagicSignature("2321414d520a", description = "AMR-NB file header"))),
    FormatSupport(setOf("audio/amr-wb"), setOf("awb"), magic = listOf(MagicSignature("2321414d522d57420a", description = "AMR-WB file header"))),
    FormatSupport(setOf("audio/x-ape"), setOf("ape"), magic = listOf(MagicSignature("4d414320", description = "Monkey's Audio header"))),
    FormatSupport(setOf("audio/x-wavpack"), setOf("wv"), magic = listOf(MagicSignature("7776706b", description = "WavPack block header"))),
    FormatSupport(setOf("audio/x-tta"), setOf("tta"), magic = listOf(MagicSignature("54544131", description = "True Audio header"))),
    FormatSupport(setOf("audio/ac3"), setOf("ac3", "a52"), magic = listOf(MagicSignature("0b77", description = "AC-3/E-AC-3 sync word"))),
    FormatSupport(setOf("audio/eac3"), setOf("eac3"), magic = listOf(MagicSignature("0b77", description = "AC-3/E-AC-3 sync word"))),
    FormatSupport(setOf("audio/vnd.dts"), setOf("dts"), magic = listOf(
        MagicSignature("7ffe8001", description = "DTS core sync"),
        MagicSignature("fe7f0180", description = "DTS little-endian core sync"),
        MagicSignature("1fffe800", description = "DTS 14-bit core sync"),
        MagicSignature("ff1f00e8", description = "DTS little-endian 14-bit core sync"))),
    FormatSupport(setOf("audio/x-mlp"), setOf("mlp"), magic = listOf(MagicSignature("f8726fbb", 4, "MLP sync"))),
    FormatSupport(setOf("audio/x-truehd"), setOf("thd"), magic = listOf(MagicSignature("f8726fba", 4, "MLP/TrueHD sync")))
)

private fun gameMusicFormats() = listOf(
    FormatSupport(setOf("audio/x-nsf"), setOf("nsf"), magic = listOf(MagicSignature("4e45534d1a", description = "NES Sound Format header"))),
    FormatSupport(setOf("audio/x-nsfe"), setOf("nsfe"), magic = listOf(MagicSignature("4e534645", description = "Extended NES Sound Format header"))),
    FormatSupport(setOf("audio/x-spc"), setOf("spc"), magic = listOf(MagicSignature("534e45532d53504337303020536f756e642046696c652044617461", description = "SNES SPC700 header"))),
    FormatSupport(setOf("audio/x-gbs"), setOf("gbs"), magic = listOf(MagicSignature("47425301", description = "Game Boy Sound System header"))),
    FormatSupport(setOf("audio/x-hes"), setOf("hes"), magic = listOf(MagicSignature("4845534d", description = "PC Engine HES header"))),
    FormatSupport(setOf("audio/x-kss"), setOf("kss"), magic = listOf(
        MagicSignature("4b534343", description = "KSS header"), MagicSignature("4b535358", description = "KSSX header"))),
    FormatSupport(setOf("audio/x-gym"), setOf("gym"), magic = listOf(MagicSignature("47594d58", description = "GYM music log header"))),
    FormatSupport(setOf("audio/x-ay"), setOf("ay"), magic = listOf(MagicSignature("5a584159454d554c", description = "ZXAYEMUL header"))),
    FormatSupport(setOf("audio/x-vgm"), setOf("vgm", "vgz"), magic = listOf(MagicSignature("56676d20", description = "Video Game Music header"))),
    FormatSupport(setOf("audio/x-sap"), setOf("sap"), magic = listOf(MagicSignature("5341500d0a", description = "SAP music header"))),
    FormatSupport(setOf("audio/x-sgc"), setOf("sgc"), magic = listOf(MagicSignature("5347431a", description = "Sega Game Gear/SMS/SG-1000 music header")))
)

/** Identifies additional established chiptune files; playback is advertised only by real decoders. */
private fun retroMusicIdentityFormats() = listOf(
    FormatSupport(setOf("audio/x-sid"), setOf("sid"), magic = listOf(
        MagicSignature("50534944", description = "PlaySID SID tune"),
        MagicSignature("52534944", description = "RealSID SID tune"))),
    FormatSupport(setOf("audio/x-ym"), setOf("ym"), magic = listOf(
        MagicSignature("594d3221"), MagicSignature("594d3321"), MagicSignature("594d3362"),
        MagicSignature("594d3421"), MagicSignature("594d3521"), MagicSignature("594d3621"))),
    FormatSupport(setOf("audio/x-dro"), setOf("dro"), magic = listOf(
        MagicSignature("44425241574f504c", description = "DOSBox Raw OPL music")))
)
