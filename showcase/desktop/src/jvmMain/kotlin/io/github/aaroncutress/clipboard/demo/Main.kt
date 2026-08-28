package io.github.aaroncutress.clipboard.demo

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(width = 760.dp, height = 980.dp),
        title = "kmp-clipboard",
    ) {
        ClipboardDemo()
    }
}
