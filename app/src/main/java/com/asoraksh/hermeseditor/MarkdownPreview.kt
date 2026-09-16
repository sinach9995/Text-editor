package com.asoraksh.hermeseditor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

// Local-only Markdown rendering. Parsing happens on-device with commonmark-java
// (BSD-2-Clause, no network, no tracking). No WebView, no remote content.

private val mdParser: Parser = Parser.builder()
    .extensions(listOf(StrikethroughExtension.create(), TaskListItemsExtension.create()))
    .build()

// Local Markdown-to-plain-text export. Drops formatting syntax, keeps readable
// text, paragraph breaks, list text, link labels and code content. Source untouched.
fun markdownToPlainText(src: String): String {
    if (src.isBlank()) return ""
    val sb = StringBuilder()
    fun inline(n: Node) {
        var c: Node? = n.firstChild
        while (c != null) {
            val cur = c
            when (cur) {
                is Text -> sb.append(cur.literal)
                is Code -> sb.append(cur.literal)
                is Emphasis, is StrongEmphasis, is Strikethrough, is Link -> inline(cur)
                is Image -> inline(cur)
                is SoftLineBreak -> sb.append(' ')
                is HardLineBreak -> sb.append('\n')
                else -> inline(cur)
            }
            c = cur.next
        }
    }
    fun blocks(n: Node) {
        var c: Node? = n.firstChild
        while (c != null) {
            val cur = c
            when (cur) {
                is Paragraph, is Heading -> { inline(cur); sb.append("\n\n") }
                is ListItem -> { inline(cur); sb.append('\n') }
                is FencedCodeBlock -> sb.append((cur.literal ?: "").trim()).append("\n\n")
                is IndentedCodeBlock -> sb.append((cur.literal ?: "").trim()).append("\n\n")
                is TaskListItemMarker -> { }
                else -> blocks(cur)
            }
            c = cur.next
        }
    }
    blocks(mdParser.parse(src))
    return sb.toString().trim()
}

@Composable
fun MarkdownPreview(markdown: String, onLinkClick: (String) -> Unit, modifier: Modifier = Modifier) {
    if (markdown.isBlank()) {
        Box(modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("Nothing to preview.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
        }
    } else {
        val document = remember(markdown) { mdParser.parse(markdown) }
        Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
            RenderBlocks(document, 0, onLinkClick)
        }
    }
}

@Composable
private fun RenderBlocks(parent: Node, indent: Int, onLinkClick: (String) -> Unit) {
    var child: Node? = parent.firstChild
    while (child != null) {
        val current = child
        RenderBlock(current, indent, onLinkClick)
        child = current.next
    }
}

private data class HeadingStyle(val size: TextUnit, val weight: FontWeight, val topPad: Dp)

@Composable
private fun RenderBlock(node: Node, indent: Int, onLinkClick: (String) -> Unit) {
    val bodyColor = MaterialTheme.colorScheme.onBackground
    when (node) {
        is Heading -> {
            val style = when (node.level) {
                1 -> HeadingStyle(26.sp, FontWeight.Bold, 12.dp)
                2 -> HeadingStyle(22.sp, FontWeight.Bold, 10.dp)
                else -> HeadingStyle(19.sp, FontWeight.Bold, 8.dp)
            }
            Spacer(Modifier.height(style.topPad))
            LinkedText(
                node = node,
                style = TextStyle(fontSize = style.size, fontWeight = style.weight, lineHeight = style.size * 1.25f, color = bodyColor),
                onLinkClick = onLinkClick
            )
            Spacer(Modifier.height(6.dp))
        }
        is Paragraph -> {
            LinkedText(
                node = node,
                style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = bodyColor),
                onLinkClick = onLinkClick
            )
            Spacer(Modifier.height(8.dp))
        }
        is FencedCodeBlock -> CodeBlock(node.literal ?: "")
        is IndentedCodeBlock -> CodeBlock(node.literal ?: "")
        is BlockQuote -> {
            Row(Modifier.padding(vertical = 4.dp).height(IntrinsicSize.Min)) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { RenderBlocks(node, indent, onLinkClick) }
            }
            Spacer(Modifier.height(8.dp))
        }
        is BulletList -> {
            var item: Node? = node.firstChild
            while (item != null) {
                val current = item
                if (current is ListItem) {
                    RenderListItem(current, indent, onLinkClick) {
                        val marker = findTaskMarker(current)
                        if (marker != null) {
                            Checkbox(checked = marker.isChecked, onCheckedChange = null, enabled = false, modifier = Modifier.size(22.dp))
                        } else {
                            Text("•", fontSize = 16.sp, color = bodyColor)
                        }
                    }
                }
                item = current.next
            }
            Spacer(Modifier.height(6.dp))
        }
        is OrderedList -> {
            var number = node.startNumber
            var item: Node? = node.firstChild
            while (item != null) {
                val current = item
                if (current is ListItem) {
                    val n = number
                    RenderListItem(current, indent, onLinkClick) {
                        val marker = findTaskMarker(current)
                        if (marker != null) {
                            Checkbox(checked = marker.isChecked, onCheckedChange = null, enabled = false, modifier = Modifier.size(22.dp))
                        } else {
                            Text("$n.", fontSize = 16.sp, color = bodyColor)
                        }
                    }
                    number++
                }
                item = current.next
            }
            Spacer(Modifier.height(6.dp))
        }
        is ThematicBreak -> {
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(6.dp))
        }
        else -> {
            // HtmlBlock and anything unknown: render children only, never raw HTML.
            RenderBlocks(node, indent, onLinkClick)
        }
    }
}

