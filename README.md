# kmp-clipboard

A **rich clipboard** for Compose Multiplatform. Text, formatted text, images,
files and arbitrary formats — on Android, iOS, desktop and web, behind one
composition local that needs no setup.

```kotlin
val clipboard = LocalRichClipboard.current
val scope = rememberCoroutineScope()

Button(onClick = { scope.launch { clipboard.setImage(chart) } }) { Text("Copy chart") }
```

## Why

Compose ships `LocalClipboard`, and it carries a `ClipEntry` — an `expect class`
with no common members worth having. Its one common property, `clipMetadata`, is
`TODO("not implemented")` on every target but Android, and on iOS the class is:

```kotlin
actual class ClipEntry internal constructor() { … }   // compose-ui, PlatformClipboard.ios.kt
```

`internal constructor`. Common code cannot build one, and `setClipEntry` there
assigns `UIPasteboard.string` and nothing else. So plain text works and anything
richer means writing four platform implementations by hand. JetBrains tracks the
gap as CMP-7624.

This is those four, written once.

## Installing

```kotlin
// build.gradle.kts
implementation("io.github.aaroncutress:kmp-clipboard:0.1.0")
```

Built against Compose Multiplatform **1.12.0** and Kotlin **2.4.10**.

## Using it

### The one-liners

Most code needs nothing else. Every one of these is an extension over the three
members of `RichClipboard`, and every one is `suspend` — a clipboard read is
cross-process on Android, a permission-gated promise on the web, and a blocking
X11 round trip on Linux.

```kotlin
clipboard.setText("hello");            clipboard.getText()
clipboard.setHtml("<b>hi</b>");        clipboard.getHtml()
clipboard.setAnnotatedString(styled);  clipboard.getAnnotatedString()
clipboard.setImage(bitmap);            clipboard.getImage()
clipboard.setUri("https://…");         clipboard.getUris()
clipboard.setFiles(files);             clipboard.getFiles()
clipboard.clear()
clipboard.hasText(); clipboard.hasImage(); clipboard.hasFiles()
```

### One payload, several representations

That is what a clipboard item actually is, and it is the reason this library
exists rather than a `setText`/`getText` pair:

```kotlin
clipboard.write(label = "Invoice total") {
    html("<b>Total:</b> £4.20", plainText = "Total: £4.20")
    image(chart)
    bytes(ClipFormat("application/vnd.myapp.cells+json")) { encodeSelection() }
}
```

An application pasting into a plain-text field gets the text, one pasting into a
document gets the HTML, an image editor gets the PNG, and your own app gets its
own format back exactly as it wrote it. None of them has to know about the
others.

`bytes(format) { … }` takes a **producer**, not a `ByteArray`. Encoding is
deferred until something asks — and on the web it is the only shape that works
outside a user gesture, because Safari accepts a `ClipboardItem` whose values are
promises and rejects one built from resolved blobs once the gesture has ended.

### Asking without reading

```kotlin
val info by rememberClipInfo()
IconButton(onClick = ::paste, enabled = info?.hasImage == true) { Icon(Paste) }
```

`peek()` and everything built on it read only the clipboard's *description*. On
Android that is the difference between a paste button and a "this app pasted
from your clipboard" toast every time the window regains focus.

### Zero setup, and how

```kotlin
val clipboard = LocalRichClipboard.current   // no provider, no init call
```

