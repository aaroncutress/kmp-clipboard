package io.github.aaroncutress.clipboard

import io.github.aaroncutress.clipboard.testing.InMemoryRichClipboard
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipFormatTest {

    @Test
    fun `parse normalises case and drops parameters`() {
        // AWT's HTML flavour really is spelled like this, and a set in which
        // this and `text/html` are two entries is a set that makes hasText wrong.
        assertEquals(
            ClipFormat.Html,
            ClipFormat.parse("text/HTML; class=java.lang.String; charset=Unicode"),
        )
        assertEquals(ClipFormat.PlainText, ClipFormat.parse("  Text/Plain ; charset=utf-8 "))
    }

    @Test
    fun `category is the half before the slash`() {
        assertEquals("image", ClipFormat.Png.category)
        assertEquals("text", ClipFormat.Html.category)
        assertEquals("application", ClipFormat.Pdf.category)
    }

    @Test
    fun `a blank mime type is rejected`() {
        assertFailsWith<IllegalArgumentException> { ClipFormat("   ") }
    }

    @Test
    fun `equal formats are one entry in a set`() {
        val formats = setOf(ClipFormat("image/png"), ClipFormat.Png)
        assertEquals(1, formats.size)
    }
}

class ClipBuilderTest {

    @Test
    fun `html offers plain text too`() = runTest {
        // The failure this prevents: an application pasting into a plain-text
        // field asks for text/plain and gets nothing.
        val clip = ClipScope().apply { html("<b>Total:</b> £4.20") }.build()
        val item = clip.items.single()
        assertEquals(setOf(ClipFormat.Html, ClipFormat.PlainText), item.formats)
        assertEquals("Total: £4.20", item.text())
    }

    @Test
    fun `an explicit plain text overrides the stripped default`() = runTest {
        val clip = ClipScope().apply { html("<b>Hi</b>", plainText = "Hello") }.build()
        assertEquals("Hello", clip.items.single().text())
    }

    @Test
    fun `a lazy producer does not run until the bytes are asked for`() = runTest {
        var ran = 0
        val clip = ClipScope().apply {
            bytes(ClipFormat.Png) { ran++; byteArrayOf(1, 2, 3) }
        }.build()
        assertEquals(0, ran, "building a clip must not encode anything")

        val item = clip.items.single()
        assertContentEquals(byteArrayOf(1, 2, 3), item.bytes(ClipFormat.Png))
        assertEquals(1, ran)

        // Memoised: the second read is free, which is what makes
        // hasImage()-then-getImage() cost one clipboard access rather than two.
        item.bytes(ClipFormat.Png)
        assertEquals(1, ran)
    }

    @Test
    fun `a format the item does not offer reads as null`() = runTest {
        val clip = ClipScope().apply { text("hi") }.build()
        assertNull(clip.items.single().bytes(ClipFormat.Png))
    }

    @Test
    fun `each file is its own item`() = runTest {
        val clip = ClipScope().apply {
            files(
                listOf(
                    ClipFile("a.txt", "one".encodeToByteArray()),
                    ClipFile("b.png", byteArrayOf(0x89.toByte())),
                )
            )
        }.build()
        assertEquals(2, clip.items.size)
        assertEquals(listOf("a.txt", "b.png"), clip.items.map { it.file?.name })
        assertEquals(ClipFormat.PlainText, clip.items[0].file?.format)
        assertEquals(ClipFormat.Png, clip.items[1].file?.format)
    }

    @Test
    fun `an empty write is a mistake rather than a shorthand for clear`() {
        assertFailsWith<IllegalArgumentException> { ClipScope().build() }
    }

    @Test
    fun `formats span every item`() = runTest {
        val clip = ClipScope().apply {
            text("summary")
            item { bytes(ClipFormat.Pdf) { byteArrayOf(1) } }
        }.build()
        assertEquals(setOf(ClipFormat.PlainText, ClipFormat.Pdf), clip.formats)
        assertEquals(ClipFormat.Pdf, clip.firstOrNull(ClipFormat.Pdf)?.formats?.single())
    }

