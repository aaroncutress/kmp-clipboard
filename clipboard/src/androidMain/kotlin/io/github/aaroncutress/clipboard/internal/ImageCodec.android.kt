package io.github.aaroncutress.clipboard.internal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.aaroncutress.clipboard.ClipFormat
import io.github.aaroncutress.clipboard.ClipboardFormatException
import java.io.ByteArrayOutputStream

/**
 * The codec for Android, which draws with its own graphics stack rather than
 * with Skia-through-Skiko and so cannot share the implementation the other three
 * targets use.
 */
internal actual object ImageCodec {

    actual fun encode(image: ImageBitmap, format: ClipFormat): ByteArray {
        val compressFormat = when (format) {
            ClipFormat.Png -> Bitmap.CompressFormat.PNG
            ClipFormat.Jpeg -> Bitmap.CompressFormat.JPEG
            ClipFormat("image/webp") -> Bitmap.CompressFormat.WEBP_LOSSLESS
            else -> throw ClipboardFormatException(
                "$format is not an image format this library can encode. " +
                    "Use ClipFormat.Png or ClipFormat.Jpeg, or pass the bytes " +
                    "yourself with `bytes(format) { … }`."
            )
        }
        val out = ByteArrayOutputStream()
        // `compress` returns false rather than throwing — a recycled bitmap, a
        // format the device's encoder was built without. Silently producing an
        // empty ByteArray here would put a zero-byte "image" on the clipboard.
        val ok = image.asAndroidBitmap().compress(compressFormat, JPEG_QUALITY, out)
        if (!ok) {
            throw ClipboardFormatException(
                "Android declined to encode a ${image.width}×${image.height} image as $format."
            )
        }
        return out.toByteArray()
    }

    actual fun decode(bytes: ByteArray): ImageBitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}
