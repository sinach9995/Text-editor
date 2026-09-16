package com.asoraksh.hermeseditor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser

// Local-only Markdown rendering. Parsing happens on-device with commonmark-java
// (BSD-2-Clause, no network, no tracking). No WebView, no remote content.

private val mdParser: Parser = Parser.builder()
    .extensions(listOf(StrikethroughExtension.create(), TaskListItemsExtension.create()))
    .build()

@Composable
fun MarkdownPreview(markdown: String, onLinkClick: (String) -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = remember {
        object : UriHandler {
            override fun openUri(uri: String) = onLinkClick(uri)
        }
    }
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        if (markdown.isBlank()) {
            Box(modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text("Nothing to preview.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
            }
        } else {
            val document = remember(markdown) { mdParser.parse(markdown) }
            Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
                RenderBlocks(document, 0)
            }
        }
    }
}

@Composable
private fun RenderBlocks(parent: Node, indent: Int) {
    var child = parent.firstChild
    while (child != null) {
        RenderBlock(child, indent)
        child = child.next
    }
}

@Composable
private fun RenderBlock(node: Node, indent: Int) {
    when (node) {
        is Heading -> {
            val style = when (node.level) {
                1 -> Triple(26.sp, FontWeight.Bold, 12.dp)
                2 -> Triple(22.sp, FontWeight.Bold, 10.dp)
                else -> Triple(19.sp, FontWeight.Bold, 8.dp)
            }
            Spacer(Modifier.height(style.third))
            Text(
                text = inlineAnnotated(node),
                fontSize = style.first,
                fontWeight = style.second,
                lineHeight = style.first * 1.25f,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
        }
        is Paragraph -> {
            Text(
                text = inlineAnnotated(node),
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onBackground
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
                Column(Modifier.weight(1f)) { RenderBlocks(node, indent) }
            }
            Spacer(Modifier.height(8.dp))
        }
        is BulletList -> {
            var item = node.firstChild
            while (item != null) {
                if (item is ListItem) {
                    val marker = findTaskMarker(item)
                    RenderListItem(item, indent) {
                        if (marker != null) {
                            Checkbox(checked = marker.isChecked, onCheckedChange = null, enabled = false, modifier = Modifier.size(22.dp))
                        } else {
                            Text("•", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                }
                item = item.next
            }
            Spacer(Modifier.height(6.dp))
        }
        is OrderedList -> {
            var number = node.startNumber
            var item = node.firstChild
            while (item != null) {
                if (item is ListItem) {
                    val n = number
                    val marker = findTaskMarker(item)
                    RenderListItem(item, indent) {
                        if (marker != null) {
                            Checkbox(checked = marker.isChecked, onCheckedChange = null, enabled = false, modifier = Modifier.size(22.dp))
                        } else {
                            Text("$n.", fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                    number++
                }
                item = item.next
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
            RenderBlocks(node, indent)
        }
    }
}

private fun findTaskMarker(item: ListItem): TaskListItemMarker? {
    var child = item.firstChild
    while (child != null) {
        if (child is TaskListItemMarker) return child
        if (child is Paragraph) {
            var inline = child.firstChild
            while (inline != null) {
                if (inline is TaskListItemMarker) return inline
                inline = inline.next
            }
        }
        child = child.next
    }
    return null
}

@Composable
private fun RenderListItem(item: ListItem, indent: Int, marker: @Composable () -> Unit) {
    Row(Modifier.padding(start = (indent * 16).dp).padding(vertical = 3.dp)) {
        Box(Modifier.width(30.dp), contentAlignment = Alignment.TopStart) { marker() }
        Column(Modifier.weight(1f)) {
            var child = item.firstChild
            while (child != null) {
                if (child !is TaskListItemMarker) RenderBlock(child, indent + 1)
                child = child.next
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
private fun inlineAnnotated(node: Node): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val builder = AnnotatedString.Builder()
    appendInline(node, builder, linkColor, codeBg)
    return builder.toAnnotatedString()
}

private fun appendInline(node: Node, b: AnnotatedString.Builder, linkColor: androidx.compose.ui.graphics.Color, codeBg: androidx.compose.ui.graphics.Color) {
    var child = node.firstChild
    while (child != null) {
        when (child) {
            is Text -> b.append(child.literal)
            is Emphasis -> b.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(child, b, linkColor, codeBg) }
            is StrongEmphasis -> b.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(child, b, linkColor, codeBg) }
            is Strikethrough -> b.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(child, b, linkColor, codeBg) }
            is Code -> b.withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 15.sp)) { b.append(child.literal) }
            is Link -> {
                val url = child.destination
                b.withLink(LinkAnnotation.Url(url)) {
                    b.withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        appendInline(child, b, linkColor, codeBg)
                    }
                }
            }
            is Image -> {
                b.append("[")
                appendInline(child, b, linkColor, codeBg)
                b.append("]")
            }
            is SoftLineBreak -> b.append(" ")
            is HardLineBreak -> b.append("\n")
            else -> appendInline(child, b, linkColor, codeBg)
        }
        child = child.next
    }
}
