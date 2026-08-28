package io.github.aaroncutress.clipboard

import androidx.compose.runtime.CompositionLocalAccessorScope
import io.github.aaroncutress.clipboard.internal.Utis
import io.github.aaroncutress.clipboard.internal.toByteArray
import io.github.aaroncutress.clipboard.internal.toNSData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.UIKit.UIPasteboard

/**
 * The iOS clipboard, over `UIPasteboard`.
 *
 * A process singleton: `UIPasteboard.generalPasteboard` is one object and there
 * is nothing per-composition to hold.
 */
internal actual fun CompositionLocalAccessorScope.platformRichClipboard(): RichClipboard =
    UIPasteboardRichClipboard

/**
 * The `UIPasteboard` this [RichClipboard] reads and writes, or null.
 *
 * Null on a [RichClipboard] that is not the iOS one.
 *
 * For everything the UTI table cannot reach: a type this library does not map,
 * `UIPasteboard.detectPatternsForPatterns` (which tells you a URL or a number is
 * on the pasteboard *without* the "pasted from" banner), item providers, and
 * named pasteboards other than the general one.
 */
val RichClipboard.uiPasteboard: UIPasteboard?
    get() = if (this is UIPasteboardRichClipboard) UIPasteboard.generalPasteboard else null

internal object UIPasteboardRichClipboard : RichClipboard {

    override val capabilities: ClipboardCapabilities = ClipboardCapabilities(
        available = true,
        writesImages = true,
        writesFiles = true,
        readsFilePaths = true,
        writesRtf = true,
        writesCustomFormats = true,
        multipleItems = true,
        readsWithoutUserGesture = true,
        observesChanges = true,
    )

    private val pasteboard: UIPasteboard get() = UIPasteboard.generalPasteboard

    override suspend fun peek(): ClipInfo? {
        // `types` is metadata: it reports what is on the pasteboard without
        // reading any of it, which is what keeps the iOS 16 "pasted from" banner
        // out of a paste button that merely wants to know whether to enable
        // itself.
        // `pasteboardTypes()`, not `types`: Kotlin/Native names Objective-C
        // members after the selector, and `UIPasteboard.types` is Swift's name
        // for `-pasteboardTypes`.
        val types = pasteboard.pasteboardTypes().filterIsInstance<String>()
        if (types.isEmpty()) return null
        val formats = types.mapNotNullTo(mutableSetOf()) { Utis.formatFor(it) }
        if (formats.isEmpty()) return null
        return ClipInfo(label = null, formats = formats)
    }

    override suspend fun read(): Clip? = withContext(Dispatchers.Default) {
        val count = pasteboard.numberOfItems.toInt()
        if (count == 0) return@withContext null

        // One entry per item, each a dictionary of UTI to value. Read once:
        // `items` is a bridged copy, and asking the pasteboard again per format
        // would be a separate access each time.
        val raw = pasteboard.items.filterIsInstance<Map<*, *>>()
        if (raw.isEmpty()) return@withContext null

        val items = raw.mapNotNull { entry ->
            val byFormat = entry.entries
                .mapNotNull { (key, value) ->
                    val type = key as? String ?: return@mapNotNull null
                    val format = Utis.formatFor(type) ?: return@mapNotNull null
                    format to value
                }
                // Two UTIs can map to one format — `public.text` and
                // `public.utf8-plain-text` both mean text/plain — and the first
                // is the one the table prefers.
                .toMap()
            if (byFormat.isEmpty()) return@mapNotNull null
            ClipItem(formats = byFormat.keys.toSet()) { format -> byFormat[format].toBytes() }
        }
        if (items.isEmpty()) return@withContext null
        Clip(label = null, items = items)
    }

    override suspend fun write(clip: Clip?) {
        if (clip == null) {
            // Not `setItems(emptyList())` on its own: assigning an empty item
            // list is how UIPasteboard is emptied, and the cast is what makes
            // the Kotlin/Native binding accept it.
            pasteboard.items = emptyList<Map<Any?, Any?>>()
            return
        }
        // Every representation resolved before the handover. UIPasteboard takes
        // values, not providers — `UIItemProvider` could defer this, at the cost
        // of keeping the source object alive for a paste that may never come.
        val items = clip.items.map { item ->
            buildMap<Any?, Any?> {
                for (format in item.formats) {
                    val bytes = item.bytes(format) ?: continue
                    put(Utis.typeFor(format), bytes.toNSData())
                }
            }
        }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return
        pasteboard.items = items
    }

    /**
     * A pasteboard value as bytes.
     *
     * The dictionary's values are whatever the writing application put there,
     * and UIKit bridges them: text arrives as `NSString`, a link as `NSURL`, and
     * anything binary as `NSData`. All three turn up in practice, so all three
     * are handled rather than only the one this library writes.
     */
    private fun Any?.toBytes(): ByteArray? = when (this) {
        is NSData -> toByteArray()
        is NSURL -> absoluteString?.encodeToByteArray()
        // `NSString` bridges to `kotlin.String` here, so this covers both the
        // string a text item carries and the one a Kotlin caller wrote.
        // `encodeToByteArray` is UTF-8, which is what `public.utf8-plain-text`
        // promises the bytes are.
        is NSString -> toString().encodeToByteArray()
        else -> null
    }
}
