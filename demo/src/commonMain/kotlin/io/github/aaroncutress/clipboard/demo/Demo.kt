package io.github.aaroncutress.clipboard.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.aaroncutress.clipboard.ClipFile
import io.github.aaroncutress.clipboard.ClipFormat
import io.github.aaroncutress.clipboard.ClipboardException
import io.github.aaroncutress.clipboard.LocalRichClipboard
import io.github.aaroncutress.clipboard.RichClipboard
import io.github.aaroncutress.clipboard.clear
import io.github.aaroncutress.clipboard.getAnnotatedString
import io.github.aaroncutress.clipboard.getFiles
import io.github.aaroncutress.clipboard.getHtml
import io.github.aaroncutress.clipboard.getImage
import io.github.aaroncutress.clipboard.getText
import io.github.aaroncutress.clipboard.getUris
import io.github.aaroncutress.clipboard.rememberClipInfo
import io.github.aaroncutress.clipboard.setAnnotatedString
import io.github.aaroncutress.clipboard.setFiles
import io.github.aaroncutress.clipboard.setImage
import io.github.aaroncutress.clipboard.setText
import io.github.aaroncutress.clipboard.setUri
import io.github.aaroncutress.clipboard.write
import kotlinx.coroutines.launch

/**
 * Every format the library carries, against the real system clipboard.
 *
 * The point is not the UI. It is that you can copy from here and paste into
 * Photoshop, Gmail, Word, Finder and a browser, and paste *from* those into
 * here — which is the only way to find out whether a clipboard library works,
 * because the thing that decides is always the other application.
 */
@Composable
fun ClipboardDemo() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val clipboard = LocalRichClipboard.current
            val scope = rememberCoroutineScope()
            var status by remember { mutableStateOf("Ready.") }
            var pasted by remember { mutableStateOf<AnnotatedString?>(null) }
            var pastedImage by remember { mutableStateOf<ImageBitmap?>(null) }

            /** Runs a clipboard operation and reports whatever comes back, including a refusal. */
            fun run(label: String, block: suspend () -> String) {
                scope.launch {
                    status = try {
                        "$label — ${block()}"
                    } catch (e: ClipboardException) {
                        "$label — refused: ${e.message}"
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("kmp-clipboard", style = MaterialTheme.typography.headlineSmall)

                StatusCard(status)
                WhatIsOnTheClipboard(clipboard)

                Section("Copy") {
                    Button(onClick = {
                        run("plain text") { clipboard.setText(SAMPLE_TEXT); "copied" }
                    }) { Text("Text") }

                    Button(onClick = {
                        run("styled text") { clipboard.setAnnotatedString(sampleStyled()); "copied" }
                    }) { Text("Formatted text") }

                    Button(onClick = {
                        run("image") { clipboard.setImage(sampleImage()); "copied a 96×96 PNG" }
                    }) { Text("Image") }

                    Button(onClick = {
                        run("link") { clipboard.setUri("https://github.com/aaroncutress/kmp-clipboard"); "copied" }
                    }) { Text("Link") }

                    Button(onClick = {
                        run("file") {
                            clipboard.setFiles(listOf(ClipFile("hello.txt", SAMPLE_TEXT.encodeToByteArray())))
                            "copied hello.txt"
                        }
                    }) { Text("File") }

                    Button(onClick = {
                        run("everything at once") {
                            // The shape this library exists for: one payload, four
                            // representations, and whoever pastes takes the one
                            // they understand.
                            clipboard.write(label = "kmp-clipboard demo") {
                                html("<b>Total:</b> <i>£4.20</i>", plainText = "Total: £4.20")
                                image(sampleImage())
                                bytes(CUSTOM) { """{"total":4.20}""".encodeToByteArray() }
                            }
                            "copied text + html + png + ${CUSTOM.mimeType}"
                        }
                    }) { Text("Multi-format") }

                    OutlinedButton(onClick = {
                        run("clear") { clipboard.clear(); "cleared" }
                    }) { Text("Clear") }
                }

                Section("Paste") {
                    Button(onClick = {
                        run("text") { clipboard.getText()?.also { pasted = AnnotatedString(it) }?.let { "${it.length} characters" } ?: "nothing" }
                    }) { Text("Text") }

                    Button(onClick = {
                        run("formatted text") {
                            clipboard.getAnnotatedString()?.also { pasted = it }
                                ?.let { "${it.spanStyles.size} styled spans" } ?: "nothing"
                        }
                    }) { Text("Formatted text") }

                    Button(onClick = {
                        run("html") {
                            clipboard.getHtml()?.also { pasted = AnnotatedString(it) }
                                ?.let { "${it.length} characters of HTML" } ?: "no HTML"
                        }
                    }) { Text("HTML source") }

                    Button(onClick = {
                        run("image") {
                            clipboard.getImage()?.also { pastedImage = it }
                                ?.let { "${it.width}×${it.height}" } ?: "no image"
                        }
                    }) { Text("Image") }

                    Button(onClick = {
                        run("links") { clipboard.getUris().joinToString().ifEmpty { "no links" } }
                    }) { Text("Links") }

                    Button(onClick = {
                        run("files") {
                            clipboard.getFiles()
                                .joinToString { "${it.name} (${it.size ?: "?"} bytes)" }
                                .ifEmpty { "no files" }
                        }
                    }) { Text("Files") }

                    Button(onClick = {
                        run("custom format") {
                            clipboard.read()?.firstOrNull(CUSTOM)?.text(CUSTOM) ?: "no ${CUSTOM.mimeType}"
                        }
                    }) { Text("Custom format") }
                }

                pasted?.let { PastedText(it) }
                pastedImage?.let { PastedImage(it) }

                Capabilities(clipboard)
            }
        }
    }
}