private fun findTaskMarker(item: ListItem): TaskListItemMarker? {
    var child: Node? = item.firstChild
    while (child != null) {
        val current = child
        if (current is TaskListItemMarker) return current
        if (current is Paragraph) {
            var inline: Node? = current.firstChild
            while (inline != null) {
                val inner = inline
                if (inner is TaskListItemMarker) return inner
                inline = inner.next
            }
        }
        child = current.next
    }
    return null
}

@Composable
private fun RenderListItem(item: ListItem, indent: Int, onLinkClick: (String) -> Unit, marker: @Composable () -> Unit) {
    Row(Modifier.padding(start = (indent * 16).dp).padding(vertical = 3.dp)) {
        Box(Modifier.width(30.dp), contentAlignment = Alignment.TopStart) { marker() }
        Column(Modifier.weight(1f)) {
            var child: Node? = item.firstChild
            while (child != null) {
                val current = child
                if (current !is TaskListItemMarker) RenderBlock(current, indent + 1, onLinkClick)
                child = current.next
            }
        }
    }
}

@Composable
private fun CodeBlock(code: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = code.trimEnd('\n'),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun LinkedText(node: Node, style: TextStyle, onLinkClick: (String) -> Unit) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val annotated = remember(node) { inlineAnnotated(node, linkColor, codeBg) }
    ClickableText(
        text = annotated,
        style = style,
        onClick = { offset ->
            annotated.getStringAnnotations("md-url", offset, offset).firstOrNull()?.let { onLinkClick(it.item) }
        }
    )
}

private fun inlineAnnotated(node: Node, linkColor: Color, codeBg: Color): AnnotatedString {
    val builder = AnnotatedString.Builder()
    appendInline(node, builder, linkColor, codeBg)
    return builder.toAnnotatedString()
}

private fun appendInline(node: Node, b: AnnotatedString.Builder, linkColor: Color, codeBg: Color) {
    var child: Node? = node.firstChild
    while (child != null) {
        val current = child
        when (current) {
            is Text -> b.append(current.literal)
            is Emphasis -> span(b, SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(current, b, linkColor, codeBg) }
            is StrongEmphasis -> span(b, SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(current, b, linkColor, codeBg) }
            is Strikethrough -> span(b, SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(current, b, linkColor, codeBg) }
            is Code -> span(b, SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 15.sp)) { b.append(current.literal) }
            is Link -> {
                val url = current.destination
                val start = b.length
                span(b, SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    appendInline(current, b, linkColor, codeBg)
                }
                b.addStringAnnotation("md-url", url, start, b.length)
            }
            is Image -> {
                b.append("[")
                appendInline(current, b, linkColor, codeBg)
                b.append("]")
            }
            is SoftLineBreak -> b.append(" ")
            is HardLineBreak -> b.append("\n")
            else -> appendInline(current, b, linkColor, codeBg)
        }
        child = current.next
    }
}

private inline fun span(b: AnnotatedString.Builder, style: SpanStyle, block: () -> Unit) {
    val start = b.length
    block()
    b.addStyle(style, start, b.length)
}
