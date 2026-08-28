package io.github.aaroncutress.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.aaroncutress.clipboard.internal.systemClipboard
import java.awt.datatransfer.FlavorListener

/**
 * AWT's `FlavorListener`, which is the only change signal the desktop clipboard
 * offers.
 *
 * It fires when the *set of available flavours* changes — so an application
 * replacing "some text" with "some other text" may not trigger it, since the
 * flavours are identical. That is a real gap and there is no second event to
 * fall back on: the alternative is polling `getContents`, which is a
 * cross-process transfer on every tick and on X11 can block on an application
 * that is not answering. A missed update is cheaper than that.
 */
@Composable
internal actual fun clipboardRevision(): Int {
    val clipboard = remember { systemClipboard } ?: return 0
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(clipboard) {
        val listener = FlavorListener { revision++ }
        clipboard.addFlavorListener(listener)
        onDispose { clipboard.removeFlavorListener(listener) }
    }
    return revision
}
