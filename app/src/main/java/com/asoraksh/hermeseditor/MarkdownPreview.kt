package com.asoraksh.hermeseditor

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import org.commonmark.parser.IncludeSourceSpans
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
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
    .extensions(
        listOf(
            StrikethroughExtension.create(),
            TaskListItemsExtension.create(),
            TablesExtension.create(),
        )
    )
    .includeSourceSpans(IncludeSourceSpans.BLOCKS)
    .build()

internal data class ParsedTable(
    val rows: List<List<TableCell?>>,
    val headerRowCount: Int,
    val colCount: Int,
)

internal fun extractTable(block: TableBlock): ParsedTable {
    val headerRows = mutableListOf<List<TableCell>>()
    val bodyRows = mutableListOf<List<TableCell>>()
    fun collectRows(section: Node, out: MutableList<List<TableCell>>) {
        var row: Node? = section.firstChild
        while (row != null) {
            if (row is TableRow) {
                val cells = mutableListOf<TableCell>()
                var cell: Node? = row.firstChild
                while (cell != null) {
                    if (cell is TableCell) cells.add(cell)
                    cell = cell.next
                }
                if (cells.isNotEmpty()) out.add(cells)
            }
            row = row.next
        }
    }
    var section: Node? = block.firstChild
    while (section != null) {
        when (section) {
            is TableHead -> collectRows(section, headerRows)
            is TableBody -> collectRows(section, bodyRows)
        }
        section = section.next
    }
    val rawRows = headerRows + bodyRows
    val colCount = rawRows.maxOfOrNull { it.size } ?: 0
    if (colCount == 0) return ParsedTable(emptyList(), 0, 0)
    val padded = rawRows.map { row -> List(colCount) { idx -> row.getOrNull(idx) } }
    return ParsedTable(rows = padded, headerRowCount = headerRows.size, colCount = colCount)
}

internal fun parseFirstTable(src: String): ParsedTable? {
    var node: Node? = mdParser.parse(src).firstChild
    while (node != null) {
        if (node is TableBlock) return extractTable(node)
        node = node.next
    }
    return null
}

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
    // Keep list structure in the export: a plain-text copy of a bulleted or
    // task list must still read as a list.
    fun itemPrefix(item: ListItem, ordinal: Int): String {
        findTaskMarker(item)?.let { return "[" + (if (it.isChecked) "x" else " ") + "] " }
        // commonmark 0.30 keeps the marker on the parent list, not on a node
        // inside the item: BulletList.getMarker() and
        // OrderedList.getMarkerStartNumber() + getMarkerDelimiter().
        return when (val list = item.parent) {
            is OrderedList -> {
                val start = list.markerStartNumber ?: 1
                val delim = list.markerDelimiter ?: "."
                (start + ordinal - 1).toString() + delim + " "
            }
            is BulletList -> {
                val m = list.marker
                (if (m.isNullOrEmpty()) "-" else m) + " "
            }
            else -> "- "
        }
    }
    fun blocks(n: Node) {
        var c: Node? = n.firstChild
        var ordinal = 0
        while (c != null) {
            val cur = c
            when (cur) {
                is Paragraph, is Heading -> { inline(cur); sb.append("\n\n") }
                is ListItem -> {
                    sb.append("  ".repeat(generateSequence(cur.parent) { it.parent }.count { it is ListItem }))
                    sb.append(itemPrefix(cur, ++ordinal))
                    var part: Node? = cur.firstChild
                    while (part != null) {
                        // A nested list starts on its own line instead of running into the parent text.
                        if (part is BulletList || part is OrderedList) {
                            if (!sb.endsWith("\n")) sb.append('\n')
                            blocks(part)
                        } else inline(part)
                        part = part.next
                    }
                    if (!sb.endsWith("\n")) sb.append('\n')
                }
                is BulletList, is OrderedList -> {
                    blocks(cur)
                    if (cur.parent !is ListItem) sb.append('\n')
                }
                is FencedCodeBlock -> sb.append((cur.literal ?: "").trim()).append("\n\n")
                is IndentedCodeBlock -> sb.append((cur.literal ?: "").trim()).append("\n\n")
                is TableBlock -> { blocks(cur); sb.append('\n') }
                is TableRow -> {
                    var cell: Node? = cur.firstChild
                    var first = true
                    while (cell != null) {
                        if (cell is TableCell) {
                            if (!first) sb.append(" | ")
                            inline(cell)
                            first = false
                        }
                        cell = cell.next
                    }
                    sb.append('\n')
                }
                is TaskListItemMarker -> { }
                else -> blocks(cur)
            }
            c = cur.next
        }
    }
    blocks(mdParser.parse(src))
    return sb.toString().trim()
}

