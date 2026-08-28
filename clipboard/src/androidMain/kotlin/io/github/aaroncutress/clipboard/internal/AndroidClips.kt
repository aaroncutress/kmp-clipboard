package io.github.aaroncutress.clipboard.internal

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import io.github.aaroncutress.clipboard.Clip
import io.github.aaroncutress.clipboard.ClipFile
import io.github.aaroncutress.clipboard.ClipFormat
import io.github.aaroncutress.clipboard.ClipInfo
import io.github.aaroncutress.clipboard.ClipItem
import io.github.aaroncutress.clipboard.ClipboardFileProvider
import io.github.aaroncutress.clipboard.formatForFileName
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** A `ClipDescription` as a [ClipInfo]. */
internal fun ClipDescription.toClipInfo(): ClipInfo = ClipInfo(
    label = label?.toString(),
    formats = (0 until mimeTypeCount).mapTo(mutableSetOf()) { ClipFormat.parse(getMimeType(it)) },
)

/**
 * A `ClipData` as a [Clip].
 *
 * Android's model is a description carrying the MIME types for the *whole*
 * clip, and items that each may hold text, HTML text, a URI and an intent. So
 * the formats an individual item offers are not in the data anywhere — they are
 * worked out here from what the item is actually carrying, which is more
 * accurate than the description and is what the lazy loaders below need.
 */
internal fun ClipData.toClip(context: Context): Clip {
    val declared = description.let { descriptor ->
        (0 until descriptor.mimeTypeCount).map { ClipFormat.parse(descriptor.getMimeType(it)) }
    }
    val items = (0 until itemCount).map { index ->
        val item = getItemAt(index)
        item.toClipItem(context, declared)
    }
    return Clip(label = description.label?.toString(), items = items)
}

private fun ClipData.Item.toClipItem(context: Context, declared: List<ClipFormat>): ClipItem {
    val uri = uri
    val uriFormat = uri?.let { context.contentResolver.getType(it) }?.let(ClipFormat::parse)

    val formats = buildSet {
        if (text != null) add(ClipFormat.PlainText)
        if (htmlText != null) add(ClipFormat.Html)
        if (uri != null) {
            // A `content://` URI is a file reference; a `http://` one is a link.
            // The distinction decides whether pasting this somewhere should
            // produce a file or a URL, and it is the scheme that says which.
            if (uri.scheme == "content" || uri.scheme == "file") {
                uriFormat?.let { add(it) }
            }
            add(ClipFormat.UriList)
        }
        // Formats the clip claims and the item does not visibly carry. Kept
        // because a clip written by another application may describe a type its
        // item exposes only through the resolver.
        if (uri != null && uriFormat == null) addAll(declared.filter { it != ClipFormat.PlainText })
    }

    val file = uri?.takeIf { it.scheme == "content" || it.scheme == "file" }
        ?.let { context.clipFileFor(it, uriFormat) }

    return ClipItem(formats = formats, file = file) { format ->
        when {
            format == ClipFormat.PlainText -> text?.toString()?.encodeToByteArray()
            format == ClipFormat.Html -> htmlText?.encodeToByteArray()
            format == ClipFormat.UriList -> uri?.toString()?.encodeToByteArray()
            uri != null -> context.readUri(uri)
            else -> null
        }
    }
}

/** What a `content://` URI says about itself, without reading it. */
private fun Context.clipFileFor(uri: Uri, format: ClipFormat?): ClipFile? {
    var name = uri.lastPathSegment ?: return null
    var size: Long? = null
    // `OpenableColumns` is the contract every well-behaved provider implements
    // and several do not, so the query is allowed to fail and the URI's last
    // path segment stands in for the name.
    runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let { name = cursor.getString(it) }
            cursor.getColumnIndex(OpenableColumns.SIZE)
                .takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let { size = cursor.getLong(it) }
        }
    }
    return ClipFile(
        name = name,
        format = format ?: formatForFileName(name),
        size = size,
        // The URI string, not a filesystem path — see the note on ClipFile.path.
        path = uri.toString(),
        load = { readUri(uri) ?: ByteArray(0) },
    )
}

private fun Context.readUri(uri: Uri): ByteArray? = try {
    contentResolver.openInputStream(uri)?.use { it.readBytes() }
} catch (_: FileNotFoundException) {
    // The clip outlived whatever it pointed at. Common: the source app was
    // killed and its cache swept.
    null
} catch (_: SecurityException) {
    // The read grant that came with the clip has expired, which happens once
    // the clip is no longer the primary one.
    null
} catch (_: IOException) {
    null
}

