@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.aaroncutress.clipboard.internal

import io.github.aaroncutress.clipboard.ClipFormat
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import org.w3c.files.Blob
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsString
import kotlin.js.Promise

/**
 * The Async Clipboard API, declared here rather than taken from `kotlinx-browser`.
 *
 * The IDL that ships with `kotlinx-browser` is wrong for `Clipboard`
 * (Kotlin/kotlinx-browser#14) — Compose declares its own for the same reason.
 * These are the four methods this library uses and no more.
 */
internal external interface W3CClipboard : JsAny {
    fun read(): Promise<JsArray<W3CClipboardItem>>
    fun write(items: JsArray<JsAny>): Promise<JsAny?>
    fun readText(): Promise<JsString>
    fun writeText(text: JsString): Promise<JsAny?>
}

/** One entry of the clipboard, as a set of MIME types over lazily fetched blobs. */
internal external interface W3CClipboardItem : JsAny {
    val types: JsArray<JsString>
    fun getType(type: JsString): Promise<Blob>
}

/** `navigator.clipboard`, or null in a browser that has none. */
internal fun w3cClipboard(): W3CClipboard? =
    js("(window.navigator && window.navigator.clipboard) || null")

/**
 * Whether `ClipboardItem`, `read()` and `write()` are all present.
 *
 * Firefox shipped `readText`/`writeText` years before the rest, and versions
 * before 127 have no `ClipboardItem` at all — so "there is a clipboard" and
 * "this browser can carry an image" are two different questions.
 */
internal fun hasFullClipboardApi(): Boolean = js(
    """Boolean(
        window.navigator &&
        window.navigator.clipboard &&
        window.navigator.clipboard.read &&
        window.navigator.clipboard.write &&
        typeof(ClipboardItem) !== 'undefined'
    )"""
)

/**
 * Whether the page is a secure context.
 *
 * The Clipboard API does not exist on plain `http://`, with the usual exception
 * for `localhost` — which is why it works in development and disappears on
 * deployment to an origin somebody forgot to put behind TLS.
 */
internal fun isSecureContext(): Boolean = js("window.isSecureContext === true")

internal fun newClipboardRecord(): JsAny = js("({})")

internal fun setClipboardEntry(record: JsAny, type: String, value: JsAny) {
    js("record[type] = value")
}

internal fun newClipboardItem(record: JsAny): JsAny = js("new ClipboardItem(record)")

internal fun newBlob(data: Int8Array, type: String): Blob =
    js("new Blob([data], { type: type })")

internal fun blobBuffer(blob: Blob): Promise<ArrayBuffer> = js("blob.arrayBuffer()")

/** A blob's contents. */
internal suspend fun Blob.toByteArray(): ByteArray {
    val view = Int8Array(blobBuffer(this).awaitOrThrow())
    return ByteArray(view.length) { view[it] }
}

/** Bytes as a typed array the `Blob` constructor will accept. */
internal fun ByteArray.toInt8Array(): Int8Array {
    val out = Int8Array(size)
    for (index in indices) out[index] = this[index]
    return out
}

/**
 * A `JsArray` as a Kotlin list.
 *
 * The suppression is the whole reason this exists as a function. `JsArray.get`
 * returns `T?` on Wasm and `T` on JS — `JsString` is an alias for `String`
 * there — so a null check that is necessary on one target is a warning on the
 * other, and warnings are errors in this module. One suppressed spot beats one
 * per call site, and beats splitting `webMain` into two source sets over it.
 */
@Suppress("USELESS_ELVIS", "SENSELESS_COMPARISON")
internal fun <T : JsAny> JsArray<T>.toList(): List<T> = buildList {
    for (index in 0 until length) {
        add(this@toList[index] ?: continue)
    }
}

/**
 * A `JsString` as a Kotlin string.
 *
 * Same story as [toList]: on Wasm `JsString` is a distinct type that has to be
 * converted, and on JS it *is* `String`, so the conversion the one target
 * requires is a redundant call on the other — and warnings are errors here. One
 * suppressed function, rather than one per call site.
 */
@Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")
internal fun JsString.toKotlinString(): String = toString()

/**
 * The MIME types a browser will carry, and what to do about the rest.
 *
 * Chrome, Safari and Firefox all restrict `clipboard.write` to a short list of
 * "sanitized" types — the browser parses and re-serialises them, which is what
 * stops a page putting hostile markup on the system clipboard. Anything outside
 * the list is dropped silently unless it is prefixed `web `, Chrome's opt-in for
 * custom formats. A `web `-prefixed type reaches other web pages and is invisible
 * to native applications, which is a real limit and better than the alternative
 * of the format vanishing with no explanation.
 */
internal object WebFormats {

    private val SANITIZED = setOf(
        ClipFormat.PlainText.mimeType,
        ClipFormat.Html.mimeType,
        ClipFormat.Png.mimeType,
        ClipFormat.Svg.mimeType,
    )

    private const val CUSTOM_PREFIX = "web "

    /** The type to write [format] under. */
    fun typeFor(format: ClipFormat): String =
        if (format.mimeType in SANITIZED) format.mimeType else CUSTOM_PREFIX + format.mimeType

    /** The format a clipboard type means. */
    fun formatFor(type: String): ClipFormat =
        ClipFormat.parse(type.removePrefix(CUSTOM_PREFIX))
}
