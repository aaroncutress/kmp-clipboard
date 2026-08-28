package io.github.aaroncutress.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIPasteboardChangedNotification

/**
 * `UIPasteboardChangedNotification`, and a re-check when the app comes back.
 *
 * The notification only covers changes made *by this process*. A user who
 * switches to Safari, copies a link and switches back has changed the pasteboard
 * without this app hearing anything — hence the second observer, which is what
 * makes a paste button correct after a task switch rather than only after a copy
 * the app itself performed.
 */
@Composable
internal actual fun clipboardRevision(): Int {
    var revision by remember { mutableIntStateOf(0) }
    val center = remember { NSNotificationCenter.defaultCenter }

    DisposableEffect(center) {
        val queue = NSOperationQueue.mainQueue
        val observers = listOf(
            UIPasteboardChangedNotification,
            UIApplicationDidBecomeActiveNotification,
        ).map { name ->
            center.addObserverForName(name, null, queue) { revision++ }
        }
        onDispose { observers.forEach(center::removeObserver) }
    }
    return revision
}
