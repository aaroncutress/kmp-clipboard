package io.github.aaroncutress.clipboard.demo

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

/**
 * The demo, in a browser.
 *
 * `ComposeViewport` rather than `CanvasBasedWindow`, which is deprecated: the
 * viewport sizes itself to a DOM element instead of to a fixed canvas, which is
 * what makes the page usable on a phone as well as a laptop.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        ClipboardDemo()
    }
}
