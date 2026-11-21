package com.asoraksh.hermeseditor

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * App-private persistence for documents opened via temporary external grants
 * (such as Telegram or share sheets) whose URI permission expires on exit.
 */
object ImportedFiles {

    private const val FALLBACK_NAME = "Document.txt"
    private const val MAX_NAME_LEN = 120
    private val INVALID_CHARS = setOf(':', '/', '\\', '*', '?', '"', '<', '>', '|')

    fun keyHash(sourceKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourceKey.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun sanitizeName(displayName: String): String {
        val tail = displayName.substringAfterLast('/').substringAfterLast('\\')
        val replaced = buildString(tail.length) {
            for (ch in tail) {
                append(if (ch.code < 0x20 || ch in INVALID_CHARS) '_' else ch)
            }
        }
        var clean = replaced.trim().trimStart('.').trim()
        if (clean.isBlank() || clean.all { it == '_' }) return FALLBACK_NAME
        if (clean.length > MAX_NAME_LEN) {
            val dot = clean.lastIndexOf('.')
            clean = if (dot > 0 && clean.length - dot in 2..16) {
                val ext = clean.substring(dot)
                clean.substring(0, MAX_NAME_LEN - ext.length).trimEnd('.', ' ') + ext
            } else {
                clean.substring(0, MAX_NAME_LEN).trimEnd('.', ' ')
            }
            if (clean.isBlank() || clean.all { it == '_' }) return FALLBACK_NAME
        }
        return clean
    }

    fun targetFile(importsDir: File, sourceKey: String, displayName: String): File =
        File(File(importsDir, keyHash(sourceKey)), sanitizeName(displayName))

    fun ownedFile(importsDir: File, filePath: String?): File? {
        if (filePath.isNullOrBlank()) return null
        return runCatching {
            val root = importsDir.canonicalFile
            val candidate = File(filePath).canonicalFile
            if (candidate.path.startsWith(root.path + File.separator)) candidate else null
        }.getOrNull()
    }

    fun writeAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("Could not write ${target.name}")
            }
        }
    }

    fun delete(file: File) {
        runCatching { file.delete() }
        val parent = file.parentFile ?: return
        if (parent.isDirectory && parent.list()?.isEmpty() == true) {
            runCatching { parent.delete() }
        }
    }
}
