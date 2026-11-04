package com.infinifolder.audio

import io.github.gretchycat.infinistream.*

class AndroidSoundCapabilityProvider : CapabilityProvider {
    override val library="sound"
    override fun handlers():List<CapabilityHandler> = listOf(
        PcmCapabilityHandler(AndroidPcmDecoder,listOf(FormatSupport(setOf("audio/*"),AndroidSoundFormats.extensions)),"platform",
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
        PcmCapabilityHandler(GameMusicPcmDecoder,listOf(FormatSupport(extensions=setOf("nsf","nsfe","spc","gbs","hes","kss","gym","ay","vgm","vgz","sap","sgc"))),"preferred",
            runtime={native("com.infinifolder.audio.GameMusicNative") { GameMusicNative.available() }},limitations=listOf("Optional GME symbols; finite policy for untimed tracks")),
        PcmCapabilityHandler(LibVlcAvDecoder,listOf(FormatSupport(setOf("audio/*"))),"fallback",runtime={native("com.infinifolder.audio.LibVlcAvNative") { LibVlcAvNative.available() }},limitations=listOf("Bundled optional FFmpeg symbols; per-input validation required")),
        PcmCapabilityHandler(LibVlcPcmDecoder,listOf(FormatSupport(setOf("audio/*"))),"fallback",runtime={native("com.infinifolder.audio.LibVlcPcmNative") { LibVlcPcmNative.available() }},limitations=listOf("libVLC codec availability and per-input validation required"))
    )
    private fun native(name:String,symbolProbe:(()->Boolean)?=null):HandlerAvailability {
        Class.forName(name,true,javaClass.classLoader)
        if(symbolProbe?.invoke()==false)return HandlerAvailability(AvailabilityState.UNAVAILABLE,"Required native codec symbols or ABI are unavailable",mapOf(name to true,"codecSymbols" to false))
        return HandlerAvailability(AvailabilityState.CONDITIONAL,"Native bridge/symbol checks passed; per-input and configured-resource validation occurs on open",mapOf(name to true,"codecSymbols" to true))
    }
}

object AndroidSoundFormats { val extensions = setOf("ogg","oga","opus","mka","weba","mp4","3gp","3gpp","3g2","amr","awb","wma","ra","ape","ac3","dts","tta","mlp","thd","eac3","wv","a52") }
