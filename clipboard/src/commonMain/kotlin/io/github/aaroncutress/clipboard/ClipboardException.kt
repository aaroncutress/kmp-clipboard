package io.github.aaroncutress.clipboard

/**
 * The clipboard could not do what was asked.
 *
 * ### Why these throw instead of returning null
 *
 * Every read on this API can come back null, and null means one specific thing:
 * **there is nothing there**, or nothing there in the format you asked for.
 * That is an ordinary answer and callers handle it inline.
 *
 * A refusal is not that. On the web, `getText()` returning null tells a
 * developer their clipboard is empty when in fact the browser denied the read
 * and the fix is a user gesture or a permission — a five-minute problem that
 * becomes an afternoon if the API lies about it. So a refusal throws, and it
 * says which refusal it was.
 *
 * The one place this is softened is [RichClipboard.peek], which answers a
 * question a UI asks constantly and cannot usefully act on: it returns null on
 * a platform that will not say.
 */
sealed class ClipboardException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The platform refused access to the clipboard.
 *
 * Almost always the web, and almost always one of three things:
 *
 * - **No user activation.** Chrome and Safari allow a read only from inside a
 *   handler for a real user gesture. A read from a `LaunchedEffect` on first
 *   composition is refused; the same read from an `onClick` is not.
 * - **Permission denied.** Chrome asks the user once for `clipboard-read` and
 *   remembers the answer. Nothing you can do in code changes a "no".
 * - **Insecure context.** The Clipboard API does not exist on plain `http://`,
 *   with the standard exception for `localhost`.
 *
 * On Android, iOS and desktop this is rare, and means the platform clipboard
 * was unavailable at that moment — a locked screen, another application holding
 * the X11 selection and not answering, an iOS device where the user declined
 * the paste prompt.
 */
class ClipboardAccessDeniedException internal constructor(
    message: String,
    cause: Throwable? = null,
) : ClipboardException(message, cause)

/**
 * There is no clipboard here at all.
 *
 * A headless JVM is the usual cause — `Toolkit.getDefaultToolkit()` throws
 * `HeadlessException` on a machine with no display, which is most CI runners.
 * Also a browser too old for the Clipboard API entirely.
 *
 * Distinct from [ClipboardAccessDeniedException] because the responses differ:
 * a denial is worth retrying from a button, and this is not worth retrying at
 * all. [ClipboardCapabilities.available] answers it ahead of time.
 */
class ClipboardUnavailableException internal constructor(
    message: String,
    cause: Throwable? = null,
) : ClipboardException(message, cause)

/**
 * The content was there, and could not be turned into what was asked for.
 *
 * A truncated PNG, an image the platform decoder rejects, HTML in an encoding
 * that is not UTF-8 and does not say so. The bytes are usually still readable
 * with [ClipItem.bytes] — this is the decode failing, not the read.
 */
class ClipboardFormatException internal constructor(
    message: String,
    cause: Throwable? = null,
) : ClipboardException(message, cause)
