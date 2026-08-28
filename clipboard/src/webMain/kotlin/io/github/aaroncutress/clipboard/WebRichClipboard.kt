@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.aaroncutress.clipboard

import androidx.compose.runtime.CompositionLocalAccessorScope
import io.github.aaroncutress.clipboard.internal.W3CClipboard
import io.github.aaroncutress.clipboard.internal.WebFormats
import io.github.aaroncutress.clipboard.internal.awaitOrThrow
import io.github.aaroncutress.clipboard.internal.hasFullClipboardApi
import io.github.aaroncutress.clipboard.internal.isSecureContext
import io.github.aaroncutress.clipboard.internal.newBlob
import io.github.aaroncutress.clipboard.internal.newClipboardItem
import io.github.aaroncutress.clipboard.internal.newClipboardRecord
import io.github.aaroncutress.clipboard.internal.setClipboardEntry
import io.github.aaroncutress.clipboard.internal.toByteArray
import io.github.aaroncutress.clipboard.internal.toInt8Array
import io.github.aaroncutress.clipboard.internal.toKotlinString
import io.github.aaroncutress.clipboard.internal.toList
import io.github.aaroncutress.clipboard.internal.w3cClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.promise
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.toJsString

/**
 * The browser clipboard, over the Async Clipboard API.
 *
 * One implementation for both web targets. `kotlin.js.Promise`, `JsAny` and the
 * `js(…)` function body all work identically from Kotlin/JS and Kotlin/Wasm as
 * of Kotlin 2.4, so the interop lives in the shared `webMain` source set rather
 * than being written twice.
 */
internal actual fun CompositionLocalAccessorScope.platformRichClipboard(): RichClipboard =
    WebRichClipboard

/**
 * The `navigator.clipboard` object this [RichClipboard] talks to, or null.
 *
 * Null in a browser with no Clipboard API, on an insecure origin, and on a
 * [RichClipboard] that is not the web one.
 *
 * Typed as [JsAny] because there is no correct static type to give it: the IDL
 * in `kotlinx-browser` is wrong for `Clipboard`, which is why this library
 * declares its own interface internally rather than using it. Cast it to your
 * own external interface.
 */
val RichClipboard.w3cClipboard: JsAny?
    get() = if (this is WebRichClipboard) w3cClipboard() else null

internal object WebRichClipboard : RichClipboard {

    override val capabilities: ClipboardCapabilities by lazy {
        val full = hasFullClipboardApi()
        val secure = isSecureContext()
        ClipboardCapabilities(
            available = w3cClipboard() != null && secure,
            // Everything richer than text needs `ClipboardItem`, which Firefox
            // did not ship until 127.
            writesImages = full,
            // A page can read a pasted file's bytes and has no way to offer
            // one. There is no API for it, not a permission standing in the way.
            writesFiles = false,
            readsFilePaths = false,
            // No browser accepts `text/rtf` on `clipboard.write`. Write HTML
            // instead: every RTF-consuming application reads it.
            writesRtf = false,
            // Through Chrome's `web ` prefix — see `WebFormats`. Reaches other
            // web pages, not native applications.
            writesCustomFormats = full,
            // `clipboard.write` takes an array and every browser ignores
            // everything past the first entry.
            multipleItems = false,
            readsWithoutUserGesture = false,
            observesChanges = false,
        )
    }

    /**
     * Always null.
     *
     * There is no way to ask a browser what is on the clipboard. `read()` is the
     * only route to the answer and it is the same permission-gated, gesture-gated
     * call as reading the contents, so a `peek` that worked would be a `read`
     * wearing a hat — and would put a permission prompt in front of a user who
     * only moved the window focus.
     *
     * So a paste button on the web cannot be disabled ahead of time. Leave it
     * enabled, do the read in its `onClick`, and handle
     * [ClipboardAccessDeniedException]; [ClipboardCapabilities.readsWithoutUserGesture]
     * is false here to say exactly that.
     */
    override suspend fun peek(): ClipInfo? = null

    override suspend fun read(): Clip? {
        val clipboard = require()
        if (!hasFullClipboardApi()) {
            // Firefox before 127, and anything else with only the text half of
            // the API. Text is better than nothing and is what the caller
            // usually wanted.
            val text = clipboard.readText().awaitOrThrow().toKotlinString()
            if (text.isEmpty()) return null
            val bytes = text.encodeToByteArray()
            return Clip(null, listOf(ClipItem(setOf(ClipFormat.PlainText)) { bytes }))
        }

        val entries = clipboard.read().awaitOrThrow().toList()
        if (entries.isEmpty()) return null

        val items = entries.mapNotNull { entry ->
            val types = entry.types.toList().map { it.toKotlinString() }
            if (types.isEmpty()) return@mapNotNull null
            val byFormat = types.associateBy { WebFormats.formatFor(it) }
            ClipItem(formats = byFormat.keys) { format ->
                val type = byFormat[format] ?: return@ClipItem null
                entry.getType(type.toJsString()).awaitOrThrow().toByteArray()
            }
        }
        return if (items.isEmpty()) null else Clip(label = null, items = items)
    }

    override suspend fun write(clip: Clip?) {
        val clipboard = require()
        if (clip == null) {
            // There is no "clear the clipboard" in the web API. An empty string
            // is the closest thing, and it is what every web application that
            // offers a clear button does.
            clipboard.writeText("".toJsString()).awaitOrThrow()
            return
        }

        val item = clip.items.first()
        if (!hasFullClipboardApi()) {
            val text = item.bytes(ClipFormat.PlainText)?.decodeToString()
                ?: throw ClipboardUnavailableException(
                    "This browser has no ClipboardItem, so it can only carry plain text, " +
                        "and this clip has none. Firefox before 127 is the usual cause; " +
                        "RichClipboard.capabilities.writesImages says so ahead of time."
                )
            clipboard.writeText(text.toJsString()).awaitOrThrow()
            return
        }

        // ### Why the blobs are promises
        //
        // Safari only honours `clipboard.write` while the page still has user
        // activation, and an `await` in between spends it — so encoding a PNG
        // before calling `write` is exactly the thing that makes a copy button
        // work in Chrome and fail in Safari. Handing `ClipboardItem` a promise
        // per type moves the encoding to *after* the call, which is what the
        // API is shaped for and the reason `ClipScope.bytes` takes a producer
        // rather than a `ByteArray`.
        //
        // `coroutineScope` rather than a scope of this object's own: the
        // promises are children of the caller's job, so a cancelled copy
        // cancels the encoding with it.
        coroutineScope {
            val record = newClipboardRecord()
            for (format in item.formats) {
                val type = WebFormats.typeFor(format)
                setClipboardEntry(record, type, blobPromise(item, format, type))
            }
            val items = JsArray<JsAny>()
            items[0] = newClipboardItem(record)
            clipboard.write(items).awaitOrThrow()
        }
    }

    private fun CoroutineScope.blobPromise(item: ClipItem, format: ClipFormat, type: String) =
        promise {
            val bytes = item.bytes(format) ?: ByteArray(0)
            newBlob(bytes.toInt8Array(), type)
        }

    private fun require(): W3CClipboard {
        if (!isSecureContext()) {
            throw ClipboardUnavailableException(
                "The Clipboard API needs a secure context. This page is on http://, and " +
                    "browsers expose the API only over https (localhost excepted). " +
                    "Nothing in code can work around it."
            )
        }
        return w3cClipboard() ?: throw ClipboardUnavailableException(
            "This browser has no navigator.clipboard at all."
        )
    }
}
