package io.github.aaroncutress.clipboard.internal

import io.github.aaroncutress.clipboard.ClipFormat
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * `java.awt.Image` in and out of encoded bytes.
 *
 * Separate from [io.github.aaroncutress.clipboard.internal.ImageCodec], which
 * converts a Compose `ImageBitmap`. This one exists because AWT's clipboard
 * traffics in `java.awt.Image` — `DataFlavor.imageFlavor` is the flavour every
 * desktop application offers and reads — and going `ImageBitmap` → PNG →
 * `BufferedImage` through Skia and then ImageIO is two encodes where one will do.
 */
internal object AwtImages {

    /** Encodes an AWT image as [format], or null if ImageIO has no writer for it. */
    fun encode(image: Any, format: ClipFormat): ByteArray? {
        val rendered = (image as? Image)?.toBufferedImage() ?: return null
        val writerFormat = when (format) {
            ClipFormat.Png -> "png"
            ClipFormat.Jpeg -> "jpg"
            ClipFormat.Gif -> "gif"
            else -> return null
        }
        val out = ByteArrayOutputStream()
        // JPEG has no alpha channel, and ImageIO's JPEG writer does not drop it
        // quietly — it writes a file whose colours are wrong, or refuses. So an
        // image bound for JPEG is flattened onto white first.
        val source = if (writerFormat == "jpg") rendered.withoutAlpha() else rendered
        return if (ImageIO.write(source, writerFormat, out)) out.toByteArray() else null
    }

    /** Decodes bytes into an AWT image, or null. */
    fun decode(bytes: ByteArray): BufferedImage? =
        runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()

    /**
     * An `Image` as a `BufferedImage`.
     *
     * A clipboard's image flavour often hands back a `ToolkitImage` or another
     * lazily loaded implementation rather than a `BufferedImage`, and ImageIO
     * can only write the latter. Drawing it into one forces the load and gives
     * ImageIO something it understands.
     */
    private fun Image.toBufferedImage(): BufferedImage? {
        if (this is BufferedImage) return this
        val width = getWidth(null)
        val height = getHeight(null)
        // -1 means the image has not loaded its dimensions, which for a
        // clipboard transfer means it never will.
        if (width <= 0 || height <= 0) return null
        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = buffered.createGraphics()
        try {
            graphics.drawImage(this, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return buffered
    }

    private fun BufferedImage.withoutAlpha(): BufferedImage {
        if (transparency == BufferedImage.OPAQUE) return this
        val opaque = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = opaque.createGraphics()
        try {
            graphics.color = java.awt.Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.drawImage(this, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return opaque
    }
}
