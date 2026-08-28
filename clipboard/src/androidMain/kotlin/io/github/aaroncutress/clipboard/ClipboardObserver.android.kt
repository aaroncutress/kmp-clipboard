package io.github.aaroncutress.clipboard

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Android's `OnPrimaryClipChangedListener`.
 *
 * A real callback, and the best of the four. It only fires while the app is in
 * the foreground — but since Android 10 the foreground is the only time an app
 * is permitted to read the clipboard at all, so the restriction costs nothing
 * that could have been delivered.
 */
@Composable
internal actual fun clipboardRevision(): Int {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    } ?: return 0

    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(manager) {
        val listener = ClipboardManager.OnPrimaryClipChangedListener { revision++ }
        manager.addPrimaryClipChangedListener(listener)
        onDispose { manager.removePrimaryClipChangedListener(listener) }
    }
    return revision
}
