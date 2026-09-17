package com.asoraksh.hermeseditor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.unit.sp

private val linkColor = Color(0xFF236DD1)
private val codeBg = Color(0xFFECEEF3)

private fun transform(md: String, activeMatch: TextRange? = null, markdown: Boolean = true): TransformedText =
    MarkdownSourceTransformation(linkColor, codeBg, 17.sp, markdown, activeMatch).filter(AnnotatedString(md))

private fun stylesAt(t: TransformedText, index: Int): List<SpanStyle> =
    t.text.spanStyles.filter { index >= it.start && index < it.end }.map { it.item }

private fun hasStyle(t: TransformedText, index: Int, pred: (SpanStyle) -> Boolean): Boolean =
    stylesAt(t, index).any(pred)

private fun backgroundAt(t: TransformedText, index: Int): Color? =
    stylesAt(t, index).map { it.background }.lastOrNull { it != Color.Unspecified }

private fun check(cond: Boolean, msg: () -> String) {
    if (!cond) throw IllegalStateException(msg())
}

fun main() {
    val md = "# Title\n\nSome **bold** and *em* and `code` text\n\n- item\n\n> quote\n\n```kotlin\nval x = 1\n```\n\n[label](https://example.com)\n"

    val t = transform(md)
    check(t.text.text == md) { "source text changed" }
    check(t.offsetMapping == OffsetMapping.Identity) { "offset mapping not identity" }
    for (offset in 0..md.length) {
        check(t.offsetMapping.originalToTransformed(offset) == offset) { "source offset changed" }
        check(t.offsetMapping.transformedToOriginal(offset) == offset) { "display offset changed" }
    }

    val headingIdx = md.indexOf("Title")
    check(hasStyle(t, headingIdx) { it.fontWeight == FontWeight.Bold }) { "heading not bold" }
    check(hasStyle(t, headingIdx) { it.fontSize == 17.sp * 1.5f }) { "h1 not enlarged" }
    val h2 = transform("## Sub head")
    check(hasStyle(h2, h2.text.text.indexOf("Sub")) { it.fontSize == 17.sp * 1.3f }) { "h2 not enlarged 1.3x" }

    val boldIdx = md.indexOf("bold")
    check(hasStyle(t, boldIdx) { it.fontWeight == FontWeight.Bold }) { "bold content not bold" }

    val emReal = md.indexOf("*em*") + 1
    check(hasStyle(t, emReal) { it.fontStyle == FontStyle.Italic }) { "emphasis not italic" }

    val codeIdx = md.indexOf("`code`") + 1
    check(hasStyle(t, codeIdx) { it.fontFamily == FontFamily.Monospace }) { "inline code not monospace" }
    check(backgroundAt(t, codeIdx) == codeBg) { "inline code missing background" }

    val fencedIdx = md.indexOf("val x = 1")
    check(hasStyle(t, fencedIdx) { it.fontFamily == FontFamily.Monospace }) { "fenced code not monospace" }
    check(backgroundAt(t, fencedIdx) == codeBg) { "fenced code missing background" }

    val linkIdx = md.indexOf("label")
    check(hasStyle(t, linkIdx) { it.color == linkColor }) { "link not link-colored" }
    val urlIdx = md.indexOf("https://example.com")
    check(hasStyle(t, urlIdx) { it.color == linkColor }) { "link url not link-colored" }

    val quoteIdx = md.indexOf("quote")
    check(hasStyle(t, quoteIdx) { it.fontStyle == FontStyle.Italic }) { "blockquote not italic" }

    val markerIdx = md.indexOf("- item")
    check(hasStyle(t, markerIdx) { it.color == linkColor }) { "list marker not styled" }
    check(!hasStyle(t, markerIdx + 2) { it.fontWeight == FontWeight.Bold }) { "list content unexpectedly bold" }

    // Active match highlight, including inside code spans.
    val insideCode = transform("run `code` now", TextRange(0, 3))
    val matchInCode = transform(md, TextRange(fencedIdx, fencedIdx + 3))
    val bg = backgroundAt(matchInCode, fencedIdx)
    check(bg != null && bg != codeBg && bg != Color.Unspecified) { "active match highlight missing inside fenced code" }
    check(backgroundAt(insideCode, 1) != null) { "active match highlight missing in plain text" }

    // Clamping of stale/out-of-range match ranges must not throw.
    transform(md, TextRange(100000, 0))
    transform(md, TextRange(md.length + 1, md.length + 5))

    // Nested precedence: inline code keeps its background inside a quote.
    val nested = transform("> use `x` now")
    check(backgroundAt(nested, nested.text.text.indexOf("x")) == codeBg) { "code bg lost inside blockquote" }

    // markdown=false must leave the text untouched except any active match.
    val plain = transform("**b** `c`", markdown = false)
    check(plain.text.text == "**b** `c`") { "markdown=false changed text" }
    check(plain.text.spanStyles.isEmpty()) { "markdown=false added styling" }

    // Empty and whitespace-only input.
    val empty = transform("")
    check(empty.text.text.isEmpty()) { "empty input changed" }
    check(empty.offsetMapping == OffsetMapping.Identity) { "empty mapping not identity" }
    transform("\r\n\r\n")

    // RTL / Persian and astral-plane characters: UTF-16 offsets must stay exact.
    val fa = "# عنوان\n\n**متن** با *تاکید* و `کد`\n"
    val faT = transform(fa)
    check(faT.text.text == fa) { "persian text changed" }
    check(hasStyle(faT, fa.indexOf("عنوان")) { it.fontWeight == FontWeight.Bold }) { "persian heading not bold" }
    check(hasStyle(faT, fa.indexOf("متن")) { it.fontWeight == FontWeight.Bold }) { "persian bold not bold" }
    check(hasStyle(faT, fa.indexOf("تاکید")) { it.fontStyle == FontStyle.Italic }) { "persian emphasis not italic" }
    check(hasStyle(faT, fa.indexOf("کد")) { it.fontFamily == FontFamily.Monospace }) { "persian code not monospace" }
    val emoji = "a \uD83D\uDE00 `c` b"
    val emojiT = transform(emoji)
    check(emojiT.text.text == emoji) { "emoji text changed" }
    check(hasStyle(emojiT, emoji.indexOf("c")) { it.fontFamily == FontFamily.Monospace }) { "code after astral char not styled" }

    // CRLF documents: spans stay aligned because both count UTF-16 units.
    val crlf = "# T\r\n\r\n`c`\r\n"
    val crlfT = transform(crlf)
    check(crlfT.text.text == crlf) { "crlf text changed" }
    check(hasStyle(crlfT, crlf.indexOf("c", 4)) { it.fontFamily == FontFamily.Monospace }) { "crlf code not styled" }

    // Recreating with theme/zoom arguments refreshes styles; preserve caller annotations.
    val original = AnnotatedString.Builder("# Heading\n\n[label](url) and `x`").apply {
        addStringAnnotation("caller", "kept", 0, 1)
    }.toAnnotatedString()
    val darkLink = Color(0xFF5E9FE8)
    val darkCode = Color(0xFF2A2E38)
    val dark = MarkdownSourceTransformation(darkLink, darkCode, 30.sp).filter(original)
    check(dark.text.getStringAnnotations("caller", 0, 1).single().item == "kept") { "caller annotation lost" }
    check(hasStyle(dark, 2) { it.fontSize == 45.sp }) { "heading zoom stale" }
    check(hasStyle(dark, original.text.indexOf("label")) { it.color == darkLink }) { "theme link stale" }
    check(backgroundAt(dark, original.text.indexOf("`x`") + 1) == darkCode) { "theme code stale" }
    val plainMatch = transform("plain", TextRange(4, 1), markdown = false)
    check(backgroundAt(plainMatch, 2) != null) { "plain Find missing" }
    check(backgroundAt(plainMatch, 0) == null) { "Find leaked outside active range" }

    for (fixture in listOf("~~~\n**not bold**\n~~~", "```\nunclosed", "a\rb\r`code`", "\\*literal*", "- one\n  - two", "1. one", "title\n=====")) {
        val rendered = transform(fixture)
        check(rendered.text.text == fixture) { "fixture source changed: $fixture" }
        for (offset in 0..fixture.length) {
            check(rendered.offsetMapping.originalToTransformed(offset) == offset) { "fixture offset changed" }
        }
    }
    val literalCode = transform("~~~\n**not bold**\n~~~")
    check(!hasStyle(literalCode, 8) { it.fontWeight == FontWeight.Bold }) { "parsed emphasis inside code" }
    val unfinishedCode = transform("```\nunclosed")
    check(backgroundAt(unfinishedCode, 5) == codeBg) { "unfinished fence not styled" }

    println("ALL BEHAVIOR TESTS PASSED")
}