/**
 * Writes the parts of a clip that Android can only carry as files.
 *
 * Android's clipboard holds text, HTML text and a URI. Everything else — an
 * image, a PDF, an app's own binary format — goes to a file in the app's cache
 * and onto the clipboard as a `content://` URI pointing at it.
 */
internal class ClipboardFiles(
    private val context: Context,
    private val authority: String,
) {

    private val directory: File get() = File(context.cacheDir, DIRECTORY)

    /** Whether anything actually serves [authority]. */
    fun providerExists(): Boolean =
        context.packageManager.resolveContentProvider(authority, 0) != null

    /**
     * One clip item as an Android one, spilling to a file where it has to.
     *
     * An Android item holds at most one URI, so an item offering two binary
     * formats can only carry the first. That is a real limit of the platform
     * rather than of this library: the second format is still readable by
     * anything that round-trips through this library, because the write puts it
     * in the clip's description, but another application will only see the one.
     */
    suspend fun toClipDataItem(item: ClipItem): AndroidItem {
        val text = item.bytes(ClipFormat.PlainText)?.decodeToString()
        val html = item.bytes(ClipFormat.Html)?.decodeToString()

        val binary = item.formats.firstOrNull {
            it != ClipFormat.PlainText && it != ClipFormat.Html && it != ClipFormat.UriList
        }
        val uri = when {
            binary != null -> item.bytes(binary)?.let { spill(it, binary, item.file?.name) }
            // A link, with nothing to write to disk.
            ClipFormat.UriList in item.formats ->
                item.bytes(ClipFormat.UriList)?.decodeToString()
                    ?.lineSequence()?.firstOrNull { it.isNotBlank() && !it.startsWith('#') }
                    ?.let(Uri::parse)
            else -> null
        }

        val mimeTypes = buildList {
            if (html != null) add(ClipDescription.MIMETYPE_TEXT_HTML)
            if (text != null) add(ClipDescription.MIMETYPE_TEXT_PLAIN)
            binary?.let { add(it.mimeType) }
            if (uri != null && binary == null) add(ClipDescription.MIMETYPE_TEXT_URILIST)
        }.ifEmpty { listOf(ClipDescription.MIMETYPE_TEXT_PLAIN) }

        return AndroidItem(ClipData.Item(text, html, null, uri), mimeTypes)
    }

    /**
     * Writes bytes into the served directory and returns their `content://` URI.
     *
     * The name keeps an extension matching the format where there is one, so
     * that the URI still resolves to the right type after this process has been
     * restarted and [ClipboardFileProvider]'s in-memory table is empty.
     */
    private fun spill(bytes: ByteArray, format: ClipFormat, preferredName: String?): Uri? {
        return try {
            directory.mkdirs()
            prune()
            val name = preferredName?.takeIf { it.isNotBlank() && '/' !in it }
                ?: "clip-${System.currentTimeMillis()}${extensionFor(format)}"
            val file = File(directory, name)
            file.writeBytes(bytes)
            ClipboardFileProvider.types[name] = format.mimeType
            FileProvider.getUriForFile(context, authority, file)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            // What `getUriForFile` throws when nothing serves the authority, or
            // when it serves a different directory than the one written to.
            // Reported as "cannot write images" through capabilities rather
            // than thrown, because a text clip alongside it should still land.
            null
        }
    }

    /**
     * Deletes copies older than an hour.
     *
     * These are cache files whose only job is to outlive the copy that made
     * them and be gone before they accumulate. An hour is well past any paste
     * anyone was going to do and short enough that a session of copying
     * screenshots does not fill the cache.
     */
    private fun prune() {
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        directory.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff && file.delete()) {
                ClipboardFileProvider.types.remove(file.name)
            }
        }
    }

    private fun extensionFor(format: ClipFormat): String = when (format) {
        ClipFormat.Png -> ".png"
        ClipFormat.Jpeg -> ".jpg"
        ClipFormat.Gif -> ".gif"
        ClipFormat.Svg -> ".svg"
        ClipFormat.Pdf -> ".pdf"
        ClipFormat.Rtf -> ".rtf"
        else -> ".bin"
    }

    private companion object {
        const val DIRECTORY = "kmp-clipboard"
        const val RETENTION_MILLIS = 60L * 60L * 1000L
    }
}

/** A `ClipData.Item` and the MIME types the clip's description has to declare for it. */
internal class AndroidItem(val item: ClipData.Item, val mimeTypes: List<String>)
