package io.github.aaroncutress.clipboard

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What is on the clipboard, without what is *in* it.
 *
 * Returned by [RichClipboard.peek]. Reading this is cheap on every platform and
 * — the reason it exists as a separate type — **silent on Android**, where
 * touching the contents shows the user a "pasted from clipboard" toast. A paste
 * button that greys itself out by calling [RichClipboard.read] every time the
 * window regains focus is a paste button that accuses your app of spying.
 *
 * ```kotlin
 * val info = clipboard.peek()
 * PasteButton(enabled = info?.hasImage == true)
 * ```
 *
 * @property label The clipboard's own description of itself, where the platform
 *   has one. Android carries a label on every `ClipData`; the others do not, and
 *   this is null there.
 * @property formats Every format the current contents can be read as, across all
 *   of its items.
 */
@Immutable
class ClipInfo internal constructor(
    val label: String?,
    val formats: Set<ClipFormat>,
) {
    /** Whether the clipboard offers [format]. */
    operator fun contains(format: ClipFormat): Boolean = format in formats

    /**
     * Whether there is text of any kind — plain, HTML, RTF or a URI list.
     *
     * Matches on the `text/` category rather than on [ClipFormat.PlainText],
     * because a platform that has HTML can nearly always flatten it, and
     * [RichClipboard.getText] takes advantage of that.
     */
    val hasText: Boolean
        get() = formats.any { it.category == "text" }

    /** Whether there is an image of any kind. */
    val hasImage: Boolean
        get() = formats.any { it.category == "image" }

    /**
     * Whether there are files.
     *
     * On the web this is false even when a paste would produce file *bytes*:
     * the browser hands over a blob and a name, never a path, so the answer to
     * "are there files here" is genuinely no. The bytes are still readable as
     * whatever format they claim to be.
     */
    val hasFiles: Boolean
        get() = ClipFormat.UriList in formats

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ClipInfo && label == other.label && formats == other.formats)

    override fun hashCode(): Int = 31 * (label?.hashCode() ?: 0) + formats.hashCode()

    override fun toString(): String =
        "ClipInfo(label=$label, formats=${formats.joinToString()})"
}

/**
 * The clipboard's contents: one or more [items][ClipItem], each carrying the
 * same thing in as many representations as whoever copied it offered.
 *
 * Most clips have exactly one item. Multiple items happen when a user selects
 * several files in a file manager, or several rows in a table — Android and iOS
 * model that natively, AWT and the web flatten it, and
 * [ClipboardCapabilities.multipleItems] says which you are on.
 *
 * ### This holds what you have read from it
 *
 * Items load lazily and memoise: asking twice does not go back to the system
 * clipboard twice, which is what makes `hasText()`-then-`getText()` cost one
 * read rather than two. The consequence is that a `Clip` you keep around after
 * reading a 12 MB image out of it is 12 MB you are keeping around. Read what you
 * need and let it go.
 *
 * @property label The clipboard's own description of itself, where the platform
 *   has one — see [ClipInfo.label].
 * @property items The representations, in the order the platform reported them.
 *   Never empty: an empty clipboard is a null [Clip], not a [Clip] with no items.
 */
@Immutable
class Clip internal constructor(
    val label: String?,
    val items: List<ClipItem>,
) {
    init {
        require(items.isNotEmpty()) {
            "A Clip with no items cannot be distinguished from an empty clipboard. " +
                "Return null instead."
        }
    }

    /** Every format across every item. */
    val formats: Set<ClipFormat>
        get() = items.flatMapTo(mutableSetOf()) { it.formats }

    /** This clip, described — the same value [RichClipboard.peek] would return. */
    val info: ClipInfo
        get() = ClipInfo(label, formats)

    /**
     * The first item offering [format], or null if none does.
     *
     * The usual way in: a caller almost always wants "the HTML on the
     * clipboard", not "the HTML on the third item".
     */
    fun firstOrNull(format: ClipFormat): ClipItem? = items.firstOrNull { format in it.formats }

    override fun toString(): String = "Clip(label=$label, items=${items.size}, formats=$formats)"
}

/**
 * One thing on the clipboard, in every representation it was offered in.
 *
 * A screenshot copied from a browser is typically one item that is
 * simultaneously `image/png` and `text/html` (an `<img>` tag pointing at the
 * original URL). Which one you want depends on where it is going, which is why
 * this hands you the choice rather than picking.
 *
 * Content is fetched on demand. That is not a refinement: on the web every
 * representation costs a separate `getType()` round trip, on Android an image is
 * a `content://` URI that has to be opened, and on AWT `getTransferData` can
 * block on a cross-process transfer. Loading all of them to answer "is there
 * text here" would make the cheap question expensive.
 *
 * @property formats Every format this item can be read as.
 * @property file The file this item *is*, when it is one — a file dragged out of
 *   a file manager, or one passed to [ClipItemScope.file]. Null otherwise, which
 *   is the common case. Present so that a platform writing the clip can offer a
 *   real file reference (`java.io.File`, a `content://` URI, a `public.file-url`)
 *   rather than only the bytes, which is the difference between a paste that
 *   lands in a folder and one that lands as an attachment.
 */
