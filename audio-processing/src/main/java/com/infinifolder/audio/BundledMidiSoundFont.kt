package com.infinifolder.audio

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Stream the bundled SF3 asset to a verified file without holding it in the Java heap. */
internal object BundledMidiSoundFont {
    private const val ASSET = "soundfonts/FluidR3Mono_GM.sf3"
    private const val CHECKSUM_ASSET = "soundfonts/FluidR3Mono_GM.sha256"
    private const val FILE_NAME = "FluidR3Mono_GM.sf3"
    private const val BYTES = 14_540_067L
    private var cached: File? = null

    @Synchronized fun file(context: Context): File {
        cached?.takeIf { it.isFile && it.length() == BYTES }?.let { return it }
        val expected = context.assets.open(CHECKSUM_ASSET).bufferedReader().use {
            it.readText().substringBefore(' ').trim()
        }
        if (!expected.matches(Regex("[0-9a-f]{64}"))) throw IOException("Invalid bundled SoundFont checksum")
        val directory = File(context.filesDir, "soundfonts")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create SoundFont cache")
        val destination = File(directory, FILE_NAME)
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
        fun finish(file: File): File {
            // Remove the old bundled SF2 cache once the replacement is verified.
            File(directory, "FluidR3_GM.sf2").delete()
            directory.listFiles { candidate ->
                candidate.name.startsWith("FluidR3-") && candidate.name.endsWith(".partial")
            }?.forEach(File::delete)
            return file.also { cached = it }
        }

        if (destination.length() == BYTES && digest(destination) == expected) return finish(destination)
        val temporary = File.createTempFile("FluidR3Mono-", ".partial", directory)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var written = 0L
            context.assets.open(ASSET).use { source ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SoundFont copy cancelled")
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
            return finish(destination)
        } finally { temporary.delete() }
    }
}
