package com.infinifolder.audio

import io.github.gretchycat.infinistream.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioArtworkFormatContractTest {
    @Test fun audioArtworkModuleUsesTheCommonTypeThumbnailAndMetadataContract() {
        val registry = RuntimeCapabilities.discover()
        val handler = registry.registeredHandlers(Operations.THUMBNAIL).single { it.descriptor.id == "sound.artwork" }
        val normalized = handler.normalize(CapabilityProbeContext(CapabilityResource(StreamMetadata(
            name = "track.flac",
            content = ContentType(mimeType = "audio/flac")
        ))))!!

        assertTrue(handler.mimeTypes().containsAll(setOf("audio/*", "audio/mpeg", "audio/flac")))
        assertTrue(handler.extensions().containsAll(setOf("mp3", "flac", "m4a")))
        assertEquals(ViewerTarget.INFINIMEDIA, handler.viewerTarget())
        assertEquals("audio/flac", normalized.mimeType)
        assertTrue(normalized.thumbnailSupported)
        assertTrue(handler.metadataFields().containsAll(setOf("title", "artist", "album", "duration", "artworkAvailable")))
        assertNull(handler.thumbnail(ThumbnailRequest(CapabilityResource(StreamMetadata(name = "track.flac")), 256)))
    }
}
