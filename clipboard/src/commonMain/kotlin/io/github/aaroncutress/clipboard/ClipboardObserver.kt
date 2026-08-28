package io.github.aaroncutress.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState

/**
 * What is on the clipboard, kept up to date.
 *
 * ```kotlin
 * val info by rememberClipInfo()
 * IconButton(onClick = ::paste, enabled = info?.hasText == true) { Icon(Paste) }
 * ```
 *
 * Goes through [RichClipboard.peek], so on Android this does **not** trip the
 * "pasted from clipboard" toast — which is the whole reason a paste button can
 * afford to ask this question continuously.
 *
 * ### Best-effort, by construction
 *
 * No platform offers a reliable "the clipboard changed" event, and the four
 * differ in how close they get. [ClipboardCapabilities.observesChanges] says
 * whether this reports anything at all; where it does, the caveats are:
 *
 * - **Android** — a real callback, and it only fires while the app is in the
 *   foreground. Since Android 10 the foreground is the only time an app may read
 *   the clipboard anyway, so nothing is lost that could have been offered.
 * - **iOS** — a real notification, plus a re-check when the app becomes active,
 *   because a change made in another app while this one was backgrounded does
 *   not notify.
 * - **Desktop** — AWT's `FlavorListener`, which fires when the set of available
 *   flavours changes. An application replacing the clipboard with *the same*
 *   types will not always trigger it.
 * - **The web** — nothing. There is no event, and no way to look without a
 *   permission prompt. This stays null there; see [RichClipboard.peek].
 *
 * A UI should treat a null as "unknown" rather than as "empty" — on the web it
 * always means the former.
 *
 * @param clipboard The clipboard to watch. The one from [LocalRichClipboard] by
 *   default, which is nearly always what you want; pass a fake to test the UI
 *   around it.
 */
@Composable
fun rememberClipInfo(
    clipboard: RichClipboard = LocalRichClipboard.current,
): State<ClipInfo?> {
    val revision = clipboardRevision()
    return produceState<ClipInfo?>(initialValue = null, clipboard, revision) {
        // A refusal here is not something a paste button can act on, and this
        // runs unprompted rather than in response to anything the user did — so
        // it reports "unknown" rather than throwing into a composition.
        value = try {
            clipboard.peek()
        } catch (_: ClipboardException) {
            null
        }
    }
}

/**
 * A number that changes whenever the platform thinks the clipboard has.
 *
 * A revision counter rather than the [ClipInfo] itself, so that the reading —
 * which is suspending, and on some platforms not free — stays in common code and
 * each platform only has to answer the one question it can actually answer.
 *
 * Constant on platforms that cannot tell.
 */
@Composable
internal expect fun clipboardRevision(): Int
