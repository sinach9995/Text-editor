package com.asoraksh.hermeseditor

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Reads a text file with a hard size ceiling.
 *
 * Every read path in the app (open, share intent, cold-start restore, draft
 * recovery) goes through here. Before v1.6 only the manual Open path was
 * guarded; a 40 MB file remembered in the tab manifest could therefore kill
 * the process during startup, before the first frame.
 *
 * The limit counts *bytes*, so a Persian document hits it at half the
 * character count of an ASCII one -- that is the honest measure of memory,
 * because the bytes are held twice (byte buffer + decoded String).
 */
object BoundedReader {

    const val MAX_BYTES: Int = 5 * 1024 * 1024

    class TooLargeException(val limit: Int) : Exception("file exceeds $limit bytes")

    /** Reads the whole stream, or throws [TooLargeException] as soon as it passes [limit]. */
    fun readText(input: InputStream, limit: Int = MAX_BYTES): String {
        val out = ByteArrayOutputStream(64 * 1024)
        val chunk = ByteArray(16 * 1024)
        input.use { stream ->
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                if (out.size().toLong() + read > limit) throw TooLargeException(limit)
                out.write(chunk, 0, read)
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    fun readText(file: File, limit: Int = MAX_BYTES): String = readText(file.inputStream(), limit)
}