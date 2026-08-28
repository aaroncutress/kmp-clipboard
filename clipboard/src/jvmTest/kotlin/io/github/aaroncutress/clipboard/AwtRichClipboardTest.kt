package io.github.aaroncutress.clipboard

import kotlinx.coroutines.test.runTest
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [AwtRichClipboard] against the machine's own clipboard.
 *
 * Both halves of this matter, and which one runs depends on the machine:
 *
 * - **Headless** — no display, which is the default on a CI runner. The
 *   assertion is that the library says so through [ClipboardCapabilities] and
 *   throws [ClipboardUnavailableException] rather than an
 *   `ExceptionInInitializerError` from somewhere inside AWT.
 * - **With a display** — including `xvfb-run`, which is how to get this half to
 *   run in CI. The assertion is a real round trip through the real X11 or
 *   Windows clipboard, which is the only thing that proves the transferable is
 *   accepted by something other than the code that built it.
 */
class AwtRichClipboardTest {

    private val headless = GraphicsEnvironment.isHeadless()

    @Test
    fun `capabilities agree with the environment`() {
        // Which half of this class did any work is otherwise invisible: the
        // round-trip tests return early when headless and pass either way, so
        // a CI run that silently lost its display would look identical to one
        // that exercised the real clipboard. This is the assertion that tells
        // them apart.
        assertEquals(!headless, AwtRichClipboard.capabilities.available)
    }

    @Test
    fun `a headless jvm says so rather than failing obscurely`() = runTest {
        if (!headless) return@runTest
        val clipboard = AwtRichClipboard
        assertFalse(clipboard.capabilities.available)
        assertFailsWith<ClipboardUnavailableException> { clipboard.read() }
        assertFailsWith<ClipboardUnavailableException> { clipboard.setText("x") }
        // `peek` is the exception: a UI asks it constantly and cannot act on a
        // throw, so it is documented to answer "I don't know" with null.
        assertFailsWith<ClipboardUnavailableException> { clipboard.peek() }
    }

    @Test
    fun `text round trips through the real clipboard`() = runTest {
        if (headless) return@runTest
        val clipboard = AwtRichClipboard
        clipboard.setText("round trip")
        assertEquals("round trip", clipboard.getText())
        assertTrue(clipboard.hasText())
    }

    @Test
    fun `formatted text round trips through the real clipboard`() = runTest {
        if (headless) return@runTest
        val clipboard = AwtRichClipboard
        clipboard.setHtml("<b>bold</b>", plainText = "bold")
        assertEquals("<b>bold</b>", clipboard.getHtml())
        assertEquals("bold", clipboard.getText())
    }

    @Test
    fun `peek sees the formats without reading them`() = runTest {
        if (headless) return@runTest
        val clipboard = AwtRichClipboard
        clipboard.setHtml("<i>x</i>", plainText = "x")
        val info = clipboard.peek()
        assertTrue(info!!.hasText)
        assertTrue(ClipFormat.Html in info)
    }

    @Test
    fun `clearing empties it`() = runTest {
        if (headless) return@runTest
        val clipboard = AwtRichClipboard
        clipboard.setText("something")
        clipboard.clear()
        assertNull(clipboard.getText())
    }
}