    @Test
    fun `a clip with no items cannot be built`() {
        assertFailsWith<IllegalArgumentException> { Clip("label", emptyList()) }
    }
}

class ClipInfoTest {

    // A set rather than a vararg: `ClipFormat` is a value class, and Kotlin
    // prohibits those as vararg element types.
    private fun info(vararg formats: String) =
        ClipInfo(null, formats.mapTo(mutableSetOf(), ClipFormat::parse))

    @Test
    fun `hasText matches the whole text category`() {
        assertTrue(info("text/html").hasText)
        assertTrue(info("text/rtf").hasText)
        assertTrue(info("text/uri-list").hasText)
        assertFalse(info("image/png").hasText)
    }

    @Test
    fun `hasImage matches the whole image category`() {
        assertTrue(info("image/jpeg").hasImage)
        assertTrue(info("image/webp").hasImage)
        assertFalse(info("application/pdf").hasImage)
    }

    @Test
    fun `equal infos compare equal so a State does not churn`() {
        assertEquals(info("text/plain"), info("text/plain"))
        assertEquals(info("text/plain").hashCode(), info("text/plain").hashCode())
    }
}

class ConvenienceTest {

    @Test
    fun `text round trips`() = runTest {
        val clipboard = InMemoryRichClipboard()
        clipboard.setText("hello")
        assertEquals("hello", clipboard.getText())
        assertTrue(clipboard.hasText())
        assertFalse(clipboard.hasImage())
    }

    @Test
    fun `getText falls back to flattening html`() = runTest {
        val clipboard = InMemoryRichClipboard()
        // A clip from another application that offered HTML only.
        clipboard.write(Clip(null, listOf(ClipScope().apply {
            bytes(ClipFormat.Html, "<p>One</p><p>Two</p>".encodeToByteArray())
        }.build().items.single())))
        assertEquals("One\n\nTwo", clipboard.getText())
    }

    @Test
    fun `uris are read as RFC 2483 says`() = runTest {
        val clipboard = InMemoryRichClipboard()
        clipboard.write {
            bytes(
                ClipFormat.UriList,
                "# a comment\nhttps://one.example\n\nhttps://two.example\n".encodeToByteArray(),
            )
        }
        assertEquals(
            listOf("https://one.example", "https://two.example"),
            clipboard.getUris(),
        )
    }

    @Test
    fun `clear empties the clipboard`() = runTest {
        val clipboard = InMemoryRichClipboard()
        clipboard.setText("hello")
        clipboard.clear()
        assertNull(clipboard.read())
        assertNull(clipboard.peek())
        assertFalse(clipboard.hasText())
    }

    @Test
    fun `setFiles refuses an empty list rather than clearing`() = runTest {
        val clipboard = InMemoryRichClipboard()
        clipboard.setText("keep me")
        assertFailsWith<IllegalArgumentException> { clipboard.setFiles(emptyList()) }
        assertEquals("keep me", clipboard.getText())
    }

    @Test
    fun `peek does not count as a read`() = runTest {
        val clipboard = InMemoryRichClipboard()
        clipboard.setText("hello")
        clipboard.hasText()
        clipboard.hasImage()
        assertEquals(0, clipboard.readCount, "hasText and hasImage must go through peek")
        clipboard.getText()
        assertEquals(1, clipboard.readCount)
    }

    @Test
    fun `getFiles finds the files across items`() = runTest {
        val clipboard = InMemoryRichClipboard()
        clipboard.setFiles(listOf(ClipFile("notes.txt", "hi".encodeToByteArray())))
        val files = clipboard.getFiles()
        assertEquals(1, files.size)
        assertEquals("notes.txt", files.single().name)
        assertEquals("hi", files.single().bytes().decodeToString())
    }
}
