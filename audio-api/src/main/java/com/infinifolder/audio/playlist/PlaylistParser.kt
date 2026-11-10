package com.infinifolder.audio.playlist

import io.github.gretchycat.infinistream.CapabilityHandler
import io.github.gretchycat.infinistream.FormatSupport
import io.github.gretchycat.infinistream.HandlerDescriptor
import io.github.gretchycat.infinistream.HandlerProbe
import io.github.gretchycat.infinistream.MatchConfidence
import io.github.gretchycat.infinistream.MetadataCapabilityHandler
import io.github.gretchycat.infinistream.MetadataReadContext
import io.github.gretchycat.infinistream.MetadataRecord
import io.github.gretchycat.infinistream.Operations
import org.json.JSONArray
import org.json.JSONObject
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class PlaylistEntry(
    val title: String,
    val pathOrUri: String,
    val durationSeconds: Int = -1,
    val startByte: Long = 0L,
    val lengthBytes: Long = -1L,
    val performer: String = "",
    val discId: String = "",
    val durationMillis: Long? = null,
    val album: String = "",
    /** Decoder override for raw audio carried by a cue/TOC image with an opaque suffix. */
    val decoderExtension: String? = null,
    val directoryUri: String? = null,
    val directoryName: String? = null,
    val directoryCoverUri: String? = null,
    val directoryArtworkMetadataResolved: Boolean = false
)

/** Playlist parsing is a Sound capability in its own right, separate from audio decoders. */
object PlaylistParser : MetadataCapabilityHandler {
    override val descriptor = HandlerDescriptor(
        id = "sound.playlists",
        library = "sound",
        title = "Shareable audio playlists",
        formats = listOf(
            FormatSupport(mimeTypes = setOf("audio/x-mpegurl"), extensions = setOf("m3u", "m3u8")),
            FormatSupport(mimeTypes = setOf("audio/x-scpls"), extensions = setOf("pls")),
            FormatSupport(mimeTypes = setOf("application/xspf+xml"), extensions = setOf("xspf")),
            FormatSupport(mimeTypes = setOf("application/jspf+json"), extensions = setOf("jspf")),
            FormatSupport(mimeTypes = setOf("application/x-cue"), extensions = setOf("cue")),
            FormatSupport(mimeTypes = setOf("application/x-cdrdao-toc"), extensions = setOf("toc"))
        ),
        operations = setOf(Operations.IDENTIFY, Operations.METADATA, Operations.PLAY),
        metadata = setOf("tracks", "title", "creator", "album", "duration"),
        limitations = listOf("Playlist entries reference media; each referenced item still needs a usable source and decoder"),
        attributes = mapOf("presentation" to "audio")
    )

    private const val XSPF_NAMESPACE = "http://xspf.org/ns/0/"
    private const val MAX_METADATA_BYTES = 1024 * 1024
    private val XML_PLAYLIST_TAG = Regex("(?is)<(?:[A-Za-z_][\\w.-]*:)?playlist\\b")

    override fun probe(context: io.github.gretchycat.infinistream.CapabilityProbeContext): HandlerProbe {
        val score = descriptor.formats.maxOfOrNull { it.score(context.resource) } ?: 0
        val association = HandlerProbe(if (score > 0) MatchConfidence.CANDIDATE else MatchConfidence.NONE,
            descriptor.operations, if (score > 0) "Playlist extension or MIME association" else null)
        if (context.resource.source == null) return association
        val bytes = try { context.prefix() } catch (error: java.io.IOException) {
            return association.copy(reason = error.message ?: "Playlist prefix could not be read")
        }
        val totalLength = context.resource.metadata.length
        val complete = totalLength?.let { it <= bytes.size.toLong() } ?: (bytes.size < 64 * 1024)
        val name = context.resource.metadata.name.orEmpty()
        val content = decodeText(bytes)
        if (complete) {
            if (!isPlaylistContent(name, content))
                return HandlerProbe(MatchConfidence.NONE, emptySet(), "Playlist parser rejected the content")
            return HandlerProbe(MatchConfidence.VERIFIED, descriptor.operations,
                "Playlist structure parsed successfully", mapOf(
                    "mimeType" to playlistMime(name, content),
                    "trackCount" to parse(name, content).size.toString()
                ))
        }
        if (association.confidence == MatchConfidence.NONE && !hasPlaylistPrefix(content)) return association
        return association.copy(reason = "The content exceeds the probe limit; the full playlist parser validates it on open")
    }

