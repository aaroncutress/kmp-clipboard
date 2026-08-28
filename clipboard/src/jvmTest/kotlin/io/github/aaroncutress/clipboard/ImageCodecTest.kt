package io.github.aaroncutress.clipboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import io.github.aaroncutress.clipboard.internal.AwtFlavors
import io.github.aaroncutress.clipboard.internal.AwtImages
import io.github.aaroncutress.clipboard.internal.ClipTransferable
import io.github.aaroncutress.clipboard.internal.ImageCodec
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import androidx.compose.ui.graphics.asSkiaBitmap
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The image codec, on the Skia implementation the JVM, iOS and web targets share.
 *
 * Running here covers three of the five targets' encoder — `skikoMain` is one
 * source set — which is most of the value a test on this machine can deliver.
 * Android's `Bitmap`-based one is the odd one out and needs a device.
 */
class ImageCodecTest {

    private fun redSquare(size: Int = 8): ImageBitmap {
        val bitmap = ImageBitmap(size, size)
        Canvas(bitmap.asSkiaBitmap()).drawRect(
            Rect.makeWH(size.toFloat(), size.toFloat()),
            Paint().apply { color = 0xFFFF0000.toInt() },
        )
        return bitmap
    }

    @Test
    fun `png survives a round trip pixel for pixel`() {
        val encoded = ImageCodec.encode(redSquare(), ClipFormat.Png)
        // The PNG magic number, so a failure says "not a PNG" rather than
        // "some bytes differ".
        assertContentStartsWith(byteArrayOf(0x89.toByte(), 'P'.code.toByte()), encoded)

        val decoded = ImageCodec.decode(encoded)!!
        assertEquals(8, decoded.width)
        assertEquals(8, decoded.height)
        assertEquals(Color(0xFFFF0000), decoded.toPixelMap()[4, 4])
    }

    @Test
    fun `jpeg encodes and decodes`() {
        // Lossy, so the assertion is that it is roughly red, not exactly red.
        val decoded = ImageCodec.decode(ImageCodec.encode(redSquare(), ClipFormat.Jpeg))!!
        val pixel = decoded.toPixelMap()[4, 4]
        assertTrue(pixel.red > 0.9f && pixel.green < 0.1f, "$pixel is not red")
    }

    @Test
    fun `an unencodable format is reported rather than guessed at`() {
        val failure = kotlin.runCatching { ImageCodec.encode(redSquare(), ClipFormat.Pdf) }
        assertTrue(failure.exceptionOrNull() is ClipboardFormatException)
    }

    @Test
    fun `bytes that are not an image decode to null`() {
        assertNull(ImageCodec.decode("this is not a png".encodeToByteArray()))
    }

    @Test
    fun `an image reaches the clipboard as both imageFlavor and png bytes`() = runTest {
        // Both, because desktop applications are split on which they read:
        // an editor takes the live `java.awt.Image`, a browser takes the encoded
        // stream, and offering one loses half of them.
        val clip = ClipScope().apply { image(redSquare()) }.build()
        val clipboard = Clipboard("kmp-clipboard test")
        clipboard.setContents(ClipTransferable.of(clip), null)
        val contents = clipboard.getContents(null)

        assertTrue(contents.isDataFlavorSupported(DataFlavor.imageFlavor))
        val png = AwtFlavors.read(contents, ClipFormat.Png)!!
        assertContentStartsWith(byteArrayOf(0x89.toByte(), 'P'.code.toByte()), png)
    }

    @Test
    fun `an awt image round trips through the image flavour`() {
        val encoded = ImageCodec.encode(redSquare(), ClipFormat.Png)
        val awt = AwtImages.decode(encoded)!!
        assertEquals(8, awt.width)
        val reEncoded = AwtImages.encode(awt, ClipFormat.Png)!!
        assertEquals(Color(0xFFFF0000), ImageCodec.decode(reEncoded)!!.toPixelMap()[4, 4])
    }

    private fun assertContentStartsWith(prefix: ByteArray, actual: ByteArray) {
        assertTrue(
            actual.size >= prefix.size && actual.take(prefix.size) == prefix.toList(),
            "expected a ${prefix.size}-byte header ${prefix.toList()}, got ${actual.take(prefix.size)}",
        )
    }
}
