package io.github.aaroncutress.clipboard

import androidx.compose.runtime.Composable

/**
 * Constant, because a browser offers nothing to observe.
 *
 * There is no clipboard-changed event, and the only way to look is
 * `navigator.clipboard.read()` — a permission-gated call that needs a user
 * gesture. A ticker built on polling that would put a permission prompt in front
 * of a user who did nothing but move the mouse.
 *
 * [rememberClipInfo] therefore stays null on the web, and
 * [ClipboardCapabilities.observesChanges] is false to say so. A paste button
 * here should stay enabled and handle the failure — see
 * [ClipboardCapabilities.readsWithoutUserGesture].
 */
@Composable
internal actual fun clipboardRevision(): Int = 0
