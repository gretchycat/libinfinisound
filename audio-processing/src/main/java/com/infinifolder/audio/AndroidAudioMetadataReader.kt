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

    override fun probeFormat(file: File, fallbackName: String, durationMs: Long): AudioFormatInfo {
        val ext = file.extension.lowercase().ifEmpty { fallbackName.substringAfterLast('.', "").lowercase() }

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
                retriever.setDataSource(file.absolutePath)
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
                    extractor.setDataSource(file.absolutePath)
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
                file.inputStream().use { stream ->
                    val header = ByteArray(44)
                    if (stream.read(header) >= 44 && header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte()) {
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
        }

        // 4. If bitrate is still not resolved, compute from file size and duration
        if (bitrateKbps == 0 && file.length() > 0) {
            val dur = durationMs
            if (dur > 0) {
                bitrateKbps = ((file.length() * 8L) / dur).toInt()
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
