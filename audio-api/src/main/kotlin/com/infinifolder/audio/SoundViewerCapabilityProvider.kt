package com.infinifolder.audio

import io.github.gretchycat.infinistream.CapabilityProvider
import io.github.gretchycat.infinistream.CapabilityHandler
import io.github.gretchycat.infinistream.FormatSupport
import io.github.gretchycat.infinistream.HandlerDescriptor
import io.github.gretchycat.infinistream.Operations

/** Lightweight consumer routing facts; parsing and playback stay in their owning apps. */
class SoundViewerCapabilityProvider : CapabilityProvider {
    override val library = "sound"

    override fun handlers(): List<CapabilityHandler> = listOf(AnsiPresentationHandler())
}

private class AnsiPresentationHandler : CapabilityHandler {
    override val descriptor = HandlerDescriptor(
        id = "sound.ansi.presentation",
        library = "sound",
        title = "ANSI art and music",
        formats = listOf(FormatSupport(
            mimeTypes = setOf("text/x-ansi", "image/x-ansi", "text/x-ascii-art"),
            extensions = setOf("ans", "ansi", "asc")
        )),
        operations = setOf(Operations.VIEW, Operations.PLAY),
        limitations = listOf("The Infinimedia application provides the terminal and audio presentation"),
        attributes = mapOf("presentation" to "audio", "viewerTarget" to "infinimedia")
    )
}
