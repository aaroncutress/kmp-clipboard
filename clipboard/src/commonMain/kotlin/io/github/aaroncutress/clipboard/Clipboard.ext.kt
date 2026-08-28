package io.github.aaroncutress.clipboard

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.AnnotatedString
import io.github.aaroncutress.clipboard.html.parseHtml
import io.github.aaroncutress.clipboard.internal.ImageCodec

// ---------------------------------------------------------------------------
// Writing
// ---------------------------------------------------------------------------

/**
 * Replaces the clipboard's contents, built by [block].
 *
 * ```kotlin
 * clipboard.write {
 *     html("<b>Total:</b> £4.20", plainText = "Total: £4.20")
 *     image(chart)
 * }
 * ```
 *
 * The trailing-lambda form of [RichClipboard.write], and the one to reach for
 * whenever a clip has more than one representation. See [ClipScope].
 *
 * @param label A description of the clip, shown by Android's clipboard UI and
 *   ignored elsewhere.
 */
suspend fun RichClipboard.write(label: String? = null, block: ClipScope.() -> Unit) {
    write(ClipScope().apply { this.label = label }.apply(block).build())
}

/** Puts plain text on the clipboard. */
suspend fun RichClipboard.setText(text: String) {
    write { text(text) }
}

/**
 * Puts formatted text on the clipboard, as HTML and as plain text.
 *
 * Both, always — an application pasting into a plain-text field asks for
 * `text/plain` and would otherwise get nothing. See [ClipScope.html].
 */
suspend fun RichClipboard.setHtml(html: String, plainText: String = stripTags(html)) {
    write { html(html, plainText) }
}

/**
 * Puts styled text on the clipboard, as HTML and as plain text.
 *
 * The HTML comes from [toHtml][io.github.aaroncutress.clipboard.html.toHtml],
 * which covers the spans [AnnotatedString] can carry and silently drops the
 * rest — see its documentation for the list.
 */
suspend fun RichClipboard.setAnnotatedString(text: AnnotatedString) {
    write { annotated(text) }
}

/**
 * Puts an image on the clipboard.
 *
 * Check [ClipboardCapabilities.writesImages] first if the platform might be one
 * where this cannot work — an Android build with the bundled file provider
 * disabled is the only such case.
 *
 * @param format [ClipFormat.Png] or [ClipFormat.Jpeg]. PNG by default: it is
 *   lossless, and it is the only image format a browser will accept.
 */
suspend fun RichClipboard.setImage(image: ImageBitmap, format: ClipFormat = ClipFormat.Png) {
    write { image(image, format) }
}

/** Puts a link on the clipboard, as `text/uri-list` and as plain text. */
suspend fun RichClipboard.setUri(uri: String) {
    write { uri(uri) }
}

/**
 * Puts files on the clipboard, one clip item each.
 *
 * Not supported on the web — see [ClipboardCapabilities.writesFiles] — where it
 * throws [ClipboardUnavailableException] rather than writing something that
 * looks like it worked.
 */
suspend fun RichClipboard.setFiles(files: List<ClipFile>) {
    require(files.isNotEmpty()) { "Nothing to copy. To empty the clipboard, call clear()." }
    write { files(files) }
}

/** Empties the clipboard. */
suspend fun RichClipboard.clear() {
    write(null)
}

// ---------------------------------------------------------------------------
// Reading
// ---------------------------------------------------------------------------

/**
 * The clipboard's plain text, or null if there is none.
 *
 * Falls back to flattening HTML when there is HTML and no `text/plain` — rare,
 * because this library always writes both, and not rare at all when the clip
 * came from another application.
 */
suspend fun RichClipboard.getText(): String? {
    val clip = read() ?: return null
    clip.firstOrNull(ClipFormat.PlainText)?.text()?.let { return it }
    return clip.firstOrNull(ClipFormat.Html)?.text(ClipFormat.Html)?.let(::stripTags)
}

