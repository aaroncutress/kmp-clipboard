package io.github.aaroncutress.clipboard

import io.github.aaroncutress.clipboard.internal.AwtFlavors
import io.github.aaroncutress.clipboard.internal.ClipTransferable
import kotlinx.coroutines.test.runTest
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop translation layer, against a real `java.awt.datatransfer.Clipboard`.
 *
 * A private clipboard rather than the system one. It is the same class and the
 * same `Transferable` contract — what differs is only that nothing else on the
 * machine can overwrite it mid-test, and that it exists at all on a headless CI
 * runner, where `Toolkit.getDefaultToolkit().systemClipboard` throws.
 *
 * This is the only one of the four platforms whose bridge can be tested without
 * a device or a browser, which is why it is tested thoroughly here and the other
 * three lean on the demo app.
 */
class AwtTransferTest {

    private fun clipboard() = Clipboard("kmp-clipboard test")

    @Test
    fun `plain text goes out as stringFlavor`() = runTest {
        val clipboard = clipboard()
        clipboard.setContents(ClipTransferable.of(clip { text("hello") }), null)

        val contents = clipboard.getContents(null)
        // `stringFlavor` specifically: it is what every desktop application
        // reads, and its MIME type is `application/x-java-serialized-object`,
        // so nothing that matched on `text/plain` would find it.
        assertTrue(contents.isDataFlavorSupported(DataFlavor.stringFlavor))
        assertEquals("hello", contents.getTransferData(DataFlavor.stringFlavor))
        assertEquals(
            "hello",
            AwtFlavors.read(contents, ClipFormat.PlainText)?.decodeToString(),
        )
    }

    @Test
    fun `html goes out in both the string and the stream form`() = runTest {
        val clipboard = clipboard()
        clipboard.setContents(ClipTransferable.of(clip { html("<b>hi</b>", "hi") }), null)
        val contents = clipboard.getContents(null)

        // Word and LibreOffice ask for the stream form, browsers for the string
        // form. Offering one is offering half.
        assertEquals("<b>hi</b>", contents.getTransferData(AwtFlavors.HtmlString))
        val stream = DataFlavor("text/html;class=java.io.InputStream;charset=utf-8", "HTML")
        assertTrue(contents.isDataFlavorSupported(stream))

        assertEquals("<b>hi</b>", AwtFlavors.read(contents, ClipFormat.Html)?.decodeToString())
        assertEquals("hi", AwtFlavors.read(contents, ClipFormat.PlainText)?.decodeToString())
    }

    @Test
    fun `a stream flavour can be read twice`() = runTest {
        // A receiving application is entitled to ask once to sniff and once to
        // take; a Transferable that handed out the same consumed stream would
        // give it an empty file the second time.
        val clipboard = clipboard()
        clipboard.setContents(ClipTransferable.of(clip { html("<i>x</i>", "x") }), null)
        val contents = clipboard.getContents(null)

        assertEquals("<i>x</i>", AwtFlavors.read(contents, ClipFormat.Html)?.decodeToString())
        assertEquals("<i>x</i>", AwtFlavors.read(contents, ClipFormat.Html)?.decodeToString())
    }

    @Test
    fun `a custom format survives a round trip`() = runTest {
        val cells = ClipFormat("application/vnd.myapp.cells+json")
        val clipboard = clipboard()
        clipboard.setContents(
            ClipTransferable.of(clip { bytes(cells, """{"a":1}""".encodeToByteArray()) }),
            null,
        )
        val contents = clipboard.getContents(null)

        assertTrue(cells in AwtFlavors.formatsOf(contents.transferDataFlavors))
        assertEquals("""{"a":1}""", AwtFlavors.read(contents, cells)?.decodeToString())
    }

    @Test
    fun `files go out as a java file list and come back with paths`() = runTest {
        val clipboard = clipboard()
        clipboard.setContents(
            ClipTransferable.of(clip { file(ClipFile("notes.txt", "hi there".encodeToByteArray())) }),
            null,
        )
        val contents = clipboard.getContents(null)

        assertTrue(contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
        val files = AwtFlavors.filesOf(contents)
        assertEquals(1, files.size)
        assertEquals("notes.txt", files.single().name)
        assertEquals("hi there", files.single().bytes().decodeToString())
        // A file built from bytes has no path until it is written, and a desktop
        // paste needs one — so the transferable spills it to a temp file.
        assertTrue(files.single().path!!.endsWith("notes.txt"))
    }

    @Test
    fun `a file list is readable as a uri list`() = runTest {
        val clipboard = clipboard()
        clipboard.setContents(
            ClipTransferable.of(clip { file(ClipFile("a.txt", "x".encodeToByteArray())) }),
            null,
        )
        val contents = clipboard.getContents(null)

        val uris = AwtFlavors.read(contents, ClipFormat.UriList)?.decodeToString()
        assertTrue(uris!!.startsWith("file:/"), uris)
        assertTrue(uris.endsWith("a.txt"), uris)
    }

    @Test
    fun `formatsOf reports text for stringFlavor despite its mime type`() {
        val flavors = arrayOf(DataFlavor.stringFlavor)
        assertTrue(ClipFormat.PlainText in AwtFlavors.formatsOf(flavors))
    }

    @Test
    fun `an absent format reads as null rather than throwing`() = runTest {
        val clipboard = clipboard()
        clipboard.setContents(ClipTransferable.of(clip { text("only text") }), null)
        assertNull(AwtFlavors.read(clipboard.getContents(null), ClipFormat.Pdf))
    }

    @Test
    fun `clearing leaves a transferable with no flavours`() {
        val clipboard = clipboard()
        clipboard.setContents(ClipTransferable.Empty, null)
        assertContentEquals(emptyArray(), clipboard.getContents(null).transferDataFlavors)
    }

    private fun clip(block: ClipScope.() -> Unit): Clip = ClipScope().apply(block).build()
}

/**
 * `application/x-java-serialized-object` is AWT's transport, not a format.
 *
 * Found by running the demo: the clipboard inspector listed it beside
 * `text/html` and `image/png`, which is an implementation detail leaking into an
 * API whose whole contract is that formats are MIME types.
 */
class SerializedObjectFlavorTest {

    @Test
    fun `the serialized-object flavour never reaches a ClipInfo`() {
        val flavors = arrayOf(
            DataFlavor.stringFlavor,
            // The shape the X11 selection hands back, which
            // `isFlavorSerializedObjectType` does *not* recognise because the
            // representation class is not Serializable.
            DataFlavor("application/x-java-serialized-object;class=java.io.InputStream", "obj"),
        )
        val formats = AwtFlavors.formatsOf(flavors)
        assertEquals(setOf(ClipFormat.PlainText), formats)
    }
}