`LocalRichClipboard` is a
[`compositionLocalWithComputedDefaultOf`](https://developer.android.com/reference/kotlin/androidx/compose/runtime/package-summary#compositionLocalWithComputedDefaultOf(kotlin.Function1)),
whose default is computed *inside* composition and can therefore read
`LocalContext` — which is how the Android implementation finds a `Context`
without one being passed in. A plain `staticCompositionLocalOf` cannot do that,
and the usual ways around it are both worse: an `error("wrap your app in …")`
default makes every consumer install a provider to use the library at all, and a
`ContentProvider` auto-initialiser puts a component in everybody's manifest for
something composition already knows.

Provide over it for a test, or for an Android app with its own file provider:

```kotlin
CompositionLocalProvider(LocalRichClipboard provides InMemoryRichClipboard()) { PriceRow(price) }
```

### When it will not work

The four platforms are not one clipboard with four spellings, and this library
does not pretend otherwise. `RichClipboard.capabilities` says what this one can
do, so a menu can grey an item out rather than offering it and apologising:

| | Android | iOS | Desktop | Web |
|---|---|---|---|---|
| images | yes | yes | yes | yes |
| files out | yes | yes | yes | **no** |
| file paths in | URIs | yes | yes | **no** |
| RTF | yes | yes | yes | **no** |
| custom formats | yes | yes | yes | `web `-prefixed |
| several items | yes | yes | **no** | **no** |
| read without a gesture | yes | yes | yes | **no** |
| observe changes | foreground | yes | best-effort | **no** |

Null from a read means *nothing there*. A refusal **throws** —
`ClipboardAccessDeniedException`, `ClipboardUnavailableException` — because on
the web the difference between "empty" and "the browser said no, add a user
gesture" is a five-minute problem that becomes an afternoon if the API lies
about it.

### Reaching the platform

Everything a MIME-shaped abstraction has to leave out is one property away:

```kotlin
clipboard.androidClipboardManager   // android.content.ClipboardManager
clipboard.uiPasteboard              // platform.UIKit.UIPasteboard
clipboard.awtClipboard              // java.awt.datatransfer.Clipboard
clipboard.w3cClipboard              // navigator.clipboard
```

## Android: the bundled file provider

Android's clipboard cannot hold bytes. It holds a `content://` URI that the
pasting application resolves — so copying an image needs a `ContentProvider` in
the app doing the copying. This library ships one, merged into your manifest at
`${applicationId}.kmpclipboard`, so `setImage` works with no setup.

To turn it off — an app with its own `FileProvider`, or a policy against extra
components — remove it and name your own authority:

```xml
<provider
    android:name="io.github.aaroncutress.clipboard.ClipboardFileProvider"
    android:authorities="${applicationId}.kmpclipboard"
    tools:node="remove" />
```

```kotlin
val clipboard = remember(context) { AndroidRichClipboard(context, authority = MY_AUTHORITY) }
```

Its paths file must serve a `kmp-clipboard` directory inside the app's cache;
copy [`kmp_clipboard_paths.xml`](clipboard/src/androidMain/res/xml/kmp_clipboard_paths.xml).

## Formatted text

`setAnnotatedString` and `getAnnotatedString` convert between `AnnotatedString`
and HTML in common code, because there is nothing to delegate to:
`AnnotatedString.fromHtml` throws
`UnsupportedOperationException("Compose Multiplatform doesn't support fromHtml")`
on every target except Android — it is a thin wrapper over Android's
`Html.fromHtml`.

Weight, slant, underline, strikethrough, colour, background, absolute size and
links survive in both directions. Paragraph alignment survives. `FontFamily` does
not, deliberately: a Compose `FontFamily` can be a bundled resource with no name
a stylesheet could refer to, and writing `font-family:sans-serif` for it would be
inventing information.

The parser is tolerant on purpose — unknown tags are dropped and **their text is
kept** — because clipboard HTML is whatever the source application felt like
emitting. Word writes `<o:p>`; Google Docs writes `<span style="font-weight:700">`
rather than `<b>`, and `color:rgb(0,0,0)` rather than hex. Both are handled.

> Related: [HtmlConverterCompose](https://github.com/cbeyls/HtmlConverterCompose)
> is a good multiplatform HTML→`AnnotatedString` converter and was considered for
> this. It reads only `color` and `background-color` from inline CSS, skips
> `<table>` and its contents, and does not go the other way — and a clipboard
> needs `font-weight`/`font-style`/`font-size` from inline CSS (that is how Docs
> and Word emit them), tables (people copy them constantly), and the HTML
> direction for writing. Different target, so this library carries its own.

## Running the demo

Every format, against a real system clipboard. Copy from it and paste into
Photoshop, Gmail, Word, Finder or a browser — and back — which is the only test
of a clipboard that counts, because the thing that decides is always the other
application.

```sh
./gradlew :showcase:desktop:run              # a JVM window
./gradlew :showcase:android:installDebug     # a device or emulator
./gradlew :showcase:web:wasmJsBrowserRun     # a browser, on localhost
```

Android is the one worth doing on a device: copy an image, paste it into Gmail.
That is what proves the bundled file provider merged into your manifest.

## Building

```sh
./gradlew :clipboard:jvmTest :clipboard:checkDependencyBudget :clipboard:dokkaGenerateHtml
./gradlew :clipboard:compileKotlinJs :clipboard:compileKotlinWasmJs \
          :clipboard:compileKotlinIosArm64 :clipboard:assemble
```

`jvmTest` covers the clip model, the HTML conversion in both directions, the
Skia image codec that three of the five targets share, and a real round trip
through `java.awt.datatransfer`. Run it under `xvfb-run` and it additionally
exercises the actual X11 system clipboard:

```sh
xvfb-run -a ./gradlew :clipboard:jvmTest
```

`checkDependencyBudget` walks the resolved runtime graph and fails if Compose
Foundation or Material reaches the library. It draws nothing and has no business
depending on anything that does.

## Releasing

A release is a tag:

```sh
git tag v0.2.0 && git push origin v0.2.0
```

CI publishes `io.github.aaroncutress:kmp-clipboard:0.2.0` to GitHub Packages. For
Maven Central, `./gradlew :clipboard:centralBundle` produces the zip that
[Central Portal](https://central.sonatype.com/publishing) takes; signing is on
whenever `SIGNING_KEY` is set and off otherwise, so a local publish needs no GPG
key.

```sh
./gradlew :clipboard:coordinate   # what this build would publish as
```

## Licence

Apache 2.0.