private val LocalPreviewTextAnchors = staticCompositionLocalOf<PreviewTextAnchors?> { null }

// Keep measured layout outside snapshot state: scrolling must not recompose text.
private class MeasuredPreviewText { var layout: TextLayoutResult? = null }

@Composable
fun MarkdownPreview(markdown: String, onLinkClick: (String) -> Unit, modifier: Modifier = Modifier, fontSize: Float = 17f, scrollState: ScrollState = rememberScrollState(), anchors: PreviewAnchors = remember { PreviewAnchors() }, textAnchors: PreviewTextAnchors = remember { PreviewTextAnchors() }, pinchScale: Float = 1f, pinchCentroid: Offset = Offset.Zero) {
    // Scale document typography only, not padding, controls or application chrome.
    val scale = if (fontSize.isFinite() && fontSize > 0f) fontSize / 17f else 1f
    if (markdown.isBlank()) {
        Box(modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(stringResource(R.string.preview_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp * scale)
        }
    } else {
        val document = remember(markdown) { mdParser.parse(markdown) }
        CompositionLocalProvider(LocalPreviewTextAnchors provides textAnchors) {
        var contentHeight by remember { mutableStateOf(1) }
        Column(modifier.clipToBounds().onGloballyPositioned { textAnchors.viewport = it }
            .verticalScroll(scrollState)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .onSizeChanged { contentHeight = it.height.coerceAtLeast(1) }
                    .graphicsLayer {
                        // Like source editing, draw a smooth temporary scale
                        // around the gesture point and defer text reflow until
                        // gesture end.
                        val pivotX = ((pinchCentroid.x - 20.dp.toPx()) / size.width.coerceAtLeast(1f))
                            .coerceIn(0f, 1f)
                        val pivotY = ((scrollState.value + pinchCentroid.y - 16.dp.toPx()) / contentHeight)
                            .coerceIn(0f, 1f)
                        scaleX = pinchScale
                        scaleY = pinchScale
                        transformOrigin = TransformOrigin(pivotX, pivotY)
                    }
            ) {
            var child = document.firstChild
            while (child != null) {
                val block = child
                val start = block.sourceSpans.firstOrNull()?.inputIndex ?: 0
                val end = block.sourceSpans.lastOrNull()?.let { it.inputIndex + it.length } ?: start
                Column(Modifier.fillMaxWidth().onGloballyPositioned {
                    anchors.update(PreviewBlock(start, end, it.positionInParent().y, it.size.height.toFloat()))
                }) { RenderBlock(block, 0, onLinkClick, scale) }
                child = block.next
            }
        }
        }
        }
    }
}

@Composable
private fun RenderBlocks(parent: Node, indent: Int, onLinkClick: (String) -> Unit, scale: Float) {
    var child: Node? = parent.firstChild
    while (child != null) {
        val current = child
        RenderBlock(current, indent, onLinkClick, scale)
        child = current.next
    }
}

private data class HeadingStyle(val size: TextUnit, val weight: FontWeight, val topPad: Dp)

