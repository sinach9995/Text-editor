package com.asoraksh.hermeseditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class BoundedReaderTest {

    private fun stream(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `reads a small document intact`() {
        val text = "خط اول\nline two\n"
        assertEquals(text, BoundedReader.readText(stream(text)))
    }

    @Test
    fun `multi-byte characters survive the round trip`() {
        val text = "سلام دنیا — naïve ✓"
        assertEquals(text, BoundedReader.readText(stream(text)))
    }

    @Test
    fun `an empty file reads as an empty string`() {
        assertEquals("", BoundedReader.readText(stream("")))
    }

    @Test
    fun `a file exactly at the limit is accepted`() {
        val text = "a".repeat(2048)
        assertEquals(text, BoundedReader.readText(stream(text), limit = 2048))
    }

    @Test
    fun `one byte over the limit throws TooLargeException`() {
        val text = "a".repeat(2049)
        try {
            BoundedReader.readText(stream(text), limit = 2048)
            fail("expected TooLargeException")
        } catch (_: BoundedReader.TooLargeException) {
            // expected
        }
    }

    @Test
    fun `the limit is counted in bytes, not characters`() {
        // 600 two-byte characters = 1200 bytes: over a 1000-byte limit even
        // though the character count is under it. That is the honest measure,
        // because both the byte buffer and the decoded String are held.
        val text = "é".repeat(600)
        assertTrue(text.length < 1000)
        try {
            BoundedReader.readText(stream(text), limit = 1000)
            fail("expected TooLargeException")
        } catch (_: BoundedReader.TooLargeException) {
            // expected
        }
    }
}