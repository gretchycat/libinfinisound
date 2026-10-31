package com.infinifolder.audio

import android.content.Context
import android.net.Uri
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File

internal object AndroidAudioMetadataReader : AudioMetadataReader {
    override fun readTags(source: AudioMetadataSource, includeArtwork: Boolean): AudioTags {
        var dataSource: MediaDataSource? = null
        try {
            return read(includeArtwork) { retriever ->
                when (source) {
                    is AudioMetadataSource.LocalFile -> retriever.setDataSource(source.file.absolutePath)
                    is AudioMetadataSource.Descriptor -> retriever.setDataSource(source.descriptor)
                    is AudioMetadataSource.Seekable -> {
                        val adapter = object : MediaDataSource() {
                            override fun getSize(): Long = source.size
                            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                                if (size == 0) return 0
                                val count = source.readAt(position, buffer, offset, size)
                                return if (count == 0) -1 else count
                            }
                            override fun close() {} // The host owns the source handle.
                        }
                        dataSource = adapter
                        retriever.setDataSource(adapter)
                    }
                }
            }
        } finally {
            runCatching { dataSource?.close() }
        }
    }

    fun readContentTags(context: Context, uri: Uri): AudioTags =
        read(false) { it.setDataSource(context, uri) }

    private fun read(includeArtwork: Boolean, configure: (MediaMetadataRetriever) -> Unit): AudioTags {
        val retriever = MediaMetadataRetriever()
        try {
            configure(retriever)
            return AudioTags(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),
                artwork = if (includeArtwork) retriever.embeddedPicture else null
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    override fun probeFormat(file: File, fallbackName: String, durationMs: Long): AudioFormatInfo =
        probeFormat(file.name, file.length(), fallbackName, durationMs,
            { it.setDataSource(file.absolutePath) }, { it.setDataSource(file.absolutePath) },
            { buffer -> file.inputStream().use { it.read(buffer) } })

    fun probeFormat(resource: io.github.gretchycat.infinistream.SourceResource,
        fallbackName: String, durationMs: Long): AudioFormatInfo = StreamAudioInput(resource).open().use { source ->
        val adapter = object : MediaDataSource() {
            override fun getSize() = source.size
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int) = source.readAt(position, buffer, offset, size)
            override fun close() {} // Borrowed until the enclosing metadata read finishes.
        }
        probeFormat(resource.metadata.name ?: fallbackName, source.size, fallbackName, durationMs,
            { it.setDataSource(adapter) }, { it.setDataSource(adapter) }, { buffer ->
                var at = 0
                while (at < buffer.size) {
                    val n = source.readAt(at.toLong(), buffer, at, buffer.size - at)
                    if (n < 0) break
                    if (n == 0) throw java.io.IOException("Metadata source made no progress")
                    at += n
                }
                at
            })
    }

    private fun probeFormat(name: String, size: Long, fallbackName: String, durationMs: Long,
        configureRetriever: (MediaMetadataRetriever) -> Unit, configureExtractor: (MediaExtractor) -> Unit,
        readHeader: (ByteArray) -> Int): AudioFormatInfo {
        val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { fallbackName.substringAfterLast('.', "").lowercase() }

        val isTracker = ext in setOf(
            "mod", "s3m", "xm", "it", "med", "okt", "mtm", "669", "far", "amf", "dsm", "ult", "psm", "umx"
        )
        val isMidi = ext in setOf("mid", "midi", "kar", "rmi", "smf")
        val isChiptune = ext in setOf(
            "sid", "nsf", "spc", "vgm", "vgz", "gym", "ay", "ym", "gbs", "hes", "kss", "dro", "imf"
        )
        val isSoundfont = ext in setOf("sf2", "sfz", "pat")

        if (isMidi) {
            return AudioFormatInfo(
                sampleRate = 0,
                channels = 0,
                bitrateKbps = 0,
                isBitrateRelevant = false,
                isSampleRateRelevant = false
            )
        }

        if (isTracker || isChiptune || isSoundfont) {
            return AudioFormatInfo(
                sampleRate = 0,
                channels = 0,
                bitrateKbps = 0,
                isBitrateRelevant = false,
                isSampleRateRelevant = true
            )
        }

        var sampleRate = 0
        var channels = 0
        var bitrateKbps = 0

        // 1. Try MediaMetadataRetriever
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                configureRetriever(retriever)
                val brStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                if (brStr != null) {
                    val b = brStr.toIntOrNull() ?: 0
                    if (b > 0) bitrateKbps = (b / 1000).coerceAtLeast(1)
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    val srStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                    if (srStr != null) {
                        val s = srStr.toIntOrNull() ?: 0
                        if (s > 0) sampleRate = s
                    }
                }
            } finally {
                retriever.release()
            }
        }

        // 2. Try MediaExtractor for accurate sampleRate, channels, and bitrate
        if (sampleRate == 0 || channels == 0 || bitrateKbps == 0) {
            runCatching {
                val extractor = MediaExtractor()
                try {
                    configureExtractor(extractor)
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("audio/")) {
                            if (sampleRate == 0 && format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (channels == 0 && format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                            if (bitrateKbps == 0 && format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                                val b = format.getInteger(MediaFormat.KEY_BIT_RATE)
                                if (b > 0) bitrateKbps = (b / 1000).coerceAtLeast(1)
                            }
                            break
                        }
                    }
                } finally {
                    extractor.release()
                }
            }
        }

        // 3. Fallback for WAV / PCM RIFF header
        if ((sampleRate == 0 || channels == 0 || bitrateKbps == 0) && (ext == "wav" || ext == "wave" || ext == "riff")) {
            runCatching {
                    val header = ByteArray(44)
                    if (readHeader(header) >= 44 && header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte()) {
                        val ch = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
                        val sr = (header[24].toInt() and 0xFF) or
                                ((header[25].toInt() and 0xFF) shl 8) or
                                ((header[26].toInt() and 0xFF) shl 16) or
                                ((header[27].toInt() and 0xFF) shl 24)
                        val bps = (header[34].toInt() and 0xFF) or ((header[35].toInt() and 0xFF) shl 8)
                        if (ch in 1..8) channels = ch
                        if (sr in 4000..384000) sampleRate = sr
                        if (sr > 0 && ch > 0 && bps > 0) {
                            bitrateKbps = ((sr.toLong() * ch * bps) / 1000L).toInt().coerceAtLeast(1)
                        }
                    }
            }
        }

        // 4. If bitrate is still not resolved, compute from file size and duration
        if (bitrateKbps == 0 && size > 0) {
            val dur = durationMs
            if (dur > 0) {
                bitrateKbps = ((size * 8L) / dur).toInt()
            }
        }


        return AudioFormatInfo(
            sampleRate = sampleRate,
            channels = channels,
            bitrateKbps = bitrateKbps,
            isBitrateRelevant = true,
            isSampleRateRelevant = true
        )
    }

}
