package com.infinifolder.audio.playlist

import io.github.gretchycat.infinistream.*
import org.junit.Assert.*
import org.junit.Test

class PlaylistParserCapabilityTest {
    @Test fun validatesXspfAndExtractsPlaylistMetadata() {
        val text = """<?xml version="1.0"?>
            |<playlist version="1" xmlns="http://xspf.org/ns/0/">
            |  <title>Fixture album</title><creator>Fixture artist</creator><trackList>
            |    <track><location>one.wav</location><title>One</title><duration>1200</duration></track>
            |    <track><location>two.wav</location><title>Two</title><duration>800</duration></track>
            |  </trackList>
            |</playlist>""".trimMargin()
        val resource = resource("fixture.xspf", text.toByteArray())
        val probe = PlaylistParser.probe(CapabilityProbeContext(resource))
        assertEquals(MatchConfidence.VERIFIED, probe.confidence)
        assertEquals("2", probe.metadata["trackCount"])
        assertEquals("application/xspf+xml", probe.metadata["mimeType"])

        MetadataReadContext(resource).use { context ->
            val metadata = PlaylistParser.extractMetadata(context)!!
            assertEquals("Fixture album", metadata.title)
            assertEquals("Fixture artist", metadata.fields["creator"])
            assertEquals("2", metadata.fields["trackCount"])
            assertEquals("2000", metadata.fields["durationMilliseconds"])
            assertEquals("application/xspf+xml", metadata.mimeType)
        }
    }

    @Test fun validatesJspfAndRejectsAnExtensionOnlyFalsePositive() {
        val jspf = """{"playlist":{"title":"JSON album","creator":"JSON artist","track":[
            |{"location":["first.mp3","fallback.mp3"],"title":"First","duration":1500},
            |{"location":"second.mp3","title":"Second","duration":500}]}}""".trimMargin()
        val resource = resource("fixture.jspf", jspf.toByteArray())
        assertEquals(MatchConfidence.VERIFIED, PlaylistParser.probe(CapabilityProbeContext(resource)).confidence)
        MetadataReadContext(resource).use { context ->
            val metadata = PlaylistParser.extractMetadata(context)!!
            assertEquals("JSON album", metadata.title)
            assertEquals("JSON artist", metadata.fields["creator"])
            assertEquals("2", metadata.fields["trackCount"])
            assertEquals("application/jspf+json", metadata.mimeType)
        }

        val invalid = resource("not-a-playlist.xspf", "This is plain text, not an XML playlist".toByteArray())
        assertEquals(MatchConfidence.NONE, PlaylistParser.probe(CapabilityProbeContext(invalid)).confidence)
    }

    @Test fun preservesAuthoritativeInfiniSelectReferencesInJspf() {
        val jspf = """{"playlist":{"track":[{"location":"content://app.selection/temporary",
            |"title":"Track","extension":{"io.github.gretchycat.infiniselect":{
            |"sourceReference":"content://com.infinifolder.documents/document/stable-file",
            |"directoryUri":"content://com.infinifolder.documents/document/stable-directory",
            |"directoryReference":"content://com.infinifolder.documents/document/stable-directory",
            |"directoryCoverUri":"content://app.selection/temporary-cover",
            |"directoryCoverReference":"content://com.infinifolder.documents/document/stable-cover"}}}]}}""".trimMargin()

        val entry = PlaylistParser.parse("selection.jspf", jspf).single()
        assertEquals("content://app.selection/temporary", entry.pathOrUri)
        assertEquals("content://com.infinifolder.documents/document/stable-file", entry.sourceReference)
        assertEquals("content://com.infinifolder.documents/document/stable-directory", entry.directoryReference)
        assertEquals("content://com.infinifolder.documents/document/stable-cover", entry.directoryCoverReference)
    }

    @Test fun validatesCueAndCdrdaoTocAndSeparatesCddbToc() {
        val cue = """
            |FILE "disc.bin" BINARY
            |  TRACK 01 AUDIO
            |    TITLE "Opening"
            |    INDEX 01 00:00:00
            |  TRACK 02 AUDIO
            |    INDEX 01 00:02:00
            |  TRACK 03 MODE1/2352
            |    INDEX 01 00:04:00
        """.trimMargin()
        val cueEntries = PlaylistParser.parse("disc.cue", cue)
        assertEquals(2, cueEntries.size)
        assertEquals("cdda", cueEntries.first().decoderExtension)
        assertEquals(150L * 2352L, cueEntries.first().lengthBytes)
        assertEquals(2 * 75L * 2352L, cueEntries.last().startByte)

        val toc = """
            |CD_DA
            |TRACK AUDIO
            |FILE "disc.bin" 0 00:02:00
            |TITLE "Opening"
            |TRACK AUDIO
            |FILE "disc.bin" 00:02:00
            |TITLE "Second"
        """.trimMargin()
        val tocResource = resource("disc.toc", toc.toByteArray())
        val tocProbe = PlaylistParser.probe(CapabilityProbeContext(tocResource))
        assertEquals(MatchConfidence.VERIFIED, tocProbe.confidence)
        assertEquals("application/x-cdrdao-toc", tocProbe.metadata["mimeType"])
        val tocEntries = PlaylistParser.parse("disc.toc", toc)
        assertEquals(2, tocEntries.size)
        assertEquals("cdda", tocEntries.first().decoderExtension)
        assertEquals(150L * 2352L, tocEntries.first().lengthBytes)

        val xmcd = """# xmcd CD database file
            |DISCID=04000102
            |DTITLE=Artist / Album
            |TTITLE0=Opening
        """.trimMargin()
        assertFalse(PlaylistParser.isPlaylist("cddb.toc", xmcd))
        assertEquals(MatchConfidence.NONE,
            PlaylistParser.probe(CapabilityProbeContext(resource("cddb.toc", xmcd.toByteArray()))).confidence)
    }

    private fun resource(name: String, bytes: ByteArray): CapabilityResource {
        val sourceMetadata = StreamMetadata(name = name, length = bytes.size.toLong(),
            capabilities = setOf(Capability.READ, Capability.KNOWN_LENGTH))
        val source = object : SourceResource {
            override val metadata = sourceMetadata
            override fun open(): ByteSource = object : ByteSource {
                override val metadata = sourceMetadata
                private var position = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    requireRange(position.toLong(), buffer, offset, length)
                    if (length == 0) return 0
                    if (position >= bytes.size) return -1
                    val count = minOf(length, bytes.size - position)
                    bytes.copyInto(buffer, offset, position, position + count)
                    position += count
                    return count
                }
                override fun close() = Unit
            }
        }
        return CapabilityResource(sourceMetadata, source = source)
    }
}
