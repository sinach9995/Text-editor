package com.asoraksh.hermeseditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabManifestCodecTest {

    private val sample = TabManifest(
        tabs = listOf(
            TabRecord("tab_1", "content://com.android.providers.downloads.documents/document/42", "notes.md", true, 1234, true, false, 480),
            TabRecord("tab_2", null, "untitled.txt", false, 0, false, true, 0),
        ),
        activeTabId = "tab_2",
    )

    @Test
    fun `round trip keeps every field`() {
        val decoded = TabManifestCodec.decode(TabManifestCodec.encode(sample))
        assertNotNull(decoded)
        assertEquals(sample, decoded)
    }

    @Test
    fun `null uri survives as null and does not become the string null`() {
        val decoded = TabManifestCodec.decode(TabManifestCodec.encode(sample))!!
        assertNull(decoded.tabs[1].uri)
    }

    @Test
    fun `garbage decodes to null instead of throwing`() {
        assertNull(TabManifestCodec.decode("not json at all"))
        assertNull(TabManifestCodec.decode("""{"version":1}"""))
        assertNull(TabManifestCodec.decode(""))
        assertNull(TabManifestCodec.decode(null))
    }

    @Test
    fun `unknown active id falls back to the first tab`() {
        val manifest = sample.copy(activeTabId = "tab_gone")
        val decoded = TabManifestCodec.decode(TabManifestCodec.encode(manifest))!!
        assertEquals("tab_1", decoded.activeTabId)
    }

    @Test
    fun `a tab without id is dropped rather than crashing the restore`() {
        val raw = """{"version":1,"active":"tab_1","tabs":[{"title":"x.txt"},{"id":"tab_1","title":"y.txt"}]}"""
        val decoded = TabManifestCodec.decode(raw)!!
        assertEquals(1, decoded.tabs.size)
        assertEquals("tab_1", decoded.tabs[0].id)
    }

    @Test
    fun `legacy single document migrates to tab_1`() {
        val manifest = TabManifestCodec.migrate(
            legacyUri = "content://docs/9",
            legacyTitle = "old.txt",
            legacyDirty = true,
            legacyChars = 77,
        )
        assertEquals("tab_1", manifest.activeTabId)
        assertEquals(1, manifest.tabs.size)
        with(manifest.tabs[0]) {
            assertEquals("tab_1", id)
            assertEquals("old.txt", title)
            assertEquals("content://docs/9", uri)
            assertTrue(isDirty)
            assertEquals(77, charCount)
        }
    }

    @Test
    fun `legacy migrate without a uri yields a clean untitled tab`() {
        val manifest = TabManifestCodec.migrate(null, null, false, 0)
        assertNull(manifest.tabs[0].uri)
        assertFalse(manifest.tabs[0].isDirty)
        assertEquals(EditorViewModel.UNTOOLED_TXT, manifest.tabs[0].title)
    }
}

class TabBudgetTest {

    private fun records(count: Int, chars: Int) =
        (1..count).map { TabRecord("tab_$it", null, "t$it", false, chars, false, false, 0) }

    @Test
    fun `ten tabs are allowed, eleven are not`() {
        assertTrue(TabBudget.canAddTab(records(9, 0)))
        assertFalse(TabBudget.canAddTab(records(10, 0)))
    }

    @Test
    fun `the text budget counts every tab, hydrated or not`() {
        val full = records(3, TabBudget.MAX_TOTAL_CHARS / 3 + 1)
        assertFalse(TabBudget.canAddTab(full))
        assertFalse(TabBudget.canAccept(records(1, 0), TabBudget.MAX_TOTAL_CHARS + 1))
        assertTrue(TabBudget.canAccept(records(1, 0), TabBudget.MAX_TOTAL_CHARS))
    }

    @Test
    fun `an empty tab set fits`() {
        assertTrue(TabBudget.canAddTab(emptyList()))
        assertEquals(0L, TabBudget.totalChars(emptyList()))
    }
}