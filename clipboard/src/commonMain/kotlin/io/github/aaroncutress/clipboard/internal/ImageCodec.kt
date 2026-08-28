package io.github.aaroncutress.clipboard.internal

import androidx.compose.ui.graphics.ImageBitmap
import io.github.aaroncutress.clipboard.ClipFormat
import io.github.aaroncutress.clipboard.ClipboardFormatException

/**
 * Turns an [ImageBitmap] into encoded bytes and back.
 *
 * Two implementations, not five. Android encodes through
 * `android.graphics.Bitmap`; desktop, iOS and web all render through Skiko
 * already, so they share one implementation built on `org.jetbrains.skia.Image`
 * in the `skikoMain` source set. That intermediate source set exists for this
 * object and nothing else — see the comment on it in `clipboard/build.gradle.kts`.
 */
internal expect object ImageCodec {

    /**
     * Encodes [image] as [format].
     *
     * @throws ClipboardFormatException if [format] is not one this platform can
     *   encode. [ClipFormat.Png] and [ClipFormat.Jpeg] work everywhere.
     */
    fun encode(image: ImageBitmap, format: ClipFormat): ByteArray

    /**
     * Decodes [bytes], sniffing the format from their header.
     *
     * Null rather than throwing when the bytes are not a decodable image: the
     * caller is usually asking "is this an image", and the answer "no" is not an
     * error. [io.github.aaroncutress.clipboard.getImage] turns it into a
     * [ClipboardFormatException] in the one case where it *is* one — bytes that
     * the clipboard announced as an image and that will not decode.
     */
    fun decode(bytes: ByteArray): ImageBitmap?
}

/** The quality JPEG encoding uses, where the platform lets us choose. */
internal const val JPEG_QUALITY: Int = 90
