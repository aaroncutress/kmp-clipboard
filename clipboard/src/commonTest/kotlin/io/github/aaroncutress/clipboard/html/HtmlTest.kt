package io.github.aaroncutress.clipboard.html

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToHtmlTest {

    @Test
    fun `plain text is escaped rather than marked up`() {
        assertEquals("a &lt;b&gt; &amp; c", AnnotatedString("a <b> & c").toHtml())
    }

    @Test
    fun `newlines become breaks`() {
        assertEquals("one<br>two", AnnotatedString("one\ntwo").toHtml())
    }

    @Test
    fun `a carriage return does not double the break`() {
        assertEquals("one<br>two", AnnotatedString("one\r\ntwo").toHtml())
    }

    @Test
    fun `weight and slant use semantic tags`() {
        val styled = buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("bold") }
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("italic") }
        }
        assertEquals("<b>bold</b><i>italic</i>", styled.toHtml())
    }

    @Test
    fun `decorations use semantic tags`() {
        val styled = buildAnnotatedString {
            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append("u") }
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append("s") }
        }
        assertEquals("<u>u</u><s>s</s>", styled.toHtml())
    }

    @Test
    fun `colour and size become inline css`() {
        val styled = buildAnnotatedString {
            withStyle(SpanStyle(color = Color(0xFFFF0000), fontSize = 18.sp)) { append("big red") }
        }
        assertEquals("""<span style="color:#ff0000;font-size:18px">big red</span>""", styled.toHtml())
    }

    @Test
    fun `a translucent colour becomes rgba`() {
        val styled = buildAnnotatedString {
            withStyle(SpanStyle(background = Color(0x80FFFF00))) { append("hi") }
        }
        assertTrue(styled.toHtml().contains("background-color:rgba(255,255,0,0.502)"), styled.toHtml())
    }

    @Test
    fun `links become anchors`() {
        val styled = buildAnnotatedString {
            append("see ")
            withLink(LinkAnnotation.Url("https://example.com/?a=1&b=2")) { append("this") }
        }
        // No `<u>`: `withLink` records the annotation and leaves styling to
        // the renderer's `TextLinkStyles`, so there is no span here to serialise.
        assertEquals(
            """see <a href="https://example.com/?a=1&amp;b=2">this</a>""",
            styled.toHtml(),
        )
    }

    @Test
    fun `overlapping spans still produce well-formed html`() {
        // 0..6 bold and 3..9 italic cannot be nested as tags. Flattening to
        // per-character style and grouping equal runs is what makes this
        // expressible at all.
        val styled = AnnotatedString(
            text = "aaabbbccc",
            spanStyles = listOf(
                AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), 0, 6),
                AnnotatedString.Range(SpanStyle(fontStyle = FontStyle.Italic), 3, 9),
            ),
        )
        assertEquals("<b>aaa</b><b><i>bbb</i></b><i>ccc</i>", styled.toHtml())
    }

    @Test
    fun `paragraph alignment survives`() {
        val styled = buildAnnotatedString {
            withStyle(ParagraphStyle(textAlign = TextAlign.Center)) { append("middle") }
        }
        assertEquals("""<p style="text-align:center">middle</p>""", styled.toHtml())
    }

    @Test
    fun `empty text is empty html`() {
        assertEquals("", AnnotatedString("").toHtml())
    }
}

class ParseHtmlTest {

    @Test
    fun `tags become styles`() {
        val parsed = parseHtml("<b>bold</b> and <i>italic</i>")
        assertEquals("bold and italic", parsed.text)
        assertEquals(FontWeight.Bold, parsed.spanStyles.first().item.fontWeight)
        assertEquals(FontStyle.Italic, parsed.spanStyles.last().item.fontStyle)
    }

    @Test
    fun `an unknown tag is dropped and its text kept`() {
        // Word writes `<o:p>`; Google Docs writes spans with ids nobody asked
        // for. A parser that gave up on either would be useless for the two
        // commonest sources of formatted text there are.
        assertEquals("keep me", parseHtml("<o:p>keep <custom-thing>me</custom-thing></o:p>").text)
    }

    @Test
    fun `script and style contents are discarded`() {
        assertEquals(
            "before after",
            parseHtml("before <script>alert('x')</script><style>p{color:red}</style> after").text,
        )
    }

    @Test
    fun `whitespace collapses the way html says`() {
        assertEquals("one two three", parseHtml("  one   two\n\tthree  ").text)
    }

    @Test
    fun `pre keeps its whitespace`() {
        assertEquals("a   b\nc", parseHtml("<pre>a   b\nc</pre>").text)
    }

    @Test
    fun `paragraphs are separated by a blank line and the document does not end in one`() {
        assertEquals("One\n\nTwo", parseHtml("<p>One</p><p>Two</p>").text)
    }

    @Test
    fun `br is a single newline`() {
        assertEquals("one\ntwo", parseHtml("one<br>two").text)
    }

    @Test
    fun `inline css is read`() {
        val parsed = parseHtml("""<span style="color: #ff0000; font-weight: 700">red</span>""")
        val style = parsed.spanStyles.single().item
        assertEquals(Color(0xFFFF0000), style.color)
        assertEquals(FontWeight(700), style.fontWeight)
    }

