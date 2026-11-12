package com.infinifolder.audio

import io.github.gretchycat.infinistream.Operations
import io.github.gretchycat.infinistream.RuntimeCapabilities
import io.github.gretchycat.infinistream.ViewerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundViewerCapabilityProviderTest {
    @Test fun ansiArtAndMusicHasALightweightInfinimediaPresentationCapability() {
        val handler = RuntimeCapabilities.discover().registeredHandlers().single {
            it.descriptor.id == "sound.ansi.presentation"
        }

        assertEquals(setOf("ans", "ansi", "asc"), handler.extensions())
        assertTrue(handler.mimeTypes().containsAll(setOf("text/x-ansi", "image/x-ansi", "text/x-ascii-art")))
        assertTrue(handler.describe().operations.containsAll(setOf(Operations.VIEW, Operations.PLAY)))
        assertEquals(ViewerTarget.INFINIMEDIA, handler.viewerTarget())
    }
}
