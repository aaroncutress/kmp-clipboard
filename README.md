# kmp-clipboard

A **rich clipboard** for Compose Multiplatform. Text, formatted text, images,
files and arbitrary formats — on Android, iOS, desktop and web, behind one
composition local that needs no setup.

**[Try the demo](https://aaroncutress.github.io/kmp-clipboard/)** ·
[documentation](docs/README.md) ·
[API reference](https://aaroncutress.github.io/kmp-clipboard/api/)

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
implementation("io.github.aaroncutress:kmp-clipboard:0.1.0")
```

Compose Multiplatform **1.12.0**, Kotlin **2.4.10**.

## A taste

One payload, several representations — which is what a clipboard item actually
is, and the reason this exists rather than a `setText`/`getText` pair:

```kotlin
clipboard.write(label = "Invoice total") {
    html("<b>Total:</b> £4.20", plainText = "Total: £4.20")
    image(chart)
    bytes(ClipFormat("application/vnd.myapp.cells+json")) { encodeSelection() }
}
```

A plain-text field gets the text, a word processor gets the markup, an image
editor gets the PNG, and your own app gets its own structured data back exactly
as it wrote it.

Asking what is there, without reading it — which on Android is the difference
between a paste button and a "this app pasted from your clipboard" toast:

```kotlin
val info by rememberClipInfo()
IconButton(onClick = ::paste, enabled = info?.hasImage == true) { Icon(Paste) }
```

Null from a read means *nothing there*. A refusal throws, because on the web the
difference between "empty" and "the browser wants a user gesture" is a
five-minute problem that becomes an afternoon if the API will not say which.

## What each platform can do

`RichClipboard.capabilities` answers this in code, so a menu can grey an item out
rather than offering it and apologising.

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

Details, and the native handle for everything a MIME-shaped abstraction leaves
out, in [`docs/using/platforms.md`](docs/using/platforms.md).

## Documentation

**Using it** — [getting started](docs/using/getting-started.md) ·
[formats](docs/using/formats.md) ·
[formatted text](docs/using/formatted-text.md) ·
[platforms](docs/using/platforms.md) ·
[Android's file provider](docs/using/android.md)

**Building it** — [contributing](docs/building/contributing.md) ·
[testing](docs/building/testing.md) ·
[releasing](docs/building/releasing.md)

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
xvfb-run -a ./gradlew :clipboard:jvmTest :clipboard:checkDependencyBudget
./gradlew :clipboard:dokkaGenerateHtml
./gradlew :clipboard:compileKotlinJs :clipboard:compileKotlinWasmJs \
          :clipboard:compileKotlinIosArm64 :clipboard:assemble
```

`xvfb-run` is not decoration: half the desktop suite only exercises the real X11
clipboard when a display exists. [`testing.md`](docs/building/testing.md) has
what each gate asks and what each has caught.

## Releasing

```sh
git tag v0.2.0 && git push origin v0.2.0
```

Tagging a commit that already passed CI does not rebuild it. See
[`releasing.md`](docs/building/releasing.md).

## Licence

Apache 2.0.
