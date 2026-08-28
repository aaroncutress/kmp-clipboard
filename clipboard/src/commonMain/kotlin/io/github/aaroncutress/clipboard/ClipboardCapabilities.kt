package io.github.aaroncutress.clipboard

import androidx.compose.runtime.Immutable

/**
 * What the clipboard underneath this [RichClipboard] can actually do.
 *
 * The four platforms are not the same clipboard with four spellings, and this
 * library does not pretend otherwise. A browser will not write RTF and cannot
 * hand you a file path; Android cannot hold image bytes without a content
 * provider to serve them; a headless JVM has no clipboard at all. Every one of
 * those is a real difference a user will notice.
 *
 * There are two ways to expose that: let the call fail and make the caller
 * catch, or say so first. This is saying so first — so a UI can grey out the
 * "Copy as RTF" item on the web rather than offering it and apologising.
 *
 * ```kotlin
 * val clipboard = LocalRichClipboard.current
 * MenuItem("Copy image", enabled = clipboard.capabilities.writesImages) { … }
 * ```
 *
 * These describe the *platform*, not the moment. `writesImages` being true does
 * not promise the next write succeeds — a browser can still refuse for want of
 * a user gesture — and it is not a substitute for handling
 * [ClipboardAccessDeniedException]. What it does promise is that the capability
 * exists at all, which is the part a menu needs to know.
 *
 * @property available Whether there is a system clipboard here. False on a
 *   headless JVM and in a browser too old for the Clipboard API; every read and
 *   write throws [ClipboardUnavailableException] when it is false.
 * @property writesImages Whether [setImage][RichClipboard] can put an image
 *   where another application will find it. True everywhere except an Android
 *   build whose file provider has been disabled — see
 *   `AndroidRichClipboard`.
 * @property writesFiles Whether files can be *put on* the clipboard. False on
 *   the web: a page can read a pasted file's bytes but has no way to offer one.
 * @property readsFilePaths Whether [ClipFile.path] is populated on read. False
 *   on the web, for the same reason.
 * @property writesRtf Whether [ClipFormat.Rtf] survives a write. False on the
 *   web, where no browser accepts it. Where this is false, write [ClipFormat.Html]
 *   instead — every RTF-consuming application on every desktop reads HTML too.
 * @property writesCustomFormats Whether a MIME type this library does not know
 *   about survives a write to *other applications*. True everywhere; the web
 *   achieves it by prefixing Chrome's `web ` marker, which means the type is
 *   visible to other web pages but not to native applications.
 * @property multipleItems Whether a clip may hold more than one item. True on
 *   Android and iOS, false on desktop and the web, which flatten a multi-item
 *   write to its first item.
 * @property readsWithoutUserGesture Whether a read can happen outside a user
 *   gesture. False on the web. Where this is false, do the read in an `onClick`,
 *   not in a `LaunchedEffect`.
 * ### Constructing one
 *
 * The constructor is public and every parameter defaults to true, so a test can
 * say what it wants to pretend and stay quiet about the rest:
 *
 * ```kotlin
 * // How does this screen behave in a browser?
 * InMemoryRichClipboard(ClipboardCapabilities(writesFiles = false, readsWithoutUserGesture = false))
 * ```
 *
 * That is the only reason it is public. In production this always comes from
 * [RichClipboard.capabilities], and a value you built yourself describes nothing.
 *
 * @property observesChanges Whether [rememberClipInfo] reports changes made by
 *   *other* applications. True on desktop and iOS; on Android only while the app
 *   is in the foreground, which since Android 10 is the only time it may look;
 *   on the web only when the page regains focus.
 */
@Immutable
class ClipboardCapabilities(
    val available: Boolean = true,
    val writesImages: Boolean = true,
    val writesFiles: Boolean = true,
    val readsFilePaths: Boolean = true,
    val writesRtf: Boolean = true,
    val writesCustomFormats: Boolean = true,
    val multipleItems: Boolean = true,
    val readsWithoutUserGesture: Boolean = true,
    val observesChanges: Boolean = true,
) {
    override fun toString(): String = buildString {
        append("ClipboardCapabilities(")
        val on = listOfNotNull(
            "available".takeIf { available },
            "writesImages".takeIf { writesImages },
            "writesFiles".takeIf { writesFiles },
            "readsFilePaths".takeIf { readsFilePaths },
            "writesRtf".takeIf { writesRtf },
            "writesCustomFormats".takeIf { writesCustomFormats },
            "multipleItems".takeIf { multipleItems },
            "readsWithoutUserGesture".takeIf { readsWithoutUserGesture },
            "observesChanges".takeIf { observesChanges },
        )
        append(if (on.isEmpty()) "nothing" else on.joinToString())
        append(")")
    }
}