    @Test
    fun `rgb colours are read`() {
        // Google Docs writes its clipboard HTML this way, which is the reason
        // this is here rather than only hex.
        val parsed = parseHtml("""<span style="color:rgb(0,128,255)">x</span>""")
        assertEquals(Color(0xFF0080FF), parsed.spanStyles.single().item.color)
    }

    @Test
    fun `named colours are read`() {
        val parsed = parseHtml("""<span style="color: DarkRed; background: yellow">x</span>""")
        val style = parsed.spanStyles.single().item
        assertEquals(Color(0xFFFFFF00), style.background)
    }

    @Test
    fun `font sizes in pt become sp`() {
        val parsed = parseHtml("""<span style="font-size:12pt">x</span>""")
        assertEquals(16f, parsed.spanStyles.single().item.fontSize.value)
    }

    @Test
    fun `links become url annotations`() {
        val parsed = parseHtml("""go <a href="https://example.com">here</a>""")
        val link = parsed.getLinkAnnotations(0, parsed.text.length).single()
        assertEquals("https://example.com", (link.item as LinkAnnotation.Url).url)
        assertEquals("here", parsed.text.substring(link.start, link.end))
    }

    @Test
    fun `entities are decoded`() {
        assertEquals("a & b < c   — ©", parseHtml("a &amp; b &lt; c &nbsp; &mdash; &copy;").text)
    }

    @Test
    fun `numeric entities are decoded including astral ones`() {
        assertEquals("Aé😀", parseHtml("&#65;&#xe9;&#128512;").text)
    }

    @Test
    fun `an unclosed tag does not swallow the rest`() {
        // The paragraph break is correct — `<p>` is a block boundary whether or
        // not the `<b>` before it was closed. What is being tested is that the
        // open `<b>` does not consume the `<p>` or the text after it.
        val parsed = parseHtml("<b>bold <p>then plain")
        assertEquals("bold\n\nthen plain", parsed.text)
        assertEquals(FontWeight.Bold, parsed.spanStyles.first().item.fontWeight)
    }

    @Test
    fun `an unmatched close tag is ignored`() {
        assertEquals("text", parseHtml("</b>text</div>").text)
    }

    @Test
    fun `a stray less-than is text`() {
        assertEquals("a < b", parseHtml("a < b").text)
    }

    @Test
    fun `list items are bulleted and numbered`() {
        assertEquals("• one\n• two", parseHtml("<ul><li>one</li><li>two</li></ul>").text)
        assertEquals("1. one\n2. two", parseHtml("<ol><li>one</li><li>two</li></ol>").text)
    }

    @Test
    fun `the windows fragment markers are honoured`() {
        val clipboardHtml = """
            Version:1.0
            StartHTML:00000097
            <html><body>
            <!--StartFragment--><b>only this</b><!--EndFragment-->
            </body></html>
        """.trimIndent()
        assertEquals("only this", parseHtml(clipboardHtml).text)
    }

    @Test
    fun `attribute quoting styles are all accepted`() {
        listOf(
            """<a href="https://e.com">x</a>""",
            """<a href='https://e.com'>x</a>""",
            """<a href=https://e.com>x</a>""",
        ).forEach { html ->
            val parsed = parseHtml(html)
            assertEquals(
                "https://e.com",
                (parsed.getLinkAnnotations(0, parsed.text.length).single().item as LinkAnnotation.Url).url,
                html,
            )
        }
    }
}

class HtmlRoundTripTest {

    @Test
    fun `styled text survives a round trip`() {
        val original = buildAnnotatedString {
            append("plain ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("bold ") }
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("italic ") }
            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append("under ") }
            withStyle(SpanStyle(color = Color(0xFF3366CC))) { append("blue") }
        }
        val parsed = parseHtml(original.toHtml())

        assertEquals(original.text, parsed.text)
        assertEquals(FontWeight.Bold, parsed.styleAt(6).fontWeight)
        assertEquals(FontStyle.Italic, parsed.styleAt(11).fontStyle)
        assertEquals(TextDecoration.Underline, parsed.styleAt(18).textDecoration)
        assertEquals(Color(0xFF3366CC), parsed.styleAt(24).color)
    }

    @Test
    fun `text with markup characters survives a round trip`() {
        val original = AnnotatedString("if a < b && c > d then \"go\"")
        assertEquals(original.text, parseHtml(original.toHtml()).text)
    }

    @Test
    fun `a link survives a round trip`() {
        val original = buildAnnotatedString {
            append("see ")
            withLink(LinkAnnotation.Url("https://example.com/a?b=1&c=2")) { append("this") }
        }
        val parsed = parseHtml(original.toHtml())
        assertEquals(original.text, parsed.text)
        assertEquals(
            "https://example.com/a?b=1&c=2",
            (parsed.getLinkAnnotations(0, parsed.text.length).single().item as LinkAnnotation.Url).url,
        )
    }

    /** The merged style at [index], which is what a renderer would apply there. */
    private fun AnnotatedString.styleAt(index: Int): SpanStyle =
        spanStyles.filter { index >= it.start && index < it.end }
            .fold(SpanStyle()) { style, range -> style.merge(range.item) }
}
