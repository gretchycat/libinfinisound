package com.infinifolder.audio

import io.github.gretchycat.infinistream.*
import java.io.IOException

class AudioArtworkCapabilityProvider : CapabilityProvider {
    override val library="sound"
    override fun handlers():List<CapabilityHandler> = listOf(AudioArtworkExtractor())
}
private class AudioArtworkExtractor : EncodedImageExtractor {
    override val descriptor=HandlerDescriptor("sound.artwork","sound","Embedded audio artwork",
        listOf(FormatSupport(setOf("audio/*"))),setOf("extract-image",Operations.METADATA),metadata=setOf("encodedArtwork"),
        limitations=listOf("Android metadata backend; borrowed known-length positional input", "16 MiB read budget; 1.4 s cooperative limit; 8 MiB encoded image limit", "No image is returned when artwork is absent; directory cover selection is host policy"))
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
}
