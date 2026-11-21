package com.asoraksh.hermeseditor

import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.Code
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTableTest {

    private fun plain(node: Node?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        var c = node.firstChild
        while (c != null) {
            when (c) {
                is Text -> sb.append(c.literal)
                is Code -> sb.append(c.literal)
                else -> sb.append(plain(c))
            }
            c = c.next
        }
        return sb.toString()
    }

    @Test
    fun `parses RTL Persian GFM table with inline code and alignment`() {
        val md = """
            ## نقشه داستانی ویدیو

            | زمان | پرده | هدف روایی | تصویر اصلی روی صفحه |
            | --- | --- | --- | --- |
            | ۰:۰۰–۰:۳۵ | قلاب مدرک | شوک عدد واقعی | اسکرین‌شات `Activity` (`${'$'}0.45` و `742.8M`) |
            | ۱۰:۴۵–۱۲:۱۵ | پایان‌بندی | درس نهایی | ادیتور کد + پنل تنظیمات امنیتی |

            متن کامل اسکریپت
        """.trimIndent()

        val table = parseFirstTable(md)
        assertNotNull(table)
        val t = table!!
        assertEquals(1, t.headerRowCount)
        assertEquals(4, t.colCount)
        assertEquals(3, t.rows.size)
        assertEquals("زمان", plain(t.rows[0][0]))
        assertEquals("تصویر اصلی روی صفحه", plain(t.rows[0][3]))
        assertEquals("اسکرین‌شات Activity ($0.45 و 742.8M)", plain(t.rows[1][3]))
        assertNull(t.rows[0][0]?.alignment)
    }

    @Test
    fun `preserves explicit left, center, and right alignments and pads ragged rows`() {
        val md = """
            | Left | Center | Right |
            | :--- | :---: | ---: |
            | one | two |
        """.trimIndent()

        val table = parseFirstTable(md)!!
        assertEquals(1, table.headerRowCount)
        assertEquals(3, table.colCount)
        assertEquals(2, table.rows.size)
        assertEquals(TableCell.Alignment.LEFT, table.rows[0][0]?.alignment)
        assertEquals(TableCell.Alignment.CENTER, table.rows[0][1]?.alignment)
        assertEquals(TableCell.Alignment.RIGHT, table.rows[0][2]?.alignment)
        assertEquals("two", plain(table.rows[1][1]))
        assertNull(table.rows[1][2])
    }

    @Test
    fun `markdownToPlainText exports table rows cleanly without separator lines`() {
        val md = """
            # Title

            | Col A | Col B |
            | --- | --- |
            | Alpha | `Beta` |
            | Gamma | **Delta** |

            After table
        """.trimIndent()

        val out = markdownToPlainText(md)
        assertTrue(out.contains("Col A | Col B"))
        assertTrue(out.contains("Alpha | Beta"))
        assertTrue(out.contains("Gamma | Delta"))
        assertTrue(out.contains("After table"))
        assertTrue(!out.contains("---"))
    }
}