/** The clipboard's HTML, or null if there is none. */
suspend fun RichClipboard.getHtml(): String? =
    read()?.firstOrNull(ClipFormat.Html)?.text(ClipFormat.Html)

/**
 * The clipboard's text as an [AnnotatedString].
 *
 * Parses the HTML where there is HTML, and returns the plain text unstyled where
 * there is not — so this is never *worse* than [getText], and the caller does
 * not have to branch on which one the source application offered.
 *
 * The parser handles a documented subset of HTML; see
 * [parseHtml][io.github.aaroncutress.clipboard.html.parseHtml].
 */
suspend fun RichClipboard.getAnnotatedString(): AnnotatedString? {
    val clip = read() ?: return null
    clip.firstOrNull(ClipFormat.Html)?.text(ClipFormat.Html)?.let { return parseHtml(it) }
    return clip.firstOrNull(ClipFormat.PlainText)?.text()?.let(::AnnotatedString)
}

/**
 * The clipboard's image, or null if there is none.
 *
 * Takes the first of `image/png`, `image/jpeg`, `image/gif` and `image/webp`
 * that is present, in that order — PNG first because it is lossless and because
 * on every platform it is the one most likely to be exactly what was copied
 * rather than a re-encode.
 *
 * [ClipFormat.Svg] is deliberately not in the list: it is an image to a user and
 * a document to a decoder, and this library has no renderer. Read it with
 * [ClipItem.text] instead.
 *
 * @throws ClipboardFormatException if there are image bytes and they will not decode.
 */
suspend fun RichClipboard.getImage(): ImageBitmap? {
    val clip = read() ?: return null
    val order = listOf(
        ClipFormat.Png,
        ClipFormat.Jpeg,
        ClipFormat.Gif,
        ClipFormat("image/webp"),
    )
    for (format in order) {
        val bytes = clip.firstOrNull(format)?.bytes(format) ?: continue
        return ImageCodec.decode(bytes)
            ?: throw ClipboardFormatException(
                "The clipboard holds ${bytes.size} bytes of $format that this platform's " +
                    "decoder rejected. The bytes are still readable with ClipItem.bytes()."
            )
    }
    return null
}

/**
 * The links on the clipboard.
 *
 * Reads `text/uri-list` as RFC 2483 says to: one URI per line, blank lines and
 * `#` comments skipped. Empty rather than null when there are none, because a
 * caller nearly always iterates this.
 */
suspend fun RichClipboard.getUris(): List<String> {
    val list = read()?.firstOrNull(ClipFormat.UriList)?.text(ClipFormat.UriList) ?: return emptyList()
    return list.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .toList()
}

/**
 * The files on the clipboard.
 *
 * Empty on the web, always: a browser hands over a pasted file's *bytes* under
 * its MIME type and never says it was a file. Those bytes are still there —
 * `read()?.firstOrNull(ClipFormat.Pdf)` finds them — but there is no file to
 * report. See [ClipboardCapabilities.readsFilePaths].
 */
suspend fun RichClipboard.getFiles(): List<ClipFile> =
    read()?.items?.mapNotNull { it.file }.orEmpty()

// ---------------------------------------------------------------------------
// Asking without reading
// ---------------------------------------------------------------------------

/**
 * Whether there is text to paste.
 *
 * Goes through [RichClipboard.peek], so on Android it does not trip the "pasted
 * from clipboard" toast — which makes it safe to call from anything that runs on
 * focus. On the web it is false until something has read the clipboard, because
 * a browser will not say what is there without a permission-gated read; see
 * [ClipboardCapabilities.readsWithoutUserGesture].
 */
suspend fun RichClipboard.hasText(): Boolean = peek()?.hasText == true

/** Whether there is an image to paste. Quiet on Android, like [hasText]. */
suspend fun RichClipboard.hasImage(): Boolean = peek()?.hasImage == true

/** Whether there are files to paste. Quiet on Android, like [hasText]. */
suspend fun RichClipboard.hasFiles(): Boolean = peek()?.hasFiles == true
