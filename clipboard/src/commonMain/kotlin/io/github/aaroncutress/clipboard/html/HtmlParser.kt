package io.github.aaroncutress.clipboard.html

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Reads clipboard HTML into styled text.
 *
 * ```kotlin
 * val styled = parseHtml(clipboard.getHtml().orEmpty())
 * ```
 *
 * ### What it handles
 *
 * `b`, `strong`, `i`, `em`, `u`, `ins`, `s`, `strike`, `del`, `mark`, `code`,
 * `kbd`, `samp`, `tt`, `pre`, `sub`, `sup`, `small`, `big`, `a`, `span`, `font`,
 * `br`, `p`, `div`, `h1`–`h6`, `blockquote`, `ul`, `ol`, `li`, `table`, `tr`,
 * `td`, `th`, and inline `style` attributes in the dialect [Css] documents.
 *
 * Unknown tags are dropped and **their text is kept** — which is the behaviour
 * that matters, because the HTML on a clipboard is whatever the source
 * application felt like emitting. Word writes `<o:p>`, Google Docs writes nested
 * `<span id="docs-internal-guid-…">`, and a parser that gave up on either would
 * be useless for the two most common sources of formatted text in existence.
 * `<script>` and `<style>` are the exception: their contents are code, not text,
 * and are discarded.
 *
 * Whitespace collapses the way HTML says it does — runs become one space, and
 * space around a block boundary disappears — except inside `<pre>`, where it is
 * kept verbatim.
 *
 * ### What it does not
 *
 * No CSS cascade: a `<style>` block at the top of a document is ignored, and
 * only `style` attributes on the elements themselves are read. Implementing the
 * cascade means implementing selector matching, and clipboard HTML from every
 * major application carries its formatting inline precisely because it cannot
 * rely on a stylesheet travelling with it.
 *
 * No images. An `<img>` becomes its `alt` text, or nothing.
 *
 * @param html The HTML to read. May be a whole document or a fragment; may carry
 *   the Windows `CF_HTML` fragment markers, which are honoured.
 */
fun parseHtml(html: String): AnnotatedString = HtmlParser(html).parse()

/** One open element, and what it contributes. */
private class Frame(
    val tag: String,
    val style: SpanStyle,
    val link: String?,
    /** Set on `<ol>`; counts its `<li>` children. */
    var listIndex: Int = 0,
    val ordered: Boolean = false,
)

private class HtmlParser(source: String) {

    // Windows puts a `Version:1.0`/`StartHTML:…` header in front of clipboard
    // HTML and marks the interesting part with comments. AWT hands the whole
    // thing over on some JDKs and only the fragment on others, so both shapes
    // arrive here and this is the cheapest way to be right about either.
    private val html: String = source
        .substringAfter("<!--StartFragment-->", source)
        .substringBefore("<!--EndFragment-->")

    private val builder = AnnotatedString.Builder()
    private val stack = mutableListOf<Frame>()

    /** True when nothing has been emitted since the last block boundary. */
    private var atBlockStart = true

    /** Set by a block tag; turned into newlines only if more text follows. */
    private var pendingBreaks = 0

    /**
     * Set by whitespace; turned into one space only if more text follows.
     *
     * Deferred for the same reason [pendingBreaks] is. HTML collapses a run of
     * whitespace to a single space *and* drops it at the edge of a block, and
     * whether a given space is at an edge is not knowable until the next thing
     * arrives. Emitting eagerly gives `before <script>…</script> after` two
     * spaces — the text nodes either side of the discarded element each keep
     * their own — and leaves a trailing space on every document that ends in
     * one.
     */
    private var pendingSpace = false

    /** Depth of `<pre>`, `<script>` and `<style>`, which change how text is read. */
    private var preformatted = 0
    private var discarding = 0

    fun parse(): AnnotatedString {
        var index = 0
        while (index < html.length) {
            val char = html[index]
            if (char != '<') {
                val textEnd = html.indexOf('<', index).takeIf { it >= 0 } ?: html.length
                appendText(html.substring(index, textEnd))
                index = textEnd
                continue
            }
            // A `<` that does not begin a tag is text. `a < b` in a document
            // written by hand is common enough to be worth not mangling.
            val tagEnd = html.indexOf('>', index)
            if (tagEnd < 0) {
                appendText(html.substring(index))
                break
            }
            val raw = html.substring(index + 1, tagEnd).trim()
            index = tagEnd + 1
            when {
                raw.startsWith("!--") -> Unit
                raw.startsWith("!") || raw.startsWith("?") -> Unit
                raw.startsWith("/") -> close(raw.drop(1).trim().lowercase())
                else -> open(raw)
            }
        }
        return builder.toAnnotatedString()
    }

