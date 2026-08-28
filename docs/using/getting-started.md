# Getting started

## Installing

```kotlin
// build.gradle.kts
implementation("io.github.aaroncutress:kmp-clipboard:0.1.0")
```

Built against Compose Multiplatform **1.12.0** and Kotlin **2.4.10**. Android,
iOS, desktop (JVM) and web (JS and Wasm).

## The clipboard

```kotlin
val clipboard = LocalRichClipboard.current
```

That is the whole setup. No provider, no `init(context)`, no manifest entry.

`LocalRichClipboard` is a
[`compositionLocalWithComputedDefaultOf`](https://developer.android.com/reference/kotlin/androidx/compose/runtime/package-summary#compositionLocalWithComputedDefaultOf(kotlin.Function1)):
its default is computed *inside* composition, so it can read `LocalContext` and
hand the Android implementation the `Context` it needs. A plain
`staticCompositionLocalOf` cannot do that, and the two usual ways around it are
both worse — an `error("wrap your app in …")` default makes every consumer
install a provider before the library does anything, and a `ContentProvider`
auto-initialiser puts a component in everybody's manifest to obtain something
composition is already holding.

## Copying and pasting

Every operation is `suspend`. A clipboard read is cross-process on Android, a
permission-gated promise on the web, and a blocking X11 round trip on Linux;
none of those belong on a frame.

```kotlin
val clipboard = LocalRichClipboard.current
val scope = rememberCoroutineScope()

Button(onClick = { scope.launch { clipboard.setText("hello") } }) { Text("Copy") }
Button(onClick = { scope.launch { pasted = clipboard.getText() } }) { Text("Paste") }
```

The full set of one-liners, each an extension over the three members of
`RichClipboard`:

| Write | Read |
|---|---|
| `setText(String)` | `getText(): String?` |
| `setHtml(html, plainText)` | `getHtml(): String?` |
| `setAnnotatedString(AnnotatedString)` | `getAnnotatedString(): AnnotatedString?` |
| `setImage(ImageBitmap, format)` | `getImage(): ImageBitmap?` |
| `setUri(String)` | `getUris(): List<String>` |
| `setFiles(List<ClipFile>)` | `getFiles(): List<ClipFile>` |
| `clear()` | |

`getText` falls back to flattening HTML when a clip offers HTML and no
`text/plain` — rare from this library, which always writes both, and common from
other applications.

## Asking without reading

```kotlin
val info by rememberClipInfo()
IconButton(onClick = ::paste, enabled = info?.hasImage == true) { Icon(Paste) }
```

`peek()`, and everything built on it — `hasText()`, `hasImage()`, `hasFiles()`,
`rememberClipInfo()` — reads only the clipboard's *description*. On Android that
is the difference between a paste button and a "this app pasted from your
clipboard" toast every time the window regains focus.

It reports null on the web, always. See [platforms](platforms.md#the-web).

## When something goes wrong

Null from a read means **nothing there**, or nothing there in the format you
asked for. That is an ordinary answer and callers handle it inline.

A refusal **throws**:

```kotlin
try {
    clipboard.getImage()
} catch (e: ClipboardAccessDeniedException) {
    // the browser said no — usually a missing user gesture
} catch (e: ClipboardUnavailableException) {
    // there is no clipboard here at all — a headless JVM, a page on http://
}
```

The two are separated because the responses differ: a denial is worth retrying
from a button, and an unavailable clipboard is not worth retrying at all. And a
refusal throws rather than returning null because on the web the difference
between "empty" and "the browser wants a user gesture" is a five-minute problem
that becomes an afternoon if the API will not say which.

`RichClipboard.capabilities` answers the predictable half ahead of time — see
[platforms](platforms.md).

## Testing against it

```kotlin
val clipboard = InMemoryRichClipboard()
CompositionLocalProvider(LocalRichClipboard provides clipboard) { PriceRow(price) }
```

See [building/testing.md](../building/testing.md#the-in-memory-clipboard).

## Next

- [Formats](formats.md) — one payload in several representations, and custom types
- [Formatted text](formatted-text.md) — `AnnotatedString` and HTML
- [Platforms](platforms.md) — what each one can and cannot do
- [Android](android.md) — the bundled file provider, and how to replace it
