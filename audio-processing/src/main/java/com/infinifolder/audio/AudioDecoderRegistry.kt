package com.infinifolder.audio

/** Tries adapters in registration order, retaining fallback after null results or decode errors. */
class AudioDecoderRegistry(
    decoders: List<AudioDecoder>,
    private val supportProbe: ((AudioProbe) -> Boolean)? = null
) : AudioProcessor {
    private val decoders = decoders.toList()

    override fun isSupported(probe: AudioProbe): Boolean =
        supportProbe?.invoke(probe) ?: decoders.any { it.matches(probe) }

    override fun decode(request: AudioDecodeRequest, observer: AudioDecodeObserver?): AudioDecodeResult? {
        val probe = request.probe
        for (decoder in decoders) {
            if (!decoder.matches(probe)) continue
            try {
                val pcm = decoder.decode(request) ?: continue
                val result = AudioDecodeResult(pcm, decoder.name, decoder.sourceFormat(request))
                observer?.decoded(result)
                return result
            } catch (error: Throwable) {
                observer?.warning(decoder.name, error)
            }
        }
        observer?.unsupported()
        return null
    }
}
