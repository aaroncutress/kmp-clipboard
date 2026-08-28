@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.aaroncutress.clipboard.internal

import io.github.aaroncutress.clipboard.ClipboardAccessDeniedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlin.js.JsAny
import kotlin.js.Promise

/**
 * Awaits a promise, turning a rejection into a [ClipboardAccessDeniedException].
 *
 * Every rejection the Clipboard API produces is a refusal of some kind — no
 * permission, no user activation, an insecure context, a type the browser will
 * not sanitize — and the browser reports all of them as a rejected promise with
 * a message. That message is the single most useful thing a developer can be
 * shown here, so it is carried through rather than replaced.
 *
 * `CancellationException` is re-thrown untouched: a cancelled coroutine is not a
 * clipboard failure, and swallowing it into a `ClipboardException` would break
 * structured concurrency for every caller.
 */
internal suspend fun <T : JsAny?> Promise<T>.awaitOrThrow(): T = try {
    await()
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    throw ClipboardAccessDeniedException(
        "The browser refused the clipboard operation: ${e.message ?: e.toString()}. " +
            "The usual causes are a read outside a user gesture, a denied " +
            "clipboard-read permission, or a page that is not a secure context " +
            "(https, or localhost).",
        e,
    )
}
