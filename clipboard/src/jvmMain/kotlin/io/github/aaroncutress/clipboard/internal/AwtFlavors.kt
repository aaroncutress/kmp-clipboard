package io.github.aaroncutress.clipboard.internal

import io.github.aaroncutress.clipboard.ClipFile
import io.github.aaroncutress.clipboard.ClipFormat
import io.github.aaroncutress.clipboard.formatForFileName
import java.awt.GraphicsEnvironment
import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The system clipboard, or null on a headless JVM.
 *
 * Looked up once. `Toolkit.getDefaultToolkit()` is not merely expensive on a
 * machine with no display — it throws, and on some JDK builds it throws an
 * `AWTError` rather than a `HeadlessException`, which is why the guard below
 * asks `GraphicsEnvironment` first instead of relying on catching the right
 * thing.
 */
internal val systemClipboard: Clipboard? by lazy {
    if (GraphicsEnvironment.isHeadless()) return@lazy null
    try {
        Toolkit.getDefaultToolkit().systemClipboard
    } catch (_: HeadlessException) {
        null
    }
}

/**
 * The translation between `DataFlavor` and [ClipFormat].
 *
 * `DataFlavor` carries a MIME type *and* a Java representation class, and the
 * class is half the meaning: `text/plain; class=java.lang.String` and
 * `text/plain; class=java.io.InputStream` are the same content offered two ways,
 * and `application/x-java-serialized-object; class=java.lang.String` — which is
 * what `DataFlavor.stringFlavor` actually is — has a MIME type that says nothing
 * about text at all.
 *
 * So this cannot be a table of MIME strings. It is a table of intentions.
 */
internal object AwtFlavors {

    /**
     * AWT's own transport type, which is not a clipboard format.
     *
     * `DataFlavor.stringFlavor` wears it, and so does whatever the X11 selection
     * hands back for it. Skipped in both spellings; the text it carries is
     * reported as [ClipFormat.PlainText] separately.
     */
    private val SERIALIZED_OBJECT = ClipFormat(DataFlavor.javaSerializedObjectMimeType)

    /**
     * `image/x-java-image`, holding a live `java.awt.Image`.
     *
     * Reported to callers as [ClipFormat.Png] because that is what they can do
     * something with, and because every application that puts an image on a
     * desktop clipboard offers this flavour — often *only* this flavour.
     */
    private val ImageFlavor = DataFlavor.imageFlavor

    /** `text/html`, as a String. What browsers and word processors read. */
    val HtmlString: DataFlavor = DataFlavor("text/html;class=java.lang.String", "HTML")

    /** `text/rtf`, as bytes. RTF is defined as an ASCII byte stream, not as text. */
    val RtfStream: DataFlavor = DataFlavor("text/rtf;class=java.io.InputStream", "Rich Text")

    /** `text/uri-list`, as a String. How Linux desktops carry file drags. */
    val UriListString: DataFlavor = DataFlavor("text/uri-list;class=java.lang.String", "URI list")

    /**
     * Which formats a set of flavours can satisfy.
     *
     * The order of these branches is the whole content of the function, and two
     * of them are ordered the way they are because of a specific defect.
     *
     * `DataFlavor.stringFlavor` has to come first. Its MIME type is
     * `application/x-java-serialized-object; class=java.lang.String` — AWT's
     * transport, saying nothing about text — but `isFlavorTextType()` answers
     * *true* for it, because the javadoc defines a text flavour as "equivalent
     * to `DataFlavor.stringFlavor`, **or** primary type text". So a text branch
     * placed above it parses that MIME type and reports
     * `application/x-java-serialized-object` as a clipboard format. That is a
     * Java implementation detail surfacing in an API whose contract is that
     * formats are MIME types, and it was visible in the demo's clipboard
     * inspector next to `text/html` and `image/png`.
     *
     * The serialized-object branch then catches the *other* spelling: reading
     * back through the X11 selection produces the same MIME type with a
     * representation class of `InputStream`, which is neither `stringFlavor` nor
     * a text type. Matched by parsing the MIME string, because
     * `isFlavorSerializedObjectType` additionally requires the representation
     * class to be Serializable and answers false for that one.
     */
    fun formatsOf(flavors: Array<DataFlavor>): Set<ClipFormat> = buildSet {
        for (flavor in flavors) {
            when {
                flavor == DataFlavor.stringFlavor -> add(ClipFormat.PlainText)
                ClipFormat.parse(flavor.mimeType) == SERIALIZED_OBJECT -> Unit
                flavor.isFlavorTextType -> add(ClipFormat.parse(flavor.mimeType))
                flavor == ImageFlavor -> add(ClipFormat.Png)
                flavor == DataFlavor.javaFileListFlavor -> add(ClipFormat.UriList)
                else -> add(ClipFormat.parse(flavor.mimeType))
            }
        }
    }

