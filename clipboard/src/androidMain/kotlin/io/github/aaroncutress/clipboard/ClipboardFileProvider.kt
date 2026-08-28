package io.github.aaroncutress.clipboard

import android.net.Uri
import androidx.core.content.FileProvider

/**
 * Serves the files this library puts on the clipboard.
 *
 * Android's clipboard cannot carry bytes — it carries a `content://` URI that
 * the pasting application resolves — so copying an image means having a
 * `ContentProvider` in the copying app. This is it, contributed by the library's
 * manifest at `${applicationId}.kmpclipboard`, which means an app gets working
 * image and file copying without writing or declaring anything.
 *
 * A subclass rather than `FileProvider` itself, for two reasons. It gives the
 * library its own authority, so this cannot collide with a `FileProvider` the
 * app already declares — two components with the same authority is an install
 * failure, not a runtime one, and it would be *this library* that broke the
 * app's build. And it can answer [getType] from what the caller said the bytes
 * were, which the base class cannot: `FileProvider.getType` guesses from the
 * file extension, and there is no extension that means
 * `application/vnd.myapp.cells+json`.
 *
 * ### Turning it off
 *
 * See the comment in this library's `AndroidManifest.xml`. Remove the provider
 * with a `tools:node="remove"` rule and pass your own authority to
 * [AndroidRichClipboard]; everything except [ClipboardCapabilities.writesImages]
 * keeps working if you do neither.
 */
class ClipboardFileProvider : FileProvider(R.xml.kmp_clipboard_paths) {

    /**
     * The type the clip said these bytes were.
     *
     * Falls back to the base class, which guesses from the extension — right for
     * a real file that was copied, and the only thing available for a URI this
     * library did not mint.
     */
    override fun getType(uri: Uri): String? = types[uri.lastPathSegment] ?: super.getType(uri)

    internal companion object {
        /**
         * File name to MIME type, for the files this library has written.
         *
         * Not persisted. A `content://` URI on the clipboard outlives the
         * process that put it there, and after a restart this map is empty and
         * `super.getType` answers from the extension — which is why every file
         * written here gets an extension that matches its type where one exists.
         * The alternative is a database for something whose whole lifetime is
         * "until someone pastes".
         *
         * Bounded by the same pruning that bounds the cache directory; see
         * `ClipboardFiles`.
         */
        val types: MutableMap<String, String> = mutableMapOf()
    }
}
