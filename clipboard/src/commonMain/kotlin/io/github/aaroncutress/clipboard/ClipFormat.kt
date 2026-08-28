package io.github.aaroncutress.clipboard

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmInline

/**
 * What a piece of clipboard content *is*, named by MIME type.
 *
 * ```kotlin
 * if (ClipFormat.Png in clipboard.peek()?.formats.orEmpty()) {
 *     // there is an image to paste
 * }
 * ```
 *
 * ### Why MIME, when only two of the four platforms use it
 *
 * Android and the web already speak MIME. AWT speaks [DataFlavor][1] and iOS
 * speaks UTI, and both are richer than MIME in ways this library cannot
 * usefully expose: a `DataFlavor` names a *Java class* alongside the type, and
 * a UTI is a point in a conformance hierarchy where `public.png` conforms to
 * `public.image` conforms to `public.data`.
 *
 * MIME is the one vocabulary all four can be mapped onto without inventing a
 * fifth. The mapping is per platform and lives beside each implementation; what
 * it cannot express is reachable through the native handle — `awtClipboard`,
 * `uiPasteboard`, `androidClipboardManager`, `w3cClipboard` — which is the
 * honest place for it, rather than a lossy common abstraction over four things
 * that genuinely differ.
 *
 * ### Custom formats
 *
 * Any MIME string works, and an app copying its own structured data should use
 * one:
 *
 * ```kotlin
 * val Cells = ClipFormat("application/vnd.myapp.cells+json")
 * ```
 *
 * A custom format survives a round trip through this library on every platform,
 * but **not** every platform lets it out of the process. See
 * [ClipboardCapabilities.writesCustomFormats]; on the web in particular a
 * browser will silently drop a type it does not recognise unless it is prefixed
 * `web ` (Chrome's web custom formats), and this library does that prefixing
 * for you.
 *
 * [1]: https://docs.oracle.com/javase/8/docs/api/java/awt/datatransfer/DataFlavor.html
 *
 * @property mimeType The MIME type, lowercase, without parameters — `image/png`,
 *   not `image/PNG` or `text/plain;charset=utf-8`. Normalised by the
 *   constructor, so two spellings of one type are equal and hash alike.
 */
@Immutable
@JvmInline
value class ClipFormat(val mimeType: String) {

    init {
        require(mimeType.isNotBlank()) { "A ClipFormat needs a MIME type." }
    }

    /**
     * The half before the slash — `image`, `text`, `application`.
     *
     * Useful for the question "is there *an* image here", which is more often
     * what a caller means than "is there a PNG here".
     */
    val category: String
        get() = mimeType.substringBefore('/')

    override fun toString(): String = mimeType

    companion object {
        /**
         * Builds a format from a possibly untidy MIME string.
         *
         * Lowercases, trims, and drops parameters: `Text/Plain; charset=UTF-8`
         * becomes `text/plain`. Platforms hand these back in every shape —
         * AWT's HTML flavour is literally `text/html; class=java.lang.String;
         * charset=Unicode` — and a `Set<ClipFormat>` in which `text/plain` and
         * `text/plain;charset=utf-8` are two different entries is a set that
         * makes `hasText()` wrong.
         */
        fun parse(mimeType: String): ClipFormat =
            ClipFormat(mimeType.substringBefore(';').trim().lowercase())

        /** `text/plain`. Every platform. */
        val PlainText: ClipFormat = ClipFormat("text/plain")

        /** `text/html`. Every platform, and the usual carrier of formatted text. */
        val Html: ClipFormat = ClipFormat("text/html")

        /**
         * `text/rtf`. Read on every platform; written on every platform except
         * the web, where no browser accepts it.
         *
         * Worth writing alongside [Html] when the target is an office suite:
         * Word and Pages both prefer RTF to HTML when offered both, and both
         * make a better job of it.
         */
        val Rtf: ClipFormat = ClipFormat("text/rtf")

        /**
         * `text/uri-list`, as defined by RFC 2483 — one URI per line, `#`
         * comments allowed.
         *
         * This is the format for *links*. Files copied out of a file manager
         * arrive as this too, as `file:` URIs; see [ClipFile] for the shape
         * this library hands those back in.
         */
        val UriList: ClipFormat = ClipFormat("text/uri-list")

        /** `image/png`. The lossless one, and the only image type the web will write. */
        val Png: ClipFormat = ClipFormat("image/png")

        /** `image/jpeg`. */
        val Jpeg: ClipFormat = ClipFormat("image/jpeg")

        /** `image/gif`. Read-only in this library: nothing here encodes one. */
        val Gif: ClipFormat = ClipFormat("image/gif")

        /**
         * `image/svg+xml`.
         *
         * Text under the covers, and treated as an image by
         * [ClipInfo.hasImage] because that is what a user means by it — but
         * [getImage][RichClipboard] will not rasterise one, because this
         * library has no renderer.
         */
        val Svg: ClipFormat = ClipFormat("image/svg+xml")

        /** `application/pdf`. */
        val Pdf: ClipFormat = ClipFormat("application/pdf")

        /** `application/octet-stream`, for bytes with nothing better to say. */
        val OctetStream: ClipFormat = ClipFormat("application/octet-stream")
    }
}
