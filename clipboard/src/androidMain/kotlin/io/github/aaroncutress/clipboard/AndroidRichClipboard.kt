package io.github.aaroncutress.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.os.Build
import androidx.compose.runtime.CompositionLocalAccessorScope
import androidx.compose.ui.platform.LocalContext
import io.github.aaroncutress.clipboard.internal.ClipboardFiles
import io.github.aaroncutress.clipboard.internal.toClip
import io.github.aaroncutress.clipboard.internal.toClipInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.ClipboardManager as AndroidClipboardManager

/**
 * The Android clipboard, found through composition.
 *
 * `LocalContext` is why [LocalRichClipboard] is a computed-default local rather
 * than a static one: the system service needs a `Context`, composition already
 * has one, and every other way of getting hold of one — a `ContentProvider`
 * initialiser, an `Application` subclass, an `init(context)` call — asks the
 * consumer for something the framework is already holding.
 */
internal actual fun CompositionLocalAccessorScope.platformRichClipboard(): RichClipboard =
    androidRichClipboard(LocalContext.currentValue)

/**
 * The Android clipboard manager this [RichClipboard] talks to, or null.
 *
 * Null on a [RichClipboard] that is not the Android one — an
 * [InMemoryRichClipboard][io.github.aaroncutress.clipboard.testing.InMemoryRichClipboard]
 * in a test, most likely.
 *
 * For what a MIME-shaped API cannot reach: `primaryClipDescription.extras`,
 * `ClipDescription.EXTRA_IS_SENSITIVE` (which is how you keep a password out of
 * the clipboard preview on Android 13 and up), `ClipData.Item.getIntent`, and
 * `OnPrimaryClipChangedListener` if you want the raw callback rather than
 * [rememberClipInfo].
 */
val RichClipboard.androidClipboardManager: AndroidClipboardManager?
    get() = (this as? AndroidRichClipboardImpl)?.manager

/**
 * An Android clipboard using a specific file-provider authority.
 *
 * You do not need this. [LocalRichClipboard] already resolves to an instance
 * backed by the provider this library contributes at
 * `${applicationId}.kmpclipboard`, and images and files work out of the box.
 *
 * Reach for it when that provider is not there — because your app removed it
 * with a manifest merger rule, or because it already has a `FileProvider` and
 * you would rather have one than two:
 *
 * ```kotlin
 * val context = LocalContext.current
 * val clipboard = remember(context) {
 *     AndroidRichClipboard(context, authority = "${context.packageName}.fileprovider")
 * }
 * CompositionLocalProvider(LocalRichClipboard provides clipboard) { App() }
 * ```
 *
 * The authority you pass must serve a directory named `kmp-clipboard` inside the
 * app's cache — a `<cache-path name="…" path="kmp-clipboard/" />` entry in its
 * paths file — because that is where this writes. Copy the one in this library's
 * `res/xml/kmp_clipboard_paths.xml`.
 *
 * @param context Any context. The application context is what gets held, so an
 *   `Activity` passed here is not leaked.
 * @param authority The file provider that will serve copied images and files.
 *   When nothing serves it, everything except images and files still works and
 *   [ClipboardCapabilities.writesImages] reports false.
 */
fun AndroidRichClipboard(
    context: Context,
    authority: String = "${context.packageName}.kmpclipboard",
): RichClipboard = AndroidRichClipboardImpl(context.applicationContext, authority)

/**
 * One clipboard per application context.
 *
 * [LocalRichClipboard]'s default is recomputed on every read — that is how a
 * computed default works — so constructing an instance there would allocate one
 * per recomposition of every composable that touches the clipboard, each with
 * its own file cache bookkeeping.
 *
 * Weak keys because the map outlives an `Activity`-derived context; in practice
 * it holds one entry, because [AndroidRichClipboard] normalises to the
 * application context before it gets here.
 */
private val instances = java.util.WeakHashMap<Context, RichClipboard>()

private fun androidRichClipboard(context: Context): RichClipboard {
    val application = context.applicationContext
    return synchronized(instances) {
        instances.getOrPut(application) { AndroidRichClipboard(application) }
    }
}

internal class AndroidRichClipboardImpl(
    private val context: Context,
    private val authority: String,
) : RichClipboard {

    internal val manager: AndroidClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? AndroidClipboardManager

    private val files = ClipboardFiles(context, authority)

    override val capabilities: ClipboardCapabilities by lazy {
        val hasProvider = files.providerExists()
        ClipboardCapabilities(
            available = manager != null,
            // Both go through the file provider: Android's clipboard carries a
            // `content://` URI, never bytes, and without something to serve that
            // URI there is nothing to put on it.
            writesImages = hasProvider,
            writesFiles = hasProvider,
            readsFilePaths = true,
            writesRtf = hasProvider,
            writesCustomFormats = hasProvider,
            multipleItems = true,
            readsWithoutUserGesture = true,
            // True, but only while the app is in the foreground — which since
            // Android 10 is the only time it is allowed to read the clipboard
            // at all, so the restriction costs nothing this library could have
            // offered anyway.
            observesChanges = true,
        )
    }

    override suspend fun peek(): ClipInfo? {
        // `primaryClipDescription`, not `primaryClip`. The description is the
        // one piece of the clipboard Android lets an app read without showing
        // the user a "pasted from clipboard" toast, and this method exists so
        // that a paste button can ask what is there on every focus change
        // without accusing the app of snooping.
        val description = require().primaryClipDescription ?: return null
        return description.toClipInfo()
    }

    override suspend fun read(): Clip? = withContext(Dispatchers.IO) {
        val data = require().primaryClip ?: return@withContext null
        if (data.itemCount == 0) return@withContext null
        data.toClip(context)
    }

    override suspend fun write(clip: Clip?) {
        val manager = require()
        if (clip == null) {
            withContext(Dispatchers.Main) { clearPrimaryClip(manager) }
            return
        }
        val data = buildClipData(clip)
        // `setPrimaryClip` is main-thread-only in practice: it notifies
        // listeners synchronously, and on several OEM builds it throws from a
        // background thread. Building the ClipData — which writes files — has
        // already happened off it.
        withContext(Dispatchers.Main) { manager.setPrimaryClip(data) }
    }

    private suspend fun buildClipData(clip: Clip): ClipData = withContext(Dispatchers.IO) {
        val items = clip.items.map { files.toClipDataItem(it) }
        val mimeTypes = items.flatMap { it.mimeTypes }.distinct().toTypedArray()
        val description = ClipDescription(
            clip.label ?: mimeTypes.firstOrNull() ?: ClipDescription.MIMETYPE_TEXT_PLAIN,
            mimeTypes,
        )
        val data = ClipData(description, items.first().item)
        items.drop(1).forEach { data.addItem(it.item) }
        data
    }

    private fun clearPrimaryClip(manager: AndroidClipboardManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.clearPrimaryClip()
        } else {
            // Before API 28 there is no way to empty the clipboard, only to
            // replace what is on it. An empty string is the least surprising
            // thing to leave behind.
            manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    private fun require(): AndroidClipboardManager = manager
        ?: throw ClipboardUnavailableException(
            "This device reports no CLIPBOARD_SERVICE. That is not supposed to happen on " +
                "Android; RichClipboard.capabilities.available says so ahead of time."
        )
}