@Immutable
class ClipItem internal constructor(
    val formats: Set<ClipFormat>,
    val file: ClipFile? = null,
    private val load: suspend (ClipFormat) -> ByteArray?,
) {
    // Guards `loaded`. Two coroutines asking for the same format concurrently
    // would otherwise both go to the system clipboard, and on the web that is
    // two permission-gated round trips where one would do.
    private val mutex = Mutex()
    private val loaded = mutableMapOf<ClipFormat, ByteArray?>()

    /**
     * This item's bytes in [format], or null if it does not offer that format.
     *
     * Memoised — see the note on [Clip] about what that means for a clip you
     * hold on to.
     *
     * @throws ClipboardAccessDeniedException if the platform refused the read.
     */
    suspend fun bytes(format: ClipFormat): ByteArray? {
        if (format !in formats) return null
        return mutex.withLock {
            if (format in loaded) loaded[format] else load(format).also { loaded[format] = it }
        }
    }

    /**
     * This item's content in [format], decoded as UTF-8.
     *
     * Null when the item does not offer that format. Every text format this
     * library writes is UTF-8, and every platform hands text back as a string
     * that this library encodes as UTF-8 before it reaches here, so the decode
     * is lossless in both directions for anything the library itself wrote.
     * Bytes that arrive from another application in another encoding are the
     * one case it cannot fix, and they come back mojibake rather than throwing.
     */
    suspend fun text(format: ClipFormat = ClipFormat.PlainText): String? =
        bytes(format)?.decodeToString()

    override fun toString(): String = "ClipItem(formats=${formats.joinToString()})"
}

/**
 * A file on the clipboard.
 *
 * What a "file" is differs more between platforms than anything else this
 * library carries, and this type is the smallest thing all four agree on: a
 * name, a type, and bytes you can ask for.
 *
 * - **Desktop** and **iOS** have real paths, and [path] is set.
 * - **Android** has a `content://` URI, which [path] carries verbatim. It is
 *   not a filesystem path and `File(path)` will not open it.
 * - **The web** has neither. A pasted file is a blob with a name; [path] is
 *   null, and [ClipInfo.hasFiles] is false, because there is no file — only its
 *   contents.
 *
 * @property name The file's name, including its extension where there is one.
 * @property format The file's type. Guessed from the extension when the platform
 *   does not say, falling back to [ClipFormat.OctetStream].
 * @property size The file's size in bytes, where the platform reports one
 *   without opening it. Null rather than a lie when it does not.
 * @property path Where the file is, in whatever the platform's terms are — see
 *   above. Null on the web, and on anything that was never a file.
 */
@Immutable
class ClipFile internal constructor(
    val name: String,
    val format: ClipFormat,
    val size: Long?,
    val path: String?,
    private val load: suspend () -> ByteArray,
) {
    private val mutex = Mutex()
    private var loaded: ByteArray? = null

    /**
     * The file's contents. Memoised, like [ClipItem.bytes].
     *
     * Reads the whole file into memory, because that is the only shape all four
     * platforms can offer — a browser has no path to hand you and no stream to
     * open. For a file large enough that this matters, use [path] and the
     * platform's own I/O on the three targets that have one.
     */
    suspend fun bytes(): ByteArray = mutex.withLock {
        loaded ?: load().also { loaded = it }
    }

    override fun toString(): String = "ClipFile(name=$name, format=$format, size=$size)"
}

/**
 * A file to put on the clipboard, from bytes you already have.
 *
 * @param name The name the receiving application should see, extension included.
 * @param bytes The contents.
 * @param format The file's type. Defaults to a guess from the extension.
 */
fun ClipFile(
    name: String,
    bytes: ByteArray,
    format: ClipFormat = formatForFileName(name),
): ClipFile = ClipFile(
    name = name,
    format = format,
    size = bytes.size.toLong(),
    path = null,
    load = { bytes },
)

/**
 * A file to put on the clipboard, produced on demand.
 *
 * Prefer this to the [ByteArray] overload for anything expensive to make. On the
 * web it is also the only shape that works outside a user gesture: Safari
 * accepts a `ClipboardItem` whose values are promises, and rejects one whose
 * values are already-resolved blobs if the gesture has ended.
 *
 * @param name The name the receiving application should see, extension included.
 * @param format The file's type. Defaults to a guess from the extension.
 * @param size The size in bytes, if it is known without producing the contents.
 * @param load Produces the contents. Called at most once per [ClipFile].
 */
fun ClipFile(
    name: String,
    format: ClipFormat = formatForFileName(name),
    size: Long? = null,
    load: suspend () -> ByteArray,
): ClipFile = ClipFile(name = name, format = format, size = size, path = null, load = load)

/**
 * The format a file name suggests.
 *
 * A short table rather than a complete one: the types this library can do
 * something useful with, plus the handful that show up constantly on a
 * clipboard. Anything else is [ClipFormat.OctetStream], which every platform
 * accepts and none interprets.
 */
internal fun formatForFileName(name: String): ClipFormat =
    when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log", "md" -> ClipFormat.PlainText
        "htm", "html" -> ClipFormat.Html
        "rtf" -> ClipFormat.Rtf
        "png" -> ClipFormat.Png
        "jpg", "jpeg" -> ClipFormat.Jpeg
        "gif" -> ClipFormat.Gif
        "svg" -> ClipFormat.Svg
        "pdf" -> ClipFormat.Pdf
        "json" -> ClipFormat("application/json")
        "csv" -> ClipFormat("text/csv")
        "xml" -> ClipFormat("text/xml")
        "webp" -> ClipFormat("image/webp")
        else -> ClipFormat.OctetStream
    }
