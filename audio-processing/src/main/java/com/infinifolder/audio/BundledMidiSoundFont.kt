package com.infinifolder.audio

import android.content.Context
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.SequenceInputStream
import java.util.Collections
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Stream a compressed asset to a verified file; never hold the whole SF2 in the Java heap. */
internal object BundledMidiSoundFont {
    private const val BYTES = 148_398_306L
    private var cached: File? = null

    @Synchronized fun file(context: Context): File {
        cached?.takeIf { it.isFile && it.length() == BYTES }?.let { return it }
        val expected = context.assets.open("soundfonts/FluidR3_GM.sha256").bufferedReader().use { it.readText().substringBefore(' ').trim() }
        if (!expected.matches(Regex("[0-9a-f]{64}"))) throw IOException("Invalid bundled SoundFont checksum")
        val directory = File(context.filesDir, "soundfonts")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create SoundFont cache")
        val destination = File(directory, "FluidR3_GM.sf2")
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { source ->
                val buffer = ByteArray(65536)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SoundFont verification cancelled")
                    val count = source.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        if (destination.length() == BYTES && digest(destination) == expected) return destination.also { cached = it }
        val temporary = File.createTempFile("FluidR3-", ".partial", directory)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var written = 0L
            val compressed = SequenceInputStream(Collections.enumeration((0..2).map {
                context.assets.open("soundfonts/FluidR3_GM.sf2.part$it.bz2")
            }))
            BZip2CompressorInputStream(compressed).use { source ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SoundFont extraction cancelled")
                        val count = source.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > BYTES) throw IOException("Bundled SoundFont exceeds its declared size")
                        hash.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            val actual = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (written != BYTES || actual != expected) throw IOException("Bundled SoundFont is incomplete or corrupt")
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return destination.also { cached = it }
        } finally { temporary.delete() }
    }
}