    /** The files a transferable offers, or empty. */
    fun filesOf(contents: Transferable): List<ClipFile> {
        if (!contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return emptyList()
        val files = try {
            @Suppress("UNCHECKED_CAST")
            contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
        } catch (_: UnsupportedFlavorException) {
            null
        } catch (_: IOException) {
            null
        } ?: return emptyList()

        return files.map { file ->
            ClipFile(
                name = file.name,
                format = formatForFileName(file.name),
                // `length()` is a stat, not a read: cheap, and 0 for a file that
                // is not there, which is reported as null rather than as an
                // empty file.
                size = file.length().takeIf { it > 0 },
                path = file.absolutePath,
                load = { file.readBytes() },
            )
        }
    }

    /**
     * Reads [format] out of [contents], or null if it is not there.
     *
     * Blocking, and called on [kotlinx.coroutines.Dispatchers.IO] for that
     * reason: the transfer is a round trip to whichever application owns the
     * clipboard, and on X11 that application may simply not answer.
     */
    fun read(contents: Transferable, format: ClipFormat): ByteArray? {
        // Text first, and by intention rather than by MIME type, because
        // `stringFlavor` does not advertise itself as text.
        if (format == ClipFormat.PlainText) {
            transfer(contents, DataFlavor.stringFlavor)?.let { return asBytes(it) }
        }
        if (format == ClipFormat.UriList &&
            contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
        ) {
            val files = filesOf(contents)
            if (files.isNotEmpty()) {
                return files.mapNotNull { it.path }
                    .joinToString("\n") { File(it).toURI().toString() }
                    .encodeToByteArray()
            }
        }
        if (format.category == "image") {
            // An image on a desktop clipboard is nearly always the live
            // `java.awt.Image` flavour and nothing else, so it is encoded here
            // rather than reported as absent.
            transfer(contents, ImageFlavor)?.let { image ->
                return AwtImages.encode(image, format)
            }
        }

        // Everything else by MIME type, preferring a representation this can
        // turn into bytes without guessing an encoding.
        val candidates = contents.transferDataFlavors.orEmpty()
            .filter { ClipFormat.parse(it.mimeType) == format }
            .sortedBy { flavor ->
                when {
                    flavor.representationClass == ByteArray::class.java -> 0
                    InputStream::class.java.isAssignableFrom(flavor.representationClass) -> 1
                    flavor.representationClass == String::class.java -> 2
                    else -> 3
                }
            }
        for (flavor in candidates) {
            transfer(contents, flavor)?.let { return asBytes(it) }
        }
        return null
    }

    private fun transfer(contents: Transferable, flavor: DataFlavor): Any? =
        if (!contents.isDataFlavorSupported(flavor)) {
            null
        } else {
            try {
                contents.getTransferData(flavor)
            } catch (_: UnsupportedFlavorException) {
                // The flavour was advertised and then refused. Owning
                // applications do this while shutting down; there is nothing to
                // report and nothing to retry.
                null
            } catch (_: IOException) {
                null
            }
        }

    private fun asBytes(data: Any): ByteArray? = when (data) {
        is ByteArray -> data
        is String -> data.encodeToByteArray()
        is InputStream -> data.use { it.readBytes() }
        is CharSequence -> data.toString().encodeToByteArray()
        else -> null
    }
}