@Composable
private fun RenderBlock(node: Node, indent: Int, onLinkClick: (String) -> Unit, scale: Float) {
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
                style = TextStyle(fontSize = style.size * scale, fontWeight = style.weight, lineHeight = style.size * 1.25f * scale, color = bodyColor),
                onLinkClick = onLinkClick,
                scale = scale
            )
            Spacer(Modifier.height(6.dp))
        }
        is Paragraph -> {
            LinkedText(
                node = node,
                style = TextStyle(fontSize = 16.sp * scale, lineHeight = 24.sp * scale, color = bodyColor),
                onLinkClick = onLinkClick,
                scale = scale
            )
            Spacer(Modifier.height(8.dp))
        }
        is FencedCodeBlock -> CodeBlock(node, node.literal ?: "", scale)
        is IndentedCodeBlock -> CodeBlock(node, node.literal ?: "", scale)
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
                Column(Modifier.weight(1f)) { RenderBlocks(node, indent, onLinkClick, scale) }
            }
            Spacer(Modifier.height(8.dp))
        }
        is BulletList -> {
            var item: Node? = node.firstChild
            while (item != null) {
                val current = item
                if (current is ListItem) {
                    RenderListItem(current, indent, onLinkClick, scale) {
                        val marker = findTaskMarker(current)
                        if (marker != null) {
                            Checkbox(checked = marker.isChecked, onCheckedChange = null, enabled = false, modifier = Modifier.size(22.dp))
                        } else {
                            Text("•", fontSize = 16.sp * scale, color = bodyColor)
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
                    RenderListItem(current, indent, onLinkClick, scale) {
                        val marker = findTaskMarker(current)
                        if (marker != null) {
                            Checkbox(checked = marker.isChecked, onCheckedChange = null, enabled = false, modifier = Modifier.size(22.dp))
                        } else {
                            Text("$n.", fontSize = 16.sp * scale, color = bodyColor)
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
        is TableBlock -> MarkdownTable(node, onLinkClick, scale)
        else -> {
            // HtmlBlock and anything unknown: render children only, never raw HTML.
            RenderBlocks(node, indent, onLinkClick, scale)
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
private fun RenderListItem(item: ListItem, indent: Int, onLinkClick: (String) -> Unit, scale: Float, marker: @Composable () -> Unit) {
    Row(Modifier.padding(start = (indent * 16).dp).padding(vertical = 3.dp)) {
        Box(Modifier.width((30f * scale.coerceAtLeast(1f)).dp), contentAlignment = Alignment.TopStart) { marker() }
        Column(Modifier.weight(1f)) {
            var child: Node? = item.firstChild
            while (child != null) {
                val current = child
                if (current !is TaskListItemMarker) RenderBlock(current, indent + 1, onLinkClick, scale)
                child = current.next
            }
        }
    }
}

@Composable
private fun CodeBlock(node: Node, code: String, scale: Float) {
    val anchors = LocalPreviewTextAnchors.current
    val measured = remember(node) { MeasuredPreviewText() }
    DisposableEffect(node, anchors) { onDispose { anchors?.remove(node) } }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = code.trimEnd('\n'),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp * scale,
            lineHeight = 20.sp * scale,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            onTextLayout = { measured.layout = it },
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)
                .onGloballyPositioned { coordinates ->
                    measured.layout?.let { anchors?.update(node, it, coordinates) }
                }
        )
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun MarkdownTable(block: TableBlock, onLinkClick: (String) -> Unit, scale: Float) {
    val table = remember(block) { extractTable(block) }
    if (table.rows.isEmpty() || table.colCount == 0) return
    val headerBg = MaterialTheme.colorScheme.surfaceVariant
    val surfaceBg = MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val bodyColor = MaterialTheme.colorScheme.onBackground
    val textDir = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextDirection.Rtl else TextDirection.Ltr
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minTableWidth = maxWidth
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = surfaceBg,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier.fillMaxWidth()
        ) {
            Layout(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                content = {
                    repeat(table.headerRowCount) {
                        Box(Modifier.background(headerBg))
                    }
                    repeat((table.rows.size - 1).coerceAtLeast(0)) {
                        Box(Modifier.background(borderColor))
                    }
                    for (r in table.rows.indices) {
                        val isHeaderRow = r < table.headerRowCount
                        for (c in 0 until table.colCount) {
                            val cell = table.rows[r][c]
                            if (cell == null) {
                                Box(Modifier.size(0.dp))
                            } else {
                                val align = when (cell.alignment) {
                                    TableCell.Alignment.LEFT -> TextAlign.Left
                                    TableCell.Alignment.CENTER -> TextAlign.Center
                                    TableCell.Alignment.RIGHT -> TextAlign.Right
                                    null -> TextAlign.Start
                                }
                                Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                    LinkedText(
                                        node = cell,
                                        style = TextStyle(
                                            fontSize = 15.sp * scale,
                                            lineHeight = 22.sp * scale,
                                            fontWeight = if (isHeaderRow || cell.isHeader) FontWeight.SemiBold else FontWeight.Normal,
                                            color = bodyColor,
                                            textAlign = align,
                                            textDirection = textDir,
                                        ),
                                        onLinkClick = onLinkClick,
                                        scale = scale,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            ) { measurables, _ ->
                val rowCount = table.rows.size
                val colCount = table.colCount
                val headerCount = table.headerRowCount
                val dividerCount = (rowCount - 1).coerceAtLeast(0)
                val dividerOffset = headerCount
                val cellOffset = headerCount + dividerCount

                val minColPx = 72.dp.roundToPx()
                val maxColPx = (240f * scale.coerceAtLeast(1f)).dp.roundToPx()
                val minTablePx = minTableWidth.roundToPx()
                val dividerPx = 1.dp.roundToPx().coerceAtLeast(1)

                val colWidths = IntArray(colCount) { c ->
                    var maxW = minColPx
                    for (r in 0 until rowCount) {
                        val w = measurables[cellOffset + r * colCount + c].maxIntrinsicWidth(Int.MAX_VALUE)
                        if (w > maxW) maxW = w
                    }
                    maxW.coerceIn(minColPx, maxColPx)
                }
                val naturalWidth = colWidths.sum()
                if (naturalWidth < minTablePx) {
                    val extra = minTablePx - naturalWidth
                    val perCol = extra / colCount
                    for (c in 0 until colCount) colWidths[c] += perCol
                    colWidths[colCount - 1] += extra - perCol * colCount
                }
                val tableWidth = colWidths.sum()

                val cellPlaceables = Array(rowCount * colCount) { idx ->
                    val c = idx % colCount
                    measurables[cellOffset + idx].measure(Constraints(minWidth = colWidths[c], maxWidth = colWidths[c]))
                }
                val rowHeights = IntArray(rowCount) { r ->
                    (0 until colCount).maxOf { c -> cellPlaceables[r * colCount + c].height }.coerceAtLeast(1)
                }
                val headerPlaceables = Array(headerCount) { h ->
                    measurables[h].measure(Constraints.fixed(tableWidth, rowHeights[h]))
                }
                val dividerPlaceables = Array(dividerCount) { d ->
                    measurables[dividerOffset + d].measure(Constraints.fixed(tableWidth, dividerPx))
                }
                val tableHeight = rowHeights.sum() + dividerCount * dividerPx

                layout(tableWidth, tableHeight) {
                    var headerY = 0
                    for (h in 0 until headerCount) {
                        headerPlaceables[h].placeRelative(0, headerY)
                        headerY += rowHeights[h] + dividerPx
                    }
                    var y = 0
                    for (r in 0 until rowCount) {
                        var x = 0
                        for (c in 0 until colCount) {
                            cellPlaceables[r * colCount + c].placeRelative(x, y)
                            x += colWidths[c]
                        }
                        y += rowHeights[r]
                        if (r < rowCount - 1) {
                            dividerPlaceables[r].placeRelative(0, y)
                            y += dividerPx
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun LinkedText(node: Node, style: TextStyle, onLinkClick: (String) -> Unit, scale: Float, modifier: Modifier = Modifier) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val annotated = remember(node, linkColor, codeBg, scale) { inlineAnnotated(node, linkColor, codeBg, scale) }
    val anchors = LocalPreviewTextAnchors.current
    val measured = remember(node) { MeasuredPreviewText() }
    DisposableEffect(node, anchors) { onDispose { anchors?.remove(node) } }
    ClickableText(
        text = annotated,
        onTextLayout = { measured.layout = it },
        modifier = modifier.onGloballyPositioned { coordinates ->
            measured.layout?.let { anchors?.update(node, it, coordinates) }
        },
        style = style,
        onClick = { offset ->
            annotated.getStringAnnotations("md-url", offset, offset).firstOrNull()?.let { onLinkClick(it.item) }
        }
    )
}

private fun inlineAnnotated(node: Node, linkColor: Color, codeBg: Color, scale: Float): AnnotatedString {
    val builder = AnnotatedString.Builder()
    appendInline(node, builder, linkColor, codeBg, scale)
    return builder.toAnnotatedString()
}

private fun appendInline(node: Node, b: AnnotatedString.Builder, linkColor: Color, codeBg: Color, scale: Float) {
    var child: Node? = node.firstChild
    while (child != null) {
        val current = child
        when (current) {
            is Text -> b.append(current.literal)
            is Emphasis -> span(b, SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(current, b, linkColor, codeBg, scale) }
            is StrongEmphasis -> span(b, SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(current, b, linkColor, codeBg, scale) }
            is Strikethrough -> span(b, SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(current, b, linkColor, codeBg, scale) }
            is Code -> span(b, SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 15.sp * scale)) { b.append(current.literal) }
            is Link -> {
                val url = current.destination
                val start = b.length
                span(b, SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    appendInline(current, b, linkColor, codeBg, scale)
                }
                b.addStringAnnotation("md-url", url, start, b.length)
            }
            is Image -> {
                b.append("[")
                appendInline(current, b, linkColor, codeBg, scale)
                b.append("]")
            }
            is SoftLineBreak -> b.append(" ")
            is HardLineBreak -> b.append("\n")
            else -> appendInline(current, b, linkColor, codeBg, scale)
        }
        child = current.next
    }
}

private inline fun span(b: AnnotatedString.Builder, style: SpanStyle, block: () -> Unit) {
    val start = b.length
    block()
    b.addStyle(style, start, b.length)
}
