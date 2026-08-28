package io.github.aaroncutress.clipboard.internal

import io.github.aaroncutress.clipboard.Clip
import io.github.aaroncutress.clipboard.ClipFile
import io.github.aaroncutress.clipboard.ClipFormat
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * A [Clip] as something AWT will hand to another application.
 *
 * ### Everything is resolved before this is built
 *
 * `Transferable.getTransferData` is called by the *receiving* application, on
 * its own thread, at paste time — possibly minutes later, possibly after this
 * process has moved on. It is synchronous and it cannot wait on a coroutine, so
 * a lazy `bytes(format) { … }` producer has to have run before the clipboard is
 * handed over. [of] is where that happens, and it is why `write` on desktop
 * costs whatever the largest representation costs even if nobody ever pastes.
 *
 * The other three platforms are the same shape for the same reason, except the
 * web, where a promise is exactly what the API wants.
 */
internal class ClipTransferable private constructor(
    private val data: Map<DataFlavor, Any>,
) : Transferable {

    override fun getTransferDataFlavors(): Array<DataFlavor> = data.keys.toTypedArray()

    override fun isDataFlavorSupported(flavor: DataFlavor?): Boolean = flavor in data

    override fun getTransferData(flavor: DataFlavor?): Any {
        val value = data[flavor] ?: throw UnsupportedFlavorException(flavor)
        // A stream may only be read once, and a receiving application is
        // entitled to ask twice — some ask once to sniff and once to take. So
        // the map holds bytes and a fresh stream is made per request.
        return if (flavor?.representationClass?.let { InputStream::class.java.isAssignableFrom(it) } == true) {
            ByteArrayInputStream(value as ByteArray)
        } else {
            value
        }
    }

    companion object {

        /** What AWT wants for "the clipboard is now empty". */
        val Empty: Transferable = ClipTransferable(emptyMap())

        /**
         * Resolves [clip] and offers it in every flavour a desktop application
         * might ask for.
         *
         * Multi-item clips are flattened: AWT has one transferable with many
         * flavours and no notion of a second item, so the first item's
         * representations are what is offered — except for files, which are
         * gathered across every item, because `javaFileListFlavor` is a list and
         * a three-file copy is the whole point of it.
         */
        suspend fun of(clip: Clip): Transferable {
            val data = mutableMapOf<DataFlavor, Any>()
            val item = clip.items.first()

            for (format in item.formats) {
                val bytes = item.bytes(format) ?: continue
                when (format) {
                    ClipFormat.PlainText ->
                        data[DataFlavor.stringFlavor] = bytes.decodeToString()

                    ClipFormat.Html -> {
                        data[AwtFlavors.HtmlString] = bytes.decodeToString()
                        // Word and LibreOffice ask for the stream form; browsers
                        // ask for the string form. Offering one is offering half.
                        data[DataFlavor("text/html;class=java.io.InputStream;charset=utf-8", "HTML")] = bytes
                    }

                    ClipFormat.Rtf -> data[AwtFlavors.RtfStream] = bytes

                    ClipFormat.UriList -> data[AwtFlavors.UriListString] = bytes.decodeToString()

                    ClipFormat.Png, ClipFormat.Jpeg, ClipFormat.Gif -> {
                        // Both: `imageFlavor` for the applications that only
                        // read a live image, and the encoded stream for the
                        // ones that want the file as it was.
                        AwtImages.decode(bytes)?.let { data[DataFlavor.imageFlavor] = it }
                        data[DataFlavor("${format.mimeType};class=java.io.InputStream", format.mimeType)] = bytes
                    }

                    else ->
                        data[DataFlavor("${format.mimeType};class=java.io.InputStream", format.mimeType)] = bytes
                }
            }

            val files = clip.items.mapNotNull { it.file }
            if (files.isNotEmpty()) {
                data[DataFlavor.javaFileListFlavor] = files.map { it.toJavaFile() }
            }

            return ClipTransferable(data)
        }

        /**
         * The `java.io.File` for a [ClipFile].
         *
         * A file that came off the clipboard has a path and is used where it
         * lies. One built from bytes has none, and a desktop file paste needs a
         * real path to give the receiving application — so the bytes are spilled
         * to a temporary file. It is deleted when the JVM exits rather than
         * after the write, because "after the write" is before the paste.
         */
        private suspend fun ClipFile.toJavaFile(): File {
            path?.let { return File(it) }
            val directory = File(System.getProperty("java.io.tmpdir"), "kmp-clipboard")
            directory.mkdirs()
            val target = File(directory, name)
            target.writeBytes(bytes())
            target.deleteOnExit()
            return target
        }
    }
}