    private fun open(raw: String) {
        val name = raw.takeWhile { !it.isWhitespace() && it != '/' }.lowercase()
        val attributes = Attributes(raw.drop(name.length))
        val selfClosing = raw.endsWith("/")

        when (name) {
            "script", "style" -> {
                discarding++
                return
            }
            "br" -> {
                newline()
                return
            }
            "hr" -> {
                breakBlock(1)
                return
            }
            "img" -> {
                attributes["alt"]?.takeIf { it.isNotEmpty() }?.let { appendText(it) }
                return
            }
            "pre" -> preformatted++
        }

        if (name in BLOCK_TAGS) breakBlock(if (name == "p" || name == "blockquote") 2 else 1)

        if (name == "li") {
            val list = stack.lastOrNull { it.tag == "ol" || it.tag == "ul" }
            if (list != null && list.ordered) {
                list.listIndex++
                emit("${list.listIndex}. ")
            } else {
                emit("• ")
            }
        }

        val parent = stack.lastOrNull()
        val frame = Frame(
            tag = name,
            style = (parent?.style ?: SpanStyle()).merge(styleFor(name, attributes)),
            link = attributes["href"]?.takeIf { name == "a" } ?: parent?.link,
            ordered = name == "ol",
        )
        // A void element contributes nothing to nest inside; pushing it would
        // leave a frame that never gets popped, and every style after it would
        // inherit from a tag that closed at the same character it opened.
        if (!selfClosing && name !in VOID_TAGS) stack += frame
    }

    private fun close(name: String) {
        when (name) {
            "script", "style" -> {
                if (discarding > 0) discarding--
                return
            }
            "pre" -> if (preformatted > 0) preformatted--
        }
        // Popped by name, not blindly, so that an unclosed `<b>` inside a `<p>`
        // does not swallow the paragraph's close. Unmatched closes are ignored,
        // which is what browsers do.
        val at = stack.indexOfLast { it.tag == name }
        if (at >= 0) {
            while (stack.size > at) stack.removeAt(stack.lastIndex)
        }
        if (name in BLOCK_TAGS) breakBlock(if (name == "p" || name == "blockquote") 2 else 1)
    }

    private fun appendText(raw: String) {
        if (discarding > 0 || raw.isEmpty()) return
        val text = decodeEntities(raw)
        if (preformatted > 0) {
            emit(text)
            return
        }
        // HTML whitespace collapsing: any run of whitespace becomes one space,
        // and a space at the edge of a block is dropped. The edges are only
        // knowable in retrospect, so the space is recorded and emitted later —
        // see [pendingSpace].
        val collapsed = text.replace(WHITESPACE, " ")
        if (collapsed.isBlank()) {
            // Whitespace between two elements is a word separator and nothing
            // else. `<b>one</b> <b>two</b>` is two words.
            if (!atBlockStart && pendingBreaks == 0) pendingSpace = true
            return
        }
        if (collapsed.startsWith(' ') && !atBlockStart && pendingBreaks == 0) pendingSpace = true
        val trailing = collapsed.endsWith(' ')
        emit(collapsed.trim())
        pendingSpace = trailing
    }

    /** Appends text, flushing whatever was waiting to see whether more text came. */
    private fun emit(text: String) {
        if (pendingBreaks > 0 && !atBlockStart) {
            builder.append("\n".repeat(pendingBreaks))
            // A newline is already a separator; a space in front of it would be
            // trailing whitespace on the line above.
            pendingSpace = false
        }
        pendingBreaks = 0
        if (pendingSpace) {
            if (!atBlockStart) builder.append(" ")
            pendingSpace = false
        }
        atBlockStart = false

        val frame = stack.lastOrNull()
        val start = builder.length
        builder.append(text)
        val style = frame?.style ?: SpanStyle()
        if (style != SpanStyle()) builder.addStyle(style, start, builder.length)
        frame?.link?.let { builder.addLink(LinkAnnotation.Url(it), start, builder.length) }
    }

    private fun newline() {
        if (atBlockStart && pendingBreaks == 0) return
        builder.append("\n")
        pendingBreaks = 0
        pendingSpace = false
    }

    /**
     * Marks a block boundary worth [count] newlines.
     *
     * Deferred rather than written immediately, because `</p></div></body>` is
     * three boundaries and one paragraph break, and because a document that ends
     * in a closing tag should not end in a blank line. The largest pending count
     * wins: a `</p>` inside a `</div>` is still one paragraph gap.
     */
    private fun breakBlock(count: Int) {
        if (atBlockStart) return
        pendingSpace = false
        pendingBreaks = maxOf(pendingBreaks, count)
    }

