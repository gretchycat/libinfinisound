package com.infinifolder.audio

import io.github.gretchycat.infinistream.*
import java.io.IOException

class AudioArtworkCapabilityProvider : CapabilityProvider {
    override val library="sound"
    override fun handlers():List<CapabilityHandler> = listOf(AudioArtworkExtractor())
}
private class AudioArtworkExtractor : EncodedImageExtractor, MetadataCapabilityHandler {
    override val descriptor=HandlerDescriptor("sound.artwork","sound","Embedded audio artwork",
        audioArtworkFormats(),setOf("extract-image",Operations.METADATA,Operations.THUMBNAIL),
        metadata=setOf("title","artist","album","duration","artworkAvailable"),
        limitations=listOf("Android metadata backend; borrowed known-length positional input", "16 MiB read budget; 1.4 s cooperative limit; 8 MiB encoded image limit", "No image is returned when artwork is absent; directory cover selection is host policy"),
        attributes=mapOf("presentation" to "audio","viewerTarget" to "infinimedia"))
    override fun availability()=if(android.os.Build.VERSION.SDK_INT>=26)HandlerAvailability(AvailabilityState.CONDITIONAL,"Android metadata backend present; container/artwork support varies")
        else HandlerAvailability(AvailabilityState.UNAVAILABLE,"Android metadata backend unavailable")
    override fun extract(source:ByteSource,targetPixels:Int,cancellation:CancellationToken):ExtractedImage? {
        val positional=source as? PositionalReadable?:throw IOException("Artwork extraction needs positional input")
        val length=source.metadata.length?:throw IOException("Artwork extraction needs a known length")
        val deadline=System.nanoTime()+1_400_000_000L
        var remaining=16L*1024*1024
        val tags=AudioProcessing.metadata.readTags(AudioMetadataSource.Seekable(length){position,buffer,offset,count->
            cancellation.check();remaining-=count
            if(System.nanoTime()>deadline||remaining<0)throw java.io.InterruptedIOException("Audio artwork read limit reached")
            positional.readAt(position,buffer,offset,count)
        },includeArtwork=true)
        val bytes=tags.artwork?.takeIf {it.size<=8*1024*1024}?:return null
        return ExtractedImage.ownedBytes(bytes,input=source.metadata)
    }

    override fun extractMetadata(context:MetadataReadContext):MetadataRecord? {
        context.cancellation.check()
        val source=context.resource.source ?: return null
        val tags=AudioProcessing.readTags(source, includeArtwork = true, cancellation = context.cancellation,
            maxBytes = context.maxBytes, maxReads = context.maxReads, maxNanos = context.maxNanos)
        context.cancellation.check()
        val fields=buildMap {
            tags.artist?.takeIf(String::isNotBlank)?.let {put("artist",it)}
            tags.album?.takeIf(String::isNotBlank)?.let {put("album",it)}
            tags.duration?.takeIf(String::isNotBlank)?.let {put("duration",it)}
            tags.artwork?.let {put("artworkAvailable","true")}
        }
        if(tags.title.isNullOrBlank() && fields.isEmpty())return null
        val mime=context.resource.metadata.content.mimeType?.substringBefore(';')?.takeIf(String::isNotBlank) ?: "audio/*"
        return MetadataRecord(descriptor.id,mime,tags.title,fields)
    }
}

/** Match artwork extraction against the same registered formats as the audio parsers. */
private fun audioArtworkFormats(): List<FormatSupport> = (
    PortableSoundCapabilityProvider().handlers() + AndroidSoundCapabilityProvider().handlers()
).flatMap { it.describe().formats }
    .filter { format -> format.extensions.isNotEmpty() || format.mimeTypes.any { it.startsWith("audio/") } }
    .distinct()
    .ifEmpty { listOf(FormatSupport(setOf("audio/*"))) }