    override fun extractMetadata(context: MetadataReadContext): MetadataRecord? {
        val name = context.resource.metadata.name.orEmpty()
        val length = context.length
        val requested = (length ?: MAX_METADATA_BYTES.toLong()).coerceAtMost(MAX_METADATA_BYTES.toLong()).toInt()
        val bytes = context.readAt(0, requested)
        val complete = length?.let { it <= bytes.size.toLong() } ?: (bytes.size < MAX_METADATA_BYTES)
        val content = decodeText(bytes)
        if (complete && !isPlaylistContent(name, content)) return null
        if (!complete && !hasPlaylistPrefix(content)) return null

        val entries = if (complete) parse(name, content) else emptyList()
        val titleAndCreator = playlistTitleAndCreator(name, content)
        val fields = buildMap {
            put("complete", complete.toString())
            if (complete) {
                put("trackCount", entries.size.toString())
                entries.count { it.pathOrUri.isNotBlank() }.takeIf { it > 0 }?.let { put("tracksWithLocation", it.toString()) }
                val durations = entries.mapNotNull { entry ->
                    entry.durationMillis ?: entry.durationSeconds.takeIf { it >= 0 }?.times(1000L)
                }
                if (durations.isNotEmpty()) put("durationMilliseconds", durations.sum().toString())
            }
            titleAndCreator.second?.takeIf { it.isNotBlank() }?.let { put("creator", it) }
            length?.let { put("sourceLengthBytes", it.toString()) }
        }
        return MetadataRecord("sound.playlists", playlistMime(name, content), titleAndCreator.first, fields,
            if (complete) emptyList() else listOf("Track metadata is partial because the playlist exceeds the read limit"))
    }

    private fun decodeText(bytes: ByteArray): String {
        val bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
        val offset = if (bytes.size >= 3 && bytes.copyOfRange(0, 3).contentEquals(bom)) 3 else 0
        return bytes.copyOfRange(offset, bytes.size).toString(Charsets.UTF_8)
    }

    private fun hasPlaylistPrefix(content: String): Boolean {
        val trimmed = content.trimStart().removePrefix("\uFEFF")
        return trimmed.startsWith("#EXTM3U", true) || trimmed.startsWith("[playlist]", true) ||
            trimmed.startsWith("<") && XML_PLAYLIST_TAG.containsMatchIn(trimmed.take(64 * 1024)) ||
            trimmed.startsWith("{\"playlist\"", true) ||
            AUDIO_TRACK.containsMatchIn(trimmed) || isCdrdaoToc(trimmed)
    }

    private fun isPlaylistContent(name: String, content: String): Boolean {
        if (isXspfDocument(content) || isJspfDocument(content) || content.contains("[playlist]", true) &&
            content.lineSequence().any { it.trim().startsWith("File", true) && '=' in it }) return true
        if (content.contains("#EXTM3U", true)) return content.lineSequence().any {
            val line = it.trim(); line.isNotEmpty() && !line.startsWith('#')
        } || content.lineSequence().any { it.trim().startsWith("#EXTINF:", true) }
        if (AUDIO_TRACK.containsMatchIn(content)) return true
        if (name.endsWith(".toc", true)) return isCdrdaoToc(content)
        if (name.endsWith(".cue", true)) return AUDIO_TRACK.containsMatchIn(content)
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("m3u", "m3u8") && content.lineSequence().any {
            val line = it.trim(); line.isNotEmpty() && !line.startsWith('#')
        }
    }

