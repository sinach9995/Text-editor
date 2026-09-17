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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.StrongEmphasis
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import java.util.ArrayDeque

/**
 * Styles source, never renders/replaces it. CommonMark source spans and Compose
 * offsets both count UTF-16 code units (including CRLF, Persian and emoji).
 * Recreate/remember with ALL constructor arguments as keys, particularly the
 * theme colors and font size. No color-dependent spans are cached globally.
 */
class MarkdownSourceTransformation(
    private val linkColor: Color,
    private val codeBg: Color,
    private val baseFontSize: TextUnit,
    private val markdown: Boolean = true,
    private val activeMatch: TextRange? = null
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        // Copy existing annotations as well as every character verbatim.
        val builder = AnnotatedString.Builder(text)
        if (markdown && text.isNotEmpty()) {
            val pending = ArrayDeque<Node>()
            pending.addLast(sourceParser.parse(text.text))
            while (pending.isNotEmpty()) {
                val node = pending.removeLast()
                val style = when (node) {
                    is Heading -> SpanStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = baseFontSize * when (node.level) {
                            1 -> 1.5f
                            2 -> 1.3f
                            else -> 1.12f
                        }
                    )
                    is StrongEmphasis -> SpanStyle(fontWeight = FontWeight.Bold)
                    is Emphasis, is BlockQuote -> SpanStyle(fontStyle = FontStyle.Italic)
                    is Link -> SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                    is Code, is FencedCodeBlock, is IndentedCodeBlock -> SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontStyle = FontStyle.Normal,
                        fontWeight = FontWeight.Normal,
                        background = codeBg
                    )
                    else -> null
                }
                if (style != null) {
                    for (span in node.sourceSpans) {
                        val start = span.inputIndex.coerceIn(0, text.length)
                        val end = (span.inputIndex + span.length).coerceIn(start, text.length)
                        if (start < end) builder.addStyle(style, start, end)
                    }
                }
                if (node is ListItem) {
                    node.sourceSpans.firstOrNull()?.let { span ->
                        val start = span.inputIndex.coerceIn(0, text.length)
                        val end = (start + span.length).coerceAtMost(text.length)
                        listMarker.find(text.text.substring(start, end))?.let { marker ->
                            builder.addStyle(
                                SpanStyle(color = linkColor, fontWeight = FontWeight.Bold),
                                start + marker.range.first, start + marker.range.last + 1
                            )
                        }
                    }
                }
                // Parent spans precede children so code overrides quote emphasis.
                // Iterative traversal avoids adding recursion for deeply nested input.
                var child = node.lastChild
                while (child != null) {
                    pending.addLast(child)
                    child = child.previous
                }
            }
        }
        // Applied last so the active match remains visible even inside code.
        // Clamping tolerates a stale match during a text/selection update; reversed
        // ranges are valid Compose selections. Plain-text Find works too.
        activeMatch?.let { match ->
            val start = match.min.coerceIn(0, text.length)
            val end = match.max.coerceIn(start, text.length)
            if (start < end) builder.addStyle(
                SpanStyle(background = linkColor.copy(alpha = 0.30f)), start, end
            )
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}

private val sourceParser: Parser = Parser.builder()
    .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
    .build()

private val listMarker = Regex("^ {0,3}(?:[-+*]|[0-9]{1,9}[.)])(?=\\s|$)")
