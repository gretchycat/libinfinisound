package com.infinifolder.audio

import java.io.File
import java.io.FileDescriptor

/** Source handles are borrowed for the read; the host retains and closes their leases. */
sealed interface AudioMetadataSource {
    data class LocalFile(val file: File) : AudioMetadataSource
    data class Descriptor(val descriptor: FileDescriptor) : AudioMetadataSource
    class Seekable(
        val size: Long,
        val readAt: (position: Long, buffer: ByteArray, offset: Int, size: Int) -> Int
    ) : AudioMetadataSource
}

/** Artwork is encoded data; image decoding and display belong to the host. */
data class AudioTags(
    val title: String?,
    val artist: String?,
    val album: String?,
    val duration: String?,
    val artwork: ByteArray? = null
)

interface AudioMetadataReader {
    fun probeFormat(file: File, fallbackName: String = "", durationMs: Long = 0L): AudioFormatInfo
    fun readTags(source: AudioMetadataSource, includeArtwork: Boolean = false): AudioTags
}
