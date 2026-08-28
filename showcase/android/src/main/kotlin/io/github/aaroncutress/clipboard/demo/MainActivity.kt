package io.github.aaroncutress.clipboard.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * The demo, on a phone.
 *
 * The host that matters most: Android is the only platform where copying an
 * image involves a component of the library's own — the bundled file provider —
 * and the only way to know it is wired up is to paste into another application
 * that has to resolve the `content://` URI. Gmail and Messages both do.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ClipboardDemo() }
    }
}
