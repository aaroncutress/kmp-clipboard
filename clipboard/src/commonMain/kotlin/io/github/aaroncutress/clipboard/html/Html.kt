package io.github.aaroncutress.clipboard.html

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration

/**
 * Renders styled text as the HTML that goes on a clipboard.
 *
 * ```kotlin
 * val styled = buildAnnotatedString {
 *     withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Total: ") }
 *     append("£4.20")
 * }
 * clipboard.setAnnotatedString(styled)   // calls this
 * ```
 *
 * ### Why this is written out here
 *
 * `AnnotatedString.fromHtml` and its inverse exist in Compose and throw
 * `UnsupportedOperationException("Compose Multiplatform doesn't support fromHtml")`
 * on every target except Android — they are a thin wrapper over Android's
 * `Html.fromHtml`. There is nothing to delegate to, so this library carries its
 * own.
 *
 * ### What survives
 *
 * Weight, slant, underline, strikethrough, foreground and background colour,
 * absolute font size, and links. Semantic tags where there is one — `<b>`,
 * `<i>`, `<u>`, `<s>`, `<a>` — and inline CSS for the rest, because that is what
 * word processors and browsers both write and both read.
 *
 * Paragraph alignment survives as `<p style="text-align:…">`. Everything else a
 * [ParagraphStyle] can hold — line height, indent, text direction — is dropped:
 * the receiving editor owns its own block layout, and a `line-height` pasted
 * into a document that has its own is noise at best.
 *
 * `FontFamily` is dropped too, and deliberately. A `FontFamily` in Compose can
 * be a bundled resource with no name a stylesheet could refer to, and writing
 * `font-family:sans-serif` for it would be inventing information.
 */
fun AnnotatedString.toHtml(): String {
    if (text.isEmpty()) return ""

    // Style per character, rather than nested spans.
    //
    // Compose's spans may overlap in ways HTML cannot nest — one range covering
    // 0..10 and another 5..15 is legal here and is not expressible as a tag
    // tree. Flattening to a per-character style and then grouping equal runs
    // produces well-formed HTML for any input, at the cost of splitting a span
    // wherever another one starts or stops. Editors do not care, and the
    // alternative is a tag-tree builder that has to re-open spans across
    // boundaries.
    val styles = Array(text.length) { SpanStyle() }
    for (range in spanStyles) {
        for (index in range.start.coerceAtLeast(0) until range.end.coerceAtMost(text.length)) {
            styles[index] = styles[index].merge(range.item)
        }
    }

    val links = arrayOfNulls<String>(text.length)
    for (range in getLinkAnnotations(0, text.length)) {
        val url = (range.item as? LinkAnnotation.Url)?.url ?: continue
        for (index in range.start.coerceAtLeast(0) until range.end.coerceAtMost(text.length)) {
            links[index] = url
        }
    }

    return buildString {
        for (block in blocks()) {
            val align = block.style?.textAlign?.toCss()
            val wrap = align != null || paragraphStyles.size > 1
            if (wrap) {
                append("<p")
                if (align != null) append(""" style="text-align:$align"""")
                append(">")
            }
            appendRuns(this@toHtml.text, styles, links, block.start, block.end)
            if (wrap) append("</p>")
        }
    }
}

/** One paragraph's worth of text, and the style that applies to it. */
private class Block(val start: Int, val end: Int, val style: ParagraphStyle?)

/**
 * The text split into paragraph blocks.
 *
 * `paragraphStyles` is sorted and non-overlapping, but it need not cover
 * everything — text before the first styled paragraph, or between two of them,
 * belongs to no range. Those gaps become unstyled blocks rather than
 * disappearing.
 */
private fun AnnotatedString.blocks(): List<Block> {
    if (paragraphStyles.isEmpty()) return listOf(Block(0, text.length, null))
    val blocks = mutableListOf<Block>()
    var cursor = 0
    for (range in paragraphStyles.sortedBy { it.start }) {
        if (range.start > cursor) blocks += Block(cursor, range.start, null)
        blocks += Block(range.start, range.end.coerceAtMost(text.length), range.item)
        cursor = range.end
    }
    if (cursor < text.length) blocks += Block(cursor, text.length, null)
    return blocks.filter { it.end > it.start }
}

/** Emits `[start, end)` as runs of constant style, each in its own tags. */
private fun StringBuilder.appendRuns(
    text: String,
    styles: Array<SpanStyle>,
    links: Array<String?>,
    start: Int,
    end: Int,
) {
    var index = start
    while (index < end) {
        val style = styles[index]
        val link = links[index]
        var runEnd = index + 1
        while (runEnd < end && styles[runEnd] == style && links[runEnd] == link) runEnd++

        val tags = openingTags(style, link)
        tags.forEach { append(it.open) }
        appendEscaped(text, index, runEnd)
        tags.asReversed().forEach { append(it.close) }

        index = runEnd
    }
}

private class Tag(val open: String, val close: String)

/**
 * The tags that express [style] and [link], outermost first.
 *
 * A semantic tag wherever HTML has one, because `<b>` survives a paste into
 * places a `<span style="font-weight:bold">` does not — plain-text-with-markdown
 * editors and email clients both, and both are common clipboard destinations.
 */
private fun openingTags(style: SpanStyle, link: String?): List<Tag> = buildList {
    if (link != null) add(Tag("""<a href="${escapeAttribute(link)}">""", "</a>"))
    val declarations = Css.declarations(style)
    if (declarations.isNotEmpty()) add(Tag("""<span style="$declarations">""", "</span>"))
    if ((style.fontWeight?.weight ?: 0) >= FontWeight.Bold.weight) add(Tag("<b>", "</b>"))
    if (style.fontStyle == FontStyle.Italic) add(Tag("<i>", "</i>"))
    style.textDecoration?.let { decoration ->
        if (decoration.contains(TextDecoration.Underline)) add(Tag("<u>", "</u>"))
        if (decoration.contains(TextDecoration.LineThrough)) add(Tag("<s>", "</s>"))
    }
}

private fun TextAlign.toCss(): String? = when (this) {
    TextAlign.Left, TextAlign.Start -> "left"
    TextAlign.Right, TextAlign.End -> "right"
    TextAlign.Center -> "center"
    TextAlign.Justify -> "justify"
    else -> null
}

/** Escapes `[start, end)` into HTML text, turning newlines into `<br>`. */
private fun StringBuilder.appendEscaped(text: String, start: Int, end: Int) {
    for (index in start until end) {
        when (val char = text[index]) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '\n' -> append("<br>")
            // A carriage return is a line ending's other half on Windows and
            // means nothing on its own; the `\n` beside it already produced the
            // break.
            '\r' -> Unit
            else -> append(char)
        }
    }
}

private fun escapeAttribute(value: String): String =
    value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
