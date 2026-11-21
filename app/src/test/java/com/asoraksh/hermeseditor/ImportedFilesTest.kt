package com.asoraksh.hermeseditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ImportedFilesTest {

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("hermes-imports-test").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `sanitizeName strips traversal and invalid characters while keeping Unicode and extension`() {
        assertEquals("surplus_youtube_script_v2.md", ImportedFiles.sanitizeName("surplus_youtube_script_v2.md"))
        assertEquals("یادداشت_های_روزانه.md", ImportedFiles.sanitizeName("یادداشت_های_روزانه.md"))
        assertEquals("evil.txt", ImportedFiles.sanitizeName("../../evil.txt"))
        assertEquals("notes 12_30.txt", ImportedFiles.sanitizeName("notes 12:30.txt"))
        assertEquals("Document.txt", ImportedFiles.sanitizeName("..."))
        assertEquals("Document.txt", ImportedFiles.sanitizeName("   "))

        val longName = "a".repeat(150) + ".md"
        val truncated = ImportedFiles.sanitizeName(longName)
        assertTrue(truncated.length <= 120)
        assertTrue(truncated.endsWith(".md"))
    }

    @Test
    fun `targetFile is deterministic per source key and isolates distinct keys`() = withTempDir { root ->
        val a1 = ImportedFiles.targetFile(root, "content://org.telegram.messenger.provider/1", "script.md")
        val a2 = ImportedFiles.targetFile(root, "content://org.telegram.messenger.provider/1", "script.md")
        val b = ImportedFiles.targetFile(root, "content://org.telegram.messenger.provider/2", "script.md")

        assertEquals(a1, a2)
        assertNotEquals(a1, b)
        assertEquals("script.md", a1.name)
    }

    @Test
    fun `writeAtomic writes and overwrites UTF-8 text safely`() = withTempDir { root ->
        val target = ImportedFiles.targetFile(root, "content://telegram/1", "notes.md")
        ImportedFiles.writeAtomic(target, "سلام دنیا")
        assertEquals("سلام دنیا", target.readText(Charsets.UTF_8))

        ImportedFiles.writeAtomic(target, "نسخه دوم — v2")
        assertEquals("نسخه دوم — v2", target.readText(Charsets.UTF_8))
        assertFalse(File(target.parentFile, target.name + ".tmp").exists())
    }

    @Test
    fun `ownedFile accepts files inside importsDir and blocks escaping paths`() = withTempDir { root ->
        val importsDir = File(root, "imports")
        val inside = ImportedFiles.targetFile(importsDir, "key", "doc.txt")
        ImportedFiles.writeAtomic(inside, "ok")

        val resolved = ImportedFiles.ownedFile(importsDir, inside.absolutePath)
        assertNotNull(resolved)
        assertEquals(inside.canonicalFile, resolved)

        val escaped = File(importsDir, "../draft.txt").absolutePath
        assertNull(ImportedFiles.ownedFile(importsDir, escaped))

        val siblingPrefix = File(root, "imports_evil/secret.txt").absolutePath
        assertNull(ImportedFiles.ownedFile(importsDir, siblingPrefix))

        assertNull(ImportedFiles.ownedFile(importsDir, null))
        assertNull(ImportedFiles.ownedFile(importsDir, ""))
    }

    @Test
    fun `delete removes the cached file and empty parent folder while keeping importsDir`() = withTempDir { root ->
        val importsDir = File(root, "imports")
        val target = ImportedFiles.targetFile(importsDir, "content://telegram/42", "note.txt")
        ImportedFiles.writeAtomic(target, "hello")
        val hashDir = target.parentFile!!
        assertTrue(target.exists())

        ImportedFiles.delete(target)
        assertFalse(target.exists())
        assertFalse(hashDir.exists())
        importsDir.mkdirs()
        assertTrue(importsDir.exists())
    }
}