    private fun playlistMime(name: String, content: String): String = when {
        name.endsWith(".xspf", true) || isXspfDocument(content) -> "application/xspf+xml"
        name.endsWith(".jspf", true) || isJspfDocument(content) -> "application/jspf+json"
        name.endsWith(".pls", true) || content.contains("[playlist]", true) -> "audio/x-scpls"
        name.endsWith(".cue", true) -> "application/x-cue"
        name.endsWith(".toc", true) || isCdrdaoToc(content) -> "application/x-cdrdao-toc"
        else -> "audio/x-mpegurl"
    }

    private fun playlistTitleAndCreator(name: String, content: String): Pair<String?, String?> = when {
        name.endsWith(".xspf", true) || isXspfDocument(content) -> {
            val root = parseXml(content)?.documentElement
            if (root == null) null to null else childText(root, "title") to childText(root, "creator")
        }
        name.endsWith(".jspf", true) || isJspfDocument(content) -> runCatching {
            val playlist = JSONObject(content).optJSONObject("playlist") ?: return@runCatching null to null
            playlist.optString("title").takeIf { it.isNotBlank() } to
                playlist.optString("creator").takeIf { it.isNotBlank() }
        }.getOrDefault(null to null)
        name.endsWith(".pls", true) || content.contains("[playlist]", true) -> {
            val title = content.lineSequence().firstOrNull { it.trim().startsWith("Title1=", true) }
                ?.substringAfter('=')?.trim()?.takeIf { it.isNotBlank() }
            title to null
        }
        else -> {
            val title = content.lineSequence().firstOrNull { it.trim().startsWith("#PLAYLIST:", true) }
                ?.substringAfter(':')?.trim()?.takeIf { it.isNotBlank() }
            title to null
        }
    }

    fun isPlaylist(name: String, content: String = ""): Boolean {
        val lower = name.lowercase()
        if (lower.endsWith(".toc")) return content.isEmpty() || isCdrdaoToc(content)
        if (lower.endsWith(".m3u") || lower.endsWith(".m3u8") || lower.endsWith(".pls") ||
            lower.endsWith(".xspf") || lower.endsWith(".jspf") || lower.endsWith(".cue")) return true
        if (hasPlaylistPrefix(content) || content.contains("#EXTM3U") || content.contains("[playlist]") ||
            isXspfDocument(content) || isJspfDocument(content) || content.contains("TRACK 01 AUDIO") ||
            AUDIO_TRACK.containsMatchIn(content) || isCdrdaoToc(content)) return true
        return false
    }

