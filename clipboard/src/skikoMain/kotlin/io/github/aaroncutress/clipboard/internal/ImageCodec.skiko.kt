package io.github.aaroncutress.clipboard.internal

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.github.aaroncutress.clipboard.ClipFormat
import io.github.aaroncutress.clipboard.ClipboardFormatException
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The codec for every target that draws with Skia: desktop, iOS, and the web.
 *
 * Skiko is already on the classpath — it is what Compose renders with on all
 * three — so this costs nothing beyond the code, and it is the same encoder the
 * toolkit uses to put pixels on the screen. The alternative was `ImageIO` on the
 * JVM, `UIImagePNGRepresentation` on iOS and a canvas round trip on the web:
 * three implementations, three sets of edge cases, one behaviour.
 */
internal actual object ImageCodec {

    actual fun encode(image: ImageBitmap, format: ClipFormat): ByteArray {
        val skiaFormat = when (format) {
            ClipFormat.Png -> EncodedImageFormat.PNG
            ClipFormat.Jpeg -> EncodedImageFormat.JPEG
            ClipFormat("image/webp") -> EncodedImageFormat.WEBP
            else -> throw ClipboardFormatException(
                "$format is not an image format this library can encode. " +
                    "Use ClipFormat.Png or ClipFormat.Jpeg, or pass the bytes " +
                    "yourself with `bytes(format) { … }`."
            )
        }
        // `encodeToData` returns null when Skia declines — an empty bitmap, or a
        // format the build was compiled without. Reported rather than passed on
        // as an empty ByteArray, which would put a zero-byte "image" on the
        // clipboard and fail somewhere much less obvious.
        val data = Image.makeFromBitmap(image.asSkiaBitmap())
            .encodeToData(skiaFormat, JPEG_QUALITY)
            ?: throw ClipboardFormatException(
                "Skia declined to encode a ${image.width}×${image.height} image as $format."
            )
        return data.bytes
    }

    actual fun decode(bytes: ByteArray): ImageBitmap? =
        // `makeFromEncoded` throws rather than returning null on bytes it cannot
        // read, and "these are not an image" is a question this function is
        // asked routinely — so the throw is turned back into the null the
        // signature promises.
        runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}
