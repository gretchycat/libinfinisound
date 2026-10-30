package com.infinifolder.audio.cddb

data class CddbRecord(
    val discId: String,
    val artist: String,
    val album: String,
    val year: String? = null,
    val genre: String? = null,
    val trackTitles: List<String> = emptyList()
)

object CddbParser {
    fun parseXmcd(text: String): CddbRecord? {
        var discId = ""
        var dtitle = ""
        var year: String? = null
        var genre: String? = null
        val titlesMap = mutableMapOf<Int, StringBuilder>()

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val key = trimmed.substring(0, eq).trim().uppercase()
            val value = trimmed.substring(eq + 1).trim()

            when {
                key == "DISCID" -> if (discId.isEmpty()) discId = value.split(',').first().trim()
                key == "DTITLE" -> dtitle = if (dtitle.isEmpty()) value else "$dtitle$value"
                key == "DYEAR" -> year = value
                key == "DGENRE" -> genre = value
                key.startsWith("TTITLE") -> {
                    val idx = key.removePrefix("TTITLE").toIntOrNull()
                    if (idx != null) {
                        titlesMap.getOrPut(idx) { StringBuilder() }.append(value)
                    }
                }
            }
        }

        if (dtitle.isEmpty() && titlesMap.isEmpty()) return null

        val artist = if (dtitle.contains(" / ")) dtitle.substringBefore(" / ").trim() else ""
        val album = if (dtitle.contains(" / ")) dtitle.substringAfter(" / ").trim() else dtitle.trim()

        val trackTitles = titlesMap.keys.sorted().map { idx ->
            titlesMap[idx].toString().trim()
        }

        return CddbRecord(
            discId = discId,
            artist = artist,
            album = album,
            year = year,
            genre = genre,
            trackTitles = trackTitles
        )
    }

}