    fun parse(name: String, content: String): List<PlaylistEntry> {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".pls") || content.contains("[playlist]") -> parsePls(content)
            lower.endsWith(".jspf") || isJspfDocument(content) -> parseJspf(content)
            lower.endsWith(".xspf") || isXspfDocument(content) -> parseXspf(content)
            lower.endsWith(".toc") || isCdrdaoToc(content) -> parseToc(content)
            lower.endsWith(".cue") || AUDIO_TRACK.containsMatchIn(content) -> parseCue(content)
            else -> parseM3u(content)
        }
    }

    private fun parseM3u(content: String): List<PlaylistEntry> {
        val entries = mutableListOf<PlaylistEntry>()
        var currentTitle = ""
        var currentDur = -1

        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#EXTINF:")) {
                val after = trimmed.removePrefix("#EXTINF:")
                val comma = after.indexOf(',')
                if (comma >= 0) {
                    currentDur = after.substring(0, comma).trim().toIntOrNull() ?: -1
                    currentTitle = after.substring(comma + 1).trim()
                } else {
                    currentDur = after.trim().toIntOrNull() ?: -1
                }
            } else if (!trimmed.startsWith("#")) {
                val title = if (currentTitle.isNotEmpty()) currentTitle else trimmed.substringAfterLast('/').substringAfterLast('\\')
                entries.add(PlaylistEntry(title = title, pathOrUri = trimmed, durationSeconds = currentDur))
                currentTitle = ""
                currentDur = -1
            }
        }
        return entries
    }

    private fun parsePls(content: String): List<PlaylistEntry> {
        val entries = mutableListOf<PlaylistEntry>()
        val files = mutableMapOf<Int, String>()
        val titles = mutableMapOf<Int, String>()
        val lengths = mutableMapOf<Int, Int>()

        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("File", ignoreCase = true)) {
                val eq = trimmed.indexOf('=')
                if (eq > 4) {
                    val idx = trimmed.substring(4, eq).toIntOrNull()
                    if (idx != null) files[idx] = trimmed.substring(eq + 1).trim()
                }
            } else if (trimmed.startsWith("Title", ignoreCase = true)) {
                val eq = trimmed.indexOf('=')
                if (eq > 5) {
                    val idx = trimmed.substring(5, eq).toIntOrNull()
                    if (idx != null) titles[idx] = trimmed.substring(eq + 1).trim()
                }
            } else if (trimmed.startsWith("Length", ignoreCase = true)) {
                val eq = trimmed.indexOf('=')
                if (eq > 6) {
                    val idx = trimmed.substring(6, eq).toIntOrNull()
                    if (idx != null) lengths[idx] = trimmed.substring(eq + 1).trim().toIntOrNull() ?: -1
                }
            }
        }

        for (idx in files.keys.sorted()) {
            val file = files[idx] ?: continue
            val title = titles[idx] ?: file.substringAfterLast('/').substringAfterLast('\\')
            val len = lengths[idx] ?: -1
            entries.add(PlaylistEntry(title, file, len))
        }

        return entries
    }

    private fun parseXspf(content: String): List<PlaylistEntry> {
        val entries = mutableListOf<PlaylistEntry>()
        val root = parseXml(content)?.documentElement ?: return entries
        val namespace = root.namespaceURI.orEmpty().trimEnd('/')
        if (localName(root) != "playlist" ||
            (namespace.isNotEmpty() && namespace != XSPF_NAMESPACE.trimEnd('/'))) return entries
        val trackList = childElement(root, "trackList") ?: return entries
        for (track in childElements(trackList, "track")) {
            // XSPF permits multiple locations, but a player renders at most one.
            val location = childElements(track, "location").firstOrNull()?.textContent?.trim()
                ?.takeIf { it.isNotEmpty() } ?: continue
            val title = childText(track, "title")
                ?.takeIf { it.isNotEmpty() }
                ?: location.substringAfterLast('/').substringAfterLast('\\')
            val duration = childText(track, "duration")?.toLongOrNull()?.takeIf { it >= 0L }
            entries += PlaylistEntry(
                title = title,
                pathOrUri = location,
                performer = childText(track, "creator").orEmpty(),
                durationMillis = duration,
                album = childText(track, "album").orEmpty()
            )
        }
        return entries
    }

    private fun parseJspf(content: String): List<PlaylistEntry> {
        val tracks = runCatching {
            val playlist = JSONObject(content).optJSONObject("playlist") ?: return emptyList()
            playlist.optJSONArray("track")
        }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until tracks.length()) {
                val track = tracks.optJSONObject(index) ?: continue
                val location = firstLocation(track.opt("location")) ?: continue
                val title = track.optString("title").takeIf { it.isNotBlank() }
                    ?: location.substringAfterLast('/').substringAfterLast('\\')
                val duration = numberAsLong(track.opt("duration"))?.takeIf { it >= 0L }
                val infiniExtension = track.optJSONObject("extension")
                    ?.optJSONObject("io.github.gretchycat.infiniselect")
                add(PlaylistEntry(
                    title = title,
                    pathOrUri = location,
                    performer = track.optString("creator"),
                    startByte = infiniExtension?.optLong("startByte", 0L)?.coerceAtLeast(0L) ?: 0L,
                    lengthBytes = infiniExtension?.optLong("lengthBytes", -1L) ?: -1L,
                    durationMillis = duration,
                    album = track.optString("album"),
                    decoderExtension = infiniExtension?.optString("decoderExtension")
                        ?.takeIf { it.isNotBlank() },
                    directoryUri = infiniExtension?.optString("directoryUri")
                        ?.takeIf { it.isNotBlank() && it != "null" },
                    directoryName = infiniExtension?.optString("directoryName")
                        ?.takeIf { it.isNotBlank() && it != "null" },
                    directoryCoverUri = infiniExtension?.optString("directoryCoverUri")
                        ?.takeIf { it.isNotBlank() && it != "null" },
                    directoryArtworkMetadataResolved = infiniExtension?.optBoolean(
                        "directoryArtworkMetadataResolved", false) == true
                ))
            }
        }
    }

    private fun firstLocation(value: Any?): String? = when (value) {
        is String -> value.trim().takeIf { it.isNotEmpty() }
        is JSONArray -> (0 until value.length()).asSequence()
            .mapNotNull { value.optString(it).trim().takeIf(String::isNotEmpty) }
            .firstOrNull()
        else -> null
    }

    private fun numberAsLong(value: Any?): Long? = when (value) {
        is Number -> value.toDouble().takeIf { it.isFinite() && it >= 0.0 && it <= Long.MAX_VALUE.toDouble() }?.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun isJspfDocument(content: String): Boolean {
        val trimmed = content.trimStart().removePrefix("\uFEFF")
        if (!trimmed.startsWith('{') || !trimmed.contains("\"playlist\"", true)) return false
        return runCatching {
            JSONObject(content).optJSONObject("playlist")?.has("track") == true
        }.getOrDefault(false)
    }

    private fun isXspfDocument(content: String): Boolean {
        val trimmed = content.trimStart().removePrefix("\uFEFF")
        if (trimmed.isEmpty() || !trimmed.startsWith('<') || !trimmed.contains("playlist", true)) return false
        return parseXml(content)?.documentElement?.let {
            localName(it) == "playlist" &&
                (it.namespaceURI.isNullOrEmpty() || it.namespaceURI.trimEnd('/') == XSPF_NAMESPACE.trimEnd('/'))
        } == true
    }

    private fun parseXml(content: String) = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Some Android XML factories do not implement this optional feature;
            // external entity/DTD access is separately disabled below and by the
            // resolver, so lack of the flag must not make valid XSPF unreadable.
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
            runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
        }
        val builder = factory.newDocumentBuilder()
        // Keep parsers that do not support one of the optional hardening flags from
        // resolving external entities through their default resolver.
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        // XSPF commonly includes a public external DTD. Android XML implementations
        // differ in whether they accept that declaration when external DTD loading is
        // disabled, so remove the declaration before parsing. This retains the XML
        // document while guaranteeing that parsing never needs to fetch the DTD.
        builder.parse(InputSource(StringReader(stripDoctype(content))))
    }.getOrNull()

    /** Removes one XML DOCTYPE declaration without being confused by quoted `>` or internal subsets. */
    private fun stripDoctype(xml: String): String {
        var cursor = 0
        var declarationStart = -1
        while (cursor < xml.length) {
            when {
                xml.startsWith("<!--", cursor) -> {
                    val end = xml.indexOf("-->", cursor + 4)
                    if (end < 0) return xml
                    cursor = end + 3
                }
                xml.startsWith("<![CDATA[", cursor) -> {
                    val end = xml.indexOf("]]>", cursor + 9)
                    if (end < 0) return xml
                    cursor = end + 3
                }
                xml.startsWith("<?", cursor) -> {
                    val end = xml.indexOf("?>", cursor + 2)
                    if (end < 0) return xml
                    cursor = end + 2
                }
                xml.regionMatches(cursor, "<!DOCTYPE", 0, "<!DOCTYPE".length, ignoreCase = true) -> {
                    declarationStart = cursor
                    break
                }
                else -> cursor++
            }
        }
        if (declarationStart < 0) return xml

        var quote: Char? = null
        var subsetDepth = 0
        cursor = declarationStart + "<!DOCTYPE".length
        while (cursor < xml.length) {
            val char = xml[cursor]
            if (quote != null) {
                if (char == quote) quote = null
            } else when (char) {
                '\'', '"' -> quote = char
                '[' -> subsetDepth++
                ']' -> if (subsetDepth > 0) subsetDepth--
                '>' -> if (subsetDepth == 0) return xml.removeRange(declarationStart, cursor + 1)
            }
            cursor++
        }
        // Malformed DOCTYPE: leave it for the secure XML parser to reject.
        return xml
    }

    private fun localName(element: org.w3c.dom.Element): String =
        (element.localName ?: element.nodeName.substringAfter(':')).lowercase()

    private fun childElements(parent: org.w3c.dom.Element, name: String): List<org.w3c.dom.Element> =
        buildList {
            val children = parent.childNodes
            for (index in 0 until children.length) {
                val child = children.item(index)
                if (child is org.w3c.dom.Element && localName(child) == name.lowercase()) add(child)
            }
        }

    private fun childElement(parent: org.w3c.dom.Element, name: String): org.w3c.dom.Element? =
        childElements(parent, name).firstOrNull()

    private fun childText(parent: org.w3c.dom.Element, name: String): String? =
        childElement(parent, name)?.textContent?.trim()

    private fun parseCue(content: String): List<PlaylistEntry> {
        val entries = mutableListOf<PlaylistEntry>()
        var globalPerformer = ""
        var globalTitle = ""
        var globalDiscId = ""
        var currentFile = ""
        var currentFileType = ""

        data class CueTrackInfo(
            val trackNum: Int,
            var title: String = "",
            var performer: String = "",
            var file: String = "",
            var fileType: String = "",
            var isAudio: Boolean = false,
            var index01Frames: Int = -1
        )

        val tracks = mutableListOf<CueTrackInfo>()
        var currentTrack: CueTrackInfo? = null

        fun parseFrames(timestamp: String): Int {
            val parts = timestamp.split(':')
            val mm = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val ss = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val ff = parts.getOrNull(2)?.toIntOrNull() ?: 0
            return (mm * 60 + ss) * 75 + ff
        }

        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val upper = trimmed.uppercase()
            when {
                upper.startsWith("REM DISCID ") || upper.startsWith("REM CDDB ") -> {
                    globalDiscId = trimmed.substringAfter("DISCID", "").substringAfter("CDDB", "").trim().take(8)
                }
                upper.startsWith("PERFORMER ") -> {
                    val p = extractQuotedOrRest(trimmed.substring(10).trim())
                    if (currentTrack != null) currentTrack.performer = p else globalPerformer = p
                }
                upper.startsWith("TITLE ") -> {
                    val t = extractQuotedOrRest(trimmed.substring(6).trim())
                    if (currentTrack != null) currentTrack.title = t else globalTitle = t
                }
                upper.startsWith("FILE ") -> {
                    val parsed = parseFileDirective(trimmed.substring(5).trim())
                    currentFile = parsed.first
                    currentFileType = parsed.second
                }
                upper.startsWith("TRACK ") -> {
                    val fields = trimmed.substringAfter("TRACK", "").trim().split(Regex("\\s+"), limit = 2)
                    val num = fields.firstOrNull()?.toIntOrNull() ?: (tracks.size + 1)
                    val mode = fields.getOrNull(1).orEmpty().substringBefore(' ').uppercase()
                    val t = CueTrackInfo(trackNum = num, file = currentFile, fileType = currentFileType,
                        isAudio = mode == "AUDIO", performer = globalPerformer)
                    tracks.add(t)
                    currentTrack = t
                }
                upper.startsWith("INDEX 01 ") || upper.startsWith("INDEX 1 ") -> {
                    val fields = trimmed.split(Regex("\\s+"), limit = 3)
                    currentTrack?.index01Frames = fields.getOrNull(2)?.let(::parseFrames) ?: -1
                }
            }
        }

        val discId = if (globalDiscId.isNotEmpty()) globalDiscId else {
            if (tracks.isNotEmpty() && tracks.all { it.index01Frames >= 0 }) {
                calculateCddbDiscId(tracks.map { it.index01Frames })
            } else ""
        }

        for (i in 0 until tracks.size) {
            val t = tracks[i]
            if (!t.isAudio || t.file.isBlank()) continue
            val nextTrackSameFile = tracks.subList(i + 1, tracks.size).firstOrNull { it.file == t.file && it.index01Frames >= 0 }
            val startFrames = if (t.index01Frames >= 0) t.index01Frames else 0
            val isRawCd = t.fileType.uppercase() in setOf("BINARY", "MOTOROLA")
            val startByte = if (isRawCd) startFrames * 2352L else 0L
            val (lengthBytes, durSec) = if (nextTrackSameFile != null && nextTrackSameFile.index01Frames > startFrames) {
                val frameDiff = nextTrackSameFile.index01Frames - startFrames
                (if (isRawCd) frameDiff * 2352L else -1L) to (frameDiff / 75)
            } else {
                -1L to -1
            }

            val title = t.title.ifBlank { "Track ${t.trackNum}" }
            entries.add(
                PlaylistEntry(
                    title = title,
                    pathOrUri = t.file,
                    durationSeconds = durSec,
                    startByte = startByte,
                    lengthBytes = lengthBytes,
                    performer = t.performer.ifBlank { globalPerformer },
                    discId = discId,
                    decoderExtension = if (isRawCd) {
                        if (t.fileType.equals("MOTOROLA", true)) "cdda-be" else "cdda"
                    } else null
                )
            )
        }

        return if (entries.isEmpty() && tracks.isEmpty() && currentFile.isNotEmpty()) {
            listOf(PlaylistEntry(title = globalTitle.ifBlank { currentFile }, pathOrUri = currentFile, discId = discId))
        } else {
            entries
        }
    }

    /** Parses cdrdao TOC audio tracks. CDDB xmcd files named *.toc are not playlists. */
    private fun parseToc(content: String): List<PlaylistEntry> {
        data class TocTrack(
            var title: String = "",
            var performer: String = "",
            var file: String = "",
            var startFrames: Int = 0,
            var lengthFrames: Int? = null,
            var isAudio: Boolean = false,
            var rawAudio: Boolean = false
        )
        val tracks = mutableListOf<TocTrack>()
        var current: TocTrack? = null
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            when {
                line.startsWith("TRACK ", true) -> {
                    current = TocTrack(isAudio = line.substringAfter(' ').trim().startsWith("AUDIO", true))
                    tracks += current
                }
                line.startsWith("FILE ", true) || line.startsWith("AUDIOFILE ", true) || line.startsWith("DATAFILE ", true) -> {
                    val directive = line.substringBefore(' ').uppercase()
                    val parsed = parseFileDirective(line.substringAfter(' ').trim())
                    current?.let { track ->
                        track.file = parsed.first
                        track.rawAudio = track.isAudio && directive != "DATAFILE" &&
                            (directive == "AUDIOFILE" || parsed.first.substringAfterLast('.', "").lowercase() in setOf("bin", "img", "raw", "cdda"))
                        val fields = fileDirectiveTail(line.substringAfter(' ').trim())
                        fields.getOrNull(0)?.let(::parseTocFrames)?.let { track.startFrames = it }
                        fields.getOrNull(1)?.let(::parseTocFrames)?.let { track.lengthFrames = it }
                    }
                }
                line.startsWith("START ", true) -> current?.let {
                    parseTocFrames(line.substringAfter(' ').trim())?.let { frames -> it.startFrames += frames }
                }
                line.startsWith("TITLE ", true) -> current?.title = extractQuotedOrRest(line.substringAfter(' ').trim())
                line.startsWith("PERFORMER ", true) -> current?.performer = extractQuotedOrRest(line.substringAfter(' ').trim())
            }
        }
        return tracks.mapNotNull { track ->
            if (!track.isAudio || track.file.isBlank()) return@mapNotNull null
            val start = if (track.rawAudio) track.startFrames * 2352L else 0L
            val length = if (track.rawAudio) track.lengthFrames?.times(2352L) ?: -1L else -1L
            PlaylistEntry(track.title.ifBlank { track.file.substringAfterLast('/').substringAfterLast('\\') },
                track.file, startByte = start, lengthBytes = length, performer = track.performer,
                decoderExtension = if (track.rawAudio) "cdda" else null)
        }
    }

    private fun isCdrdaoToc(content: String): Boolean {
        val trimmed = content.trimStart().removePrefix("\uFEFF")
        return trimmed.lineSequence().take(8).any { it.trim().uppercase() in setOf("CD_DA", "CD_ROM", "CD_ROM_XA") } &&
            TRACK_DIRECTIVE.containsMatchIn(trimmed)
    }

    private val AUDIO_TRACK = Regex("(?im)^\\s*TRACK\\s+\\d+\\s+AUDIO\\b")
    private val TRACK_DIRECTIVE = Regex("(?im)^\\s*TRACK\\s+\\w+")

    private fun parseFileDirective(value: String): Pair<String, String> {
        val trimmed = value.trim()
        val (token, tail) = if (trimmed.startsWith('"')) {
            val closing = trimmed.indexOf('"', 1)
            if (closing < 0) trimmed.removePrefix("\"") to ""
            else trimmed.substring(1, closing) to trimmed.substring(closing + 1).trim()
        } else {
            val fields = trimmed.split(Regex("\\s+"), limit = 2)
            fields.firstOrNull().orEmpty() to fields.getOrNull(1).orEmpty()
        }
        return token.trim() to tail.split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty().uppercase()
    }

    private fun fileDirectiveTail(value: String): List<String> {
        val trimmed = value.trim()
        val afterName = if (trimmed.startsWith('"')) {
            val closing = trimmed.indexOf('"', 1)
            if (closing < 0) "" else trimmed.substring(closing + 1)
        } else trimmed.split(Regex("\\s+"), limit = 2).getOrNull(1).orEmpty()
        val tokens = afterName.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        // The first token after a quoted filename is the file type in CUE, but
        // cdrdao FILE/AUDIOFILE offsets start immediately after the filename.
        return tokens
    }

    private fun parseCueFrames(value: String): Int? {
        val parts = value.trim().removeSurrounding("\"", "\"").split(':')
        if (parts.size != 3) return null
        val minutes = parts[0].toIntOrNull() ?: return null
        val seconds = parts[1].toIntOrNull() ?: return null
        val frames = parts[2].toIntOrNull() ?: return null
        if (minutes < 0 || seconds !in 0..59 || frames !in 0..74) return null
        return (minutes * 60 + seconds) * 75 + frames
    }

    private fun parseTocFrames(value: String): Int? = parseCueFrames(value) ?: value.trim().toIntOrNull()?.takeIf { it >= 0 }

    fun calculateCddbDiscId(trackOffsetsFrames: List<Int>, leadoutFrames: Int? = null): String {
        if (trackOffsetsFrames.isEmpty()) return ""
        val nTracks = trackOffsetsFrames.size
        fun cddbSum(n: Int): Int {
            var sum = 0
            var v = Math.abs(n)
            while (v > 0) {
                sum += v % 10
                v /= 10
            }
            return sum
        }

        var checksum = 0
        for (f in trackOffsetsFrames) {
            checksum += cddbSum((f + 150) / 75)
        }

        val lastOffset = trackOffsetsFrames.last()
        val totalSec = if (leadoutFrames != null && leadoutFrames > 0) {
            (leadoutFrames + 150) / 75 - 2
        } else {
            (lastOffset + 150) / 75 + 120
        }

        val discId = ((checksum % 0xFF) shl 24) or ((totalSec and 0xFFFF) shl 8) or (nTracks and 0xFF)
        return String.format(java.util.Locale.US, "%08x", discId)
    }

    private fun extractQuotedOrRest(s: String): String {
        val q1 = s.indexOf('"')
        val q2 = s.lastIndexOf('"')
        return if (q1 >= 0 && q2 > q1) s.substring(q1 + 1, q2).trim() else s.trim()
    }

}
