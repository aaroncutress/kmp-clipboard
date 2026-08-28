package io.github.aaroncutress.clipboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalAccessorScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalWithComputedDefaultOf

/**
 * The system clipboard, with everything on it.
 *
 * ```kotlin
 * val clipboard = LocalRichClipboard.current
 * val scope = rememberCoroutineScope()
 *
 * Button(onClick = { scope.launch { clipboard.setImage(chart) } }) { Text("Copy chart") }
 * ```
 *
 * ### Why this exists next to Compose's own `LocalClipboard`
 *
 * Compose's [Clipboard][androidx.compose.ui.platform.Clipboard] hands you a
 * `ClipEntry`, and `ClipEntry` is an `expect class` with no common members worth
 * having: its one common property, `clipMetadata`, is `TODO("not implemented")`
 * everywhere but Android, and on iOS the class has an `internal` constructor, so
 * common code cannot even build one. Text works. Nothing else does, and the
 * workaround is four platform implementations per app.
 *
 * This is those four, written once, behind a type that says what it holds.
 *
 * ### The shape of the API
 *
 * Three levels, and most code only needs the first:
 *
 * 1. **One-liners** — [setText], [getText], [setImage], [getImage], [setHtml],
 *    [getHtml], [setFiles], [getFiles], [clear]. Extension functions, all of them
 *    written against the three members below.
 * 2. **The clip model** — [peek], [read], [write]. Reach for these when one
 *    payload has several representations, or when you care which one you get.
 * 3. **The platform** — `awtClipboard`, `uiPasteboard`, `androidClipboardManager`,
 *    `w3cClipboard`, each declared in its own source set. For everything a
 *    common abstraction over four genuinely different clipboards has to leave
 *    out.
 *
 * Every operation is `suspend`, as Compose's own is: a clipboard read is
 * cross-process on Android, a permission-gated network-shaped promise on the
 * web, and a blocking X11 round trip on Linux. None of those belong on a frame.
 *
 * ### Failure
 *
 * Null means *nothing there*. A refusal — a browser denying a read, a headless
 * JVM with no clipboard — throws [ClipboardException], because the two need
 * different responses and a null that means both is a null nobody can act on.
 * [capabilities] answers the predictable half ahead of time.
 */
@Stable
interface RichClipboard {

    /**
     * What is on the clipboard, without reading it.
     *
     * Cheap everywhere and, on Android, *quiet*: it reads
     * `primaryClipDescription`, which does not trip the "pasted from clipboard"
     * toast that touching the contents does. Use it for anything that runs
     * because the window regained focus.
     *
     * Returns null when the clipboard is empty, and also when the platform
     * declines to say — the web cannot report what is on the clipboard without
     * a permission-gated read, so this is null there until something reads. It
     * does not throw, deliberately: a UI asking "is there anything to paste"
     * every time it is focused cannot usefully handle an exception, and the
     * honest answer to an unanswerable question is "I don't know".
     */
    suspend fun peek(): ClipInfo?

    /**
     * The clipboard's contents, or null when it is empty.
     *
     * Items load lazily — see [ClipItem]. Getting a [Clip] back does not mean
     * anything has been read yet, which is what makes it cheap to ask for one
     * and then decide what you want out of it.
     *
     * @throws ClipboardAccessDeniedException if the platform refused.
     * @throws ClipboardUnavailableException if there is no clipboard here.
     */
    suspend fun read(): Clip?

    /**
     * Replaces the clipboard's contents.
     *
     * @param clip What to put there. Null clears the clipboard — the same thing
     *   [clear] does, spelled for the case where the clip is already in a
     *   nullable variable.
     * @throws ClipboardAccessDeniedException if the platform refused.
     * @throws ClipboardUnavailableException if there is no clipboard here.
     */
    suspend fun write(clip: Clip?)

    /**
     * What the clipboard underneath can actually do.
     *
     * A property rather than a suspending call: it describes the platform, and
     * the platform does not change while the app is running. See
     * [ClipboardCapabilities] for why a UI should ask.
     */
    val capabilities: ClipboardCapabilities
}

/**
 * The clipboard for the current subtree.
 *
 * ```kotlin
 * val clipboard = LocalRichClipboard.current   // no provider, no setup
 * ```
 *
 * **Nothing has to install this.** The default is computed from the platform the
 * composition is running on — on Android from [LocalContext][1], which is how it
 * finds a `Context` without one being passed in. That is the whole point:
 * `LocalClipboard.current` needs no ceremony and neither should this.
 *
 * Provide it to replace the clipboard for a subtree, which is worth doing in
 * exactly two situations:
 *
 * ```kotlin
 * // A test, asserting on what a screen copied.
 * val fake = InMemoryRichClipboard()
 * CompositionLocalProvider(LocalRichClipboard provides fake) { PriceRow(price) }
 *
 * // An Android app with its own FileProvider, or one that has disabled the
 * // bundled one. See AndroidRichClipboard.
 * val clipboard = remember(context) { AndroidRichClipboard(context, authority = MY_AUTHORITY) }
 * CompositionLocalProvider(LocalRichClipboard provides clipboard) { App() }
 * ```
 *
 * ### Why `compositionLocalWithComputedDefaultOf`
 *
 * A plain `staticCompositionLocalOf` cannot do this. Its default is a lambda
 * that runs outside composition, so it cannot read `LocalContext`, and the
 * Android implementation needs a `Context`. The two usual ways out are both
 * worse: an `error("wrap your app in …")` default makes every consumer install a
 * provider to use the library at all, and a `ContentProvider` auto-initialiser
 * would give Android a process-wide `Context` at the price of a component in
 * everybody's manifest for something composition already knows.
 *
 * `compositionLocalWithComputedDefaultOf` computes its default *inside*
 * composition, reading other locals through
 * [CompositionLocalAccessorScope.currentValue]. So the default is the real
 * platform clipboard, and providing over it still works normally.
 *
 * The computation runs on **every read** where nothing is provided, so each
 * platform caches: three return a process singleton and Android returns an
 * instance memoised against the `Context`.
 *
 * [1]: https://developer.android.com/reference/kotlin/androidx/compose/ui/platform/package-summary#LocalContext()
 */
val LocalRichClipboard: ProvidableCompositionLocal<RichClipboard> =
    compositionLocalWithComputedDefaultOf { platformRichClipboard() }

/**
 * Installs [clipboard] for [content] and everything under it.
 *
 * Sugar for `CompositionLocalProvider(LocalRichClipboard provides clipboard)`,
 * and worth having only because it is the one thing anybody ever provides this
 * local for. Nothing needs it to use the library — see [LocalRichClipboard].
 */
@Composable
fun RichClipboardProvider(
    clipboard: RichClipboard,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalRichClipboard provides clipboard, content = content)
}

/**
 * The platform's clipboard, computed inside composition.
 *
 * Each implementation caches: this runs on every read of [LocalRichClipboard]
 * that is not provided over, which is every recomposition of every composable
 * that touches the clipboard.
 */
internal expect fun CompositionLocalAccessorScope.platformRichClipboard(): RichClipboard