@Composable
private fun StatusCard(status: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(status, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * What [rememberClipInfo] reports, live.
 *
 * The most useful panel here: it shows the formats the clipboard is offering
 * *without* reading them, which is how you find out that the application you
 * copied from writes `text/html` and nothing else, or that a screenshot arrives
 * as PNG and an `<img>` tag at the same time.
 */
@Composable
private fun WhatIsOnTheClipboard(clipboard: RichClipboard) {
    val info by rememberClipInfo(clipboard)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("On the clipboard", style = MaterialTheme.typography.titleSmall)
            when {
                !clipboard.capabilities.observesChanges ->
                    Text(
                        "This platform will not say without a read — see " +
                            "capabilities.readsWithoutUserGesture. Press a Paste button.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                info == null -> Text("Empty.", style = MaterialTheme.typography.bodySmall)
                else -> {
                    info?.label?.let { Text("label: $it", style = MaterialTheme.typography.bodySmall) }
                    Text(
                        info?.formats.orEmpty().joinToString { it.mimeType },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun Capabilities(clipboard: RichClipboard) {
    val capabilities = clipboard.capabilities
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("This platform", style = MaterialTheme.typography.titleSmall)
            listOf(
                "available" to capabilities.available,
                "writes images" to capabilities.writesImages,
                "writes files" to capabilities.writesFiles,
                "reads file paths" to capabilities.readsFilePaths,
                "writes RTF" to capabilities.writesRtf,
                "writes custom formats" to capabilities.writesCustomFormats,
                "multiple items" to capabilities.multipleItems,
                "reads without a gesture" to capabilities.readsWithoutUserGesture,
                "observes changes" to capabilities.observesChanges,
            ).forEach { (name, on) ->
                Text(
                    "${if (on) "yes" else "no "}   $name",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (on) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PastedText(text: AnnotatedString) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Pasted", style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PastedImage(image: ImageBitmap) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Pasted image — ${image.width}×${image.height}", style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.size(160.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

private val CUSTOM = ClipFormat("application/vnd.kmp-clipboard.demo+json")

private const val SAMPLE_TEXT =
    "Copied from the kmp-clipboard demo. Paste this anywhere — a text field, a " +
        "terminal, a document."

private fun sampleStyled(): AnnotatedString = buildAnnotatedString {
    append("This is ")
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("bold") }
    append(", ")
    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append("italic") }
    append(", ")
    withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append("underlined") }
    append(", ")
    withStyle(SpanStyle(color = Color(0xFFCC0033))) { append("coloured") }
    append(" and ")
    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append("monospaced") }
    append(". Paste it into a word processor.")
}

/**
 * A recognisable 96×96 image, drawn in common code.
 *
 * Recognisable on purpose: the point of pasting it into another application is
 * to see whether what arrives is the same picture, and a flat colour cannot tell
 * you whether the rows came out in the right order.
 */
private fun sampleImage(): ImageBitmap {
    val size = 96
    val bitmap = ImageBitmap(size, size)
    val canvas = Canvas(bitmap)
    val paint = Paint()

    paint.color = Color(0xFF1E2A38)
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

    paint.color = Color(0xFFCC0033)
    canvas.drawCircle(Offset(size * 0.38f, size * 0.38f), size * 0.26f, paint)

    paint.color = Color(0xFFFFC400)
    canvas.drawRect(size * 0.46f, size * 0.46f, size * 0.88f, size * 0.88f, paint)
    return bitmap
}
