package io.github.aaroncutress.clipboard.internal

import io.github.aaroncutress.clipboard.ClipFormat
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/**
 * The translation between Apple's uniform type identifiers and [ClipFormat].
 *
 * A UTI is a point in a conformance hierarchy — `public.png` conforms to
 * `public.image` conforms to `public.data` — and MIME is a flat pair of words,
 * so the mapping is a table rather than a transformation.
 *
 * `UTType(identifier:).preferredMIMEType` would do this properly and is not used
 * here: it arrived in iOS 14 and would have to be guarded, it answers null for
 * exactly the types that are not in the table below anyway, and it turns a
 * lookup into a framework call on a path that runs once per format per read.
 *
 * A type this table does not know is passed through when it looks like a MIME
 * type — which is what a clip written by this library uses for a custom format,
 * so an app's own `application/vnd.…` survives a round trip — and skipped when
 * it looks like a UTI, because inventing `application/x-com.apple.iwork.pages`
 * would be inventing a fact. Anything skipped is still reachable through
 * `RichClipboard.uiPasteboard`.
 */
internal object Utis {

    /** The UTI to write [format] under. */
    fun typeFor(format: ClipFormat): String = TO_UTI[format] ?: format.mimeType

    /** The format a pasteboard type means, or null if it means nothing portable. */
    fun formatFor(type: String): ClipFormat? = FROM_UTI[type]
        ?: if ('/' in type) ClipFormat.parse(type) else null

    /**
     * `public.utf8-plain-text` rather than `public.plain-text`.
     *
     * `public.plain-text` is an abstract type whose data may be in any encoding;
     * the UTF-8 one says what the bytes are, and every application that reads
     * text off the pasteboard accepts it. Reading maps both onto `text/plain`.
     */
    private val TO_UTI: Map<ClipFormat, String> = mapOf(
        ClipFormat.PlainText to "public.utf8-plain-text",
        ClipFormat.Html to "public.html",
        ClipFormat.Rtf to "public.rtf",
        ClipFormat.UriList to "public.url",
        ClipFormat.Png to "public.png",
        ClipFormat.Jpeg to "public.jpeg",
        ClipFormat.Gif to "com.compuserve.gif",
        ClipFormat.Svg to "public.svg-image",
        ClipFormat.Pdf to "com.adobe.pdf",
    )

    private val FROM_UTI: Map<String, ClipFormat> = mapOf(
        "public.utf8-plain-text" to ClipFormat.PlainText,
        "public.plain-text" to ClipFormat.PlainText,
        "public.text" to ClipFormat.PlainText,
        "public.utf16-plain-text" to ClipFormat.PlainText,
        "public.html" to ClipFormat.Html,
        "public.rtf" to ClipFormat.Rtf,
        "public.url" to ClipFormat.UriList,
        "public.file-url" to ClipFormat.UriList,
        "public.png" to ClipFormat.Png,
        "public.jpeg" to ClipFormat.Jpeg,
        "com.compuserve.gif" to ClipFormat.Gif,
        "public.svg-image" to ClipFormat.Svg,
        "com.adobe.pdf" to ClipFormat.Pdf,
        "org.webmproject.webp" to ClipFormat("image/webp"),
    )
}

/**
 * `NSData` as a Kotlin [ByteArray].
 *
 * A copy, not a view: the `NSData` belongs to the pasteboard and may be released
 * the moment the next clip arrives, whereas the bytes handed to a caller have to
 * outlive that.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return out
}

/** A Kotlin [ByteArray] as `NSData`. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData {
    // `addressOf(0)` on an empty array is out of bounds, and an empty clip
    // representation is a real thing — an empty text selection, a zero-byte
    // file.
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}
