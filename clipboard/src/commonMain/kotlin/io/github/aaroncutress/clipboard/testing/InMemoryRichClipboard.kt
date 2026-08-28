package io.github.aaroncutress.clipboard.testing

import io.github.aaroncutress.clipboard.Clip
import io.github.aaroncutress.clipboard.ClipInfo
import io.github.aaroncutress.clipboard.ClipboardCapabilities
import io.github.aaroncutress.clipboard.LocalRichClipboard
import io.github.aaroncutress.clipboard.RichClipboard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [RichClipboard] that keeps its clip in memory.
 *
 * ```kotlin
 * @Test
 * fun `copies the formatted price`() = runComposeUiTest {
 *     val clipboard = InMemoryRichClipboard()
 *     setContent {
 *         CompositionLocalProvider(LocalRichClipboard provides clipboard) { PriceRow(price) }
 *     }
 *     onNodeWithText("Copy").performClick()
 *     assertEquals("£4.20", runBlocking { clipboard.getText() })
 * }
 * ```
 *
 * Provide it through [LocalRichClipboard] and a test can assert on what a screen
 * copied without a system clipboard, a window or a device in the loop — and
 * without the test suite fighting whatever else on the machine is using the real
 * clipboard, which on a developer's laptop is a genuine source of flakes.
 *
 * ### It is in the main artifact, not a test one
 *
 * A `-testing` sibling would be the tidier arrangement and would cost every
 * consumer a second dependency and a second version to keep in step, for a class
 * that is sixty lines and pulls in nothing. Shipped here, it is also usable from
 * a demo or a preview, which is where it gets used nearly as often as from a
 * test.
 *
 * ### What it pretends about the platform
 *
 * Everything works: [capabilities] reports every capability true, because the
 * point of a fake is to take the platform out of the question. A test that needs
 * to see how a screen behaves where images cannot be written should construct
 * one with [capabilities] set — see the constructor parameter.
 *
 * @param capabilities What this clipboard claims it can do. Everything, by
 *   default.
 */
class InMemoryRichClipboard(
    override val capabilities: ClipboardCapabilities = EverythingWorks,
) : RichClipboard {

    private val mutex = Mutex()
    private var clip: Clip? = null

    /** How many times [write] has been called, cleared or not. */
    var writeCount: Int = 0
        private set

    /** How many times [read] has been called. [peek] does not count. */
    var readCount: Int = 0
        private set

    override suspend fun peek(): ClipInfo? = mutex.withLock { clip?.info }

    override suspend fun read(): Clip? = mutex.withLock {
        readCount++
        clip
    }

    override suspend fun write(clip: Clip?) {
        mutex.withLock {
            writeCount++
            this.clip = clip
        }
    }

    private companion object {
        val EverythingWorks = ClipboardCapabilities(
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
    }
}
