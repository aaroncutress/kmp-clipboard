package io.github.aaroncutress.clipboard

import androidx.compose.runtime.CompositionLocalAccessorScope
import io.github.aaroncutress.clipboard.internal.AwtFlavors
import io.github.aaroncutress.clipboard.internal.ClipTransferable
import io.github.aaroncutress.clipboard.internal.systemClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.datatransfer.Clipboard as AwtSystemClipboard
import java.awt.datatransfer.Transferable

/**
 * The desktop clipboard, over `java.awt.datatransfer`.
 *
 * A process singleton: AWT's clipboard is one object per toolkit and there is
 * nothing per-composition to hold.
 */
internal actual fun CompositionLocalAccessorScope.platformRichClipboard(): RichClipboard =
    AwtRichClipboard

/**
 * The AWT clipboard this [RichClipboard] reads and writes, or null.
 *
 * Null on a headless JVM — which is most CI runners — and on a [RichClipboard]
 * that is not the desktop one, such as
 * [InMemoryRichClipboard][io.github.aaroncutress.clipboard.testing.InMemoryRichClipboard].
 *
 * For everything `DataFlavor` can express and MIME cannot: a flavour carrying a
 * live Java object, a `DataFlavor` with a representation class of your own, the
 * `ClipboardOwner` callback that tells you when someone else took ownership.
 */
val RichClipboard.awtClipboard: AwtSystemClipboard?
    get() = if (this is AwtRichClipboard) systemClipboard else null

internal object AwtRichClipboard : RichClipboard {

    override val capabilities: ClipboardCapabilities = ClipboardCapabilities(
        available = systemClipboard != null,
        writesImages = true,
        writesFiles = true,
        readsFilePaths = true,
        writesRtf = true,
        writesCustomFormats = true,
        // AWT's `Transferable` is one item with many flavours, full stop. A
        // multi-item clip is flattened to its first item on write, which is
        // documented rather than silently surprising.
        multipleItems = false,
        readsWithoutUserGesture = true,
        observesChanges = true,
    )

    override suspend fun peek(): ClipInfo? = withContext(Dispatchers.IO) {
        val clipboard = require()
        // `getAvailableDataFlavors` rather than `getContents`: it does not ask
        // the owning application to transfer anything, so this stays cheap even
        // when the clipboard holds a 40 MB image in another process.
        val flavors = runCatching { clipboard.availableDataFlavors }.getOrNull() ?: return@withContext null
        if (flavors.isEmpty()) return@withContext null
        ClipInfo(label = null, formats = AwtFlavors.formatsOf(flavors))
    }

    override suspend fun read(): Clip? = withContext(Dispatchers.IO) {
        val clipboard = require()
        // Two things throw here and mean different things. `IllegalStateException`
        // is the clipboard being held by another process mid-transfer, which is
        // transient and is reported as a refusal so a retry is on the table.
        val contents: Transferable = try {
            clipboard.getContents(null)
        } catch (e: IllegalStateException) {
            throw ClipboardAccessDeniedException(
                "The system clipboard is currently unavailable — another application is " +
                    "holding it. This is usually transient; try again.",
                e,
            )
        } ?: return@withContext null

        val flavors = contents.transferDataFlavors ?: return@withContext null
        if (flavors.isEmpty()) return@withContext null
        val formats = AwtFlavors.formatsOf(flavors)
        if (formats.isEmpty()) return@withContext null

        val files = AwtFlavors.filesOf(contents)
        val item = ClipItem(
            formats = formats,
            // One item per clip on this platform, so a clip of three files is
            // one item whose `file` is the first of them — with all three in the
            // `text/uri-list`. The alternative would be inventing items AWT
            // never had.
            file = files.firstOrNull(),
        ) { format ->
            withContext(Dispatchers.IO) { AwtFlavors.read(contents, format) }
        }
        Clip(label = null, items = listOf(item))
    }

    override suspend fun write(clip: Clip?) {
        val clipboard = require()
        if (clip == null) {
            withContext(Dispatchers.IO) { clipboard.setContents(ClipTransferable.Empty, null) }
            return
        }
        // Resolved before the handover, all of it, because AWT's `Transferable`
        // is synchronous: `getTransferData` is called by the *receiving*
        // application on its own thread and cannot wait on a coroutine. So the
        // laziness this library offers elsewhere stops here, and a
        // `bytes(format) { … }` producer runs during `write` rather than during
        // the paste.
        val transferable = ClipTransferable.of(clip)
        withContext(Dispatchers.IO) {
            clipboard.setContents(transferable, null)
        }
    }

    private fun require(): AwtSystemClipboard = systemClipboard
        ?: throw ClipboardUnavailableException(
            "This JVM has no system clipboard. That means a headless environment — " +
                "no display, which is the default on a CI runner. " +
                "RichClipboard.capabilities.available says so ahead of time."
        )
}