    private fun styleFor(tag: String, attributes: Attributes): SpanStyle {
        val base = when (tag) {
            "b", "strong", "th" -> SpanStyle(fontWeight = FontWeight.Bold)
            "h1" -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp)
            "h2" -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp)
            "h3" -> SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp)
            "h4", "h5", "h6" -> SpanStyle(fontWeight = FontWeight.Bold)
            "i", "em", "cite", "var", "address" -> SpanStyle(fontStyle = FontStyle.Italic)
            "u", "ins" -> SpanStyle(textDecoration = TextDecoration.Underline)
            "s", "strike", "del" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            // A link is underlined unless the document says otherwise; the
            // `style` attribute below overrides this, which is how a
            // deliberately unstyled link stays unstyled.
            "a" -> SpanStyle(textDecoration = TextDecoration.Underline)
            "code", "kbd", "samp", "tt", "pre" -> SpanStyle(fontFamily = FontFamily.Monospace)
            "sub" -> SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.8.em)
            "sup" -> SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.8.em)
            "small" -> SpanStyle(fontSize = 0.8.em)
            "big" -> SpanStyle(fontSize = 1.2.em)
            else -> SpanStyle()
        }
        // `<font color=…>` predates CSS by a decade and is still what some
        // email clients write.
        val font = if (tag == "font") {
            SpanStyle().let { style ->
                val color = attributes["color"]?.let { Css.parse("color:$it").color }
                if (color != null) style.copy(color = color) else style
            }
        } else {
            SpanStyle()
        }
        val inline = attributes["style"]?.let(Css::parse) ?: SpanStyle()
        return base.merge(font).merge(inline)
    }

    private companion object {
        val WHITESPACE = Regex("""\s+""")

        /** Tags that start and end a line, whatever else they do. */
        val BLOCK_TAGS = setOf(
            "p", "div", "section", "article", "header", "footer", "main", "aside",
            "blockquote", "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "table", "tr", "thead", "tbody", "figure", "figcaption",
        )

        /** Tags with no closing form, which must never be pushed onto the stack. */
        val VOID_TAGS = setOf(
            "br", "hr", "img", "input", "meta", "link", "area", "base", "col",
            "embed", "source", "track", "wbr",
        )
    }
}

/**
 * An element's attributes, read on demand.
 *
 * Deliberately forgiving: `href=foo`, `href='foo'` and `href="foo"` are all
 * accepted, because all three turn up. Attribute names are lowercased; values
 * are not.
 */
private class Attributes(private val raw: String) {
    operator fun get(name: String): String? {
        val match = Regex("""\b${Regex.escape(name)}\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'>]+))""",
            RegexOption.IGNORE_CASE).find(raw) ?: return null
        val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            .ifEmpty { match.groupValues[4] }
        return decodeEntities(value)
    }
}

/**
 * Decodes HTML entities.
 *
 * The named set is short on purpose — HTML5 defines over two thousand, nearly
 * all of them for characters a clipboard never carries, and the numeric forms
 * below cover anything the table misses as long as the source used them. What is
 * here is the set that appears in text written by humans and by word processors.
 */
internal fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    return ENTITY.replace(text) { match ->
        val body = match.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") ->
                body.drop(2).toIntOrNull(16)?.toChars() ?: match.value
            body.startsWith("#") -> body.drop(1).toIntOrNull()?.toChars() ?: match.value
            else -> NAMED_ENTITIES[body] ?: match.value
        }
    }
}

/** A code point as text, or null if it is not one. */
private fun Int.toChars(): String? = when {
    this in 0..0x10FFFF && (this < 0xD800 || this > 0xDFFF) -> {
        // Kotlin has no common `Char.toChars`; a code point above the basic
        // plane is a surrogate pair, computed here rather than borrowed from a
        // JVM API that the native and web targets do not have.
        if (this <= 0xFFFF) {
            this.toChar().toString()
        } else {
            val offset = this - 0x10000
            charArrayOf(
                (0xD800 + (offset shr 10)).toChar(),
                (0xDC00 + (offset and 0x3FF)).toChar(),
            ).concatToString()
        }
    }
    else -> null
}

private val ENTITY = Regex("""&(#[xX]?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]{1,31});""")

private val NAMED_ENTITIES: Map<String, String> = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
    "ndash" to "–", "mdash" to "—", "hellip" to "…", "bull" to "•", "middot" to "·",
    "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
    "laquo" to "«", "raquo" to "»", "sbquo" to "‚", "bdquo" to "„",
    "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "plusmn" to "±",
    "times" to "×", "divide" to "÷", "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
    "sup2" to "²", "sup3" to "³", "micro" to "µ", "para" to "¶", "sect" to "§",
    "dagger" to "†", "Dagger" to "‡", "permil" to "‰", "prime" to "′", "Prime" to "″",
    "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "curren" to "¤",
    "larr" to "←", "uarr" to "↑", "rarr" to "→", "darr" to "↓", "harr" to "↔",
    "shy" to "­", "zwnj" to "‌", "zwj" to "‍",
)
