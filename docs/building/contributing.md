# Contributing

## The shape of the repository

```
clipboard/          :clipboard — the published artifact, and the only one
demo/               :demo — shared Compose UI exercising every format
showcase/           hosts that put :demo on a screen; none of them ships
  desktop/            a JVM window
  android/            an APK — the one that proves the file provider merges
  web/                a Wasm bundle, deployed to GitHub Pages
docs/               this
```

## Source sets

```
commonMain    the model, the DSL, the conveniences, the HTML conversion
  skikoMain   jvmMain + iosMain + webMain
    jvmMain   java.awt.datatransfer
    iosMain   UIPasteboard
    webMain   navigator.clipboard — serves both js and wasmJs
  androidMain android.content.ClipboardManager, and the file provider
```

Two of those need explaining.

**`skikoMain` exists for one file.** The clipboard carries images as encoded
bytes, so something has to turn an `ImageBitmap` into a PNG. There are exactly
two ways across five targets: `android.graphics.Bitmap` on Android, and Skia
everywhere else — because desktop, iOS and web all render through Skiko already,
so the encoder is on the classpath whether this library uses it or not. Written
per target that would be four implementations of the same twenty lines, three of
them identical.

It is declared as an *extension* of the default hierarchy template, not as
hand-written `dependsOn` edges:

```kotlin
applyDefaultHierarchyTemplate {
    common { group("skiko") { withJvm(); withIos(); withJs(); withWasmJs() } }
}
```

Adding edges by hand switches the default template off — the build says so — and
the template is what creates `iosMain` and `webMain` in the first place, so doing
it that way leaves both orphaned.

**`webMain` serves both web targets.** The unified `kotlin.js` interop
(`JsAny`, `JsArray`, `Promise`, `js(…)` bodies) compiles from a shared source set
as of Kotlin 2.4, so there is one browser implementation rather than two. Two
places need a `@Suppress`, each wrapped in a single function with the reason
written down: `JsArray.get` returns `T?` on Wasm and `T` on JS, and `JsString`
*is* `String` on JS — so a null check that is necessary on one target is a
warning on the other, and warnings are errors here.

## Adding a format

1. Add the constant to `ClipFormat`, with a KDoc line saying which platforms
   carry it.
2. Add it to each platform's mapping table:
   - `AwtFlavors` — a `DataFlavor`, and mind that the representation class is
     half the meaning.
   - `Utis` — both directions; the read table takes the aliases too
     (`public.text` and `public.utf8-plain-text` are both `text/plain`).
   - `WebFormats` — only if a browser will carry it unprefixed.
   - Android needs nothing: it speaks MIME already.
3. Add a convenience extension in `Clipboard.ext.kt` only if the format is one
   people reach for by name. `ClipFormat.Pdf` does not need a `setPdf`.
4. Update the capability table in [`platforms.md`](../using/platforms.md) if the
   answer differs per platform.

## Adding a platform

Implement `platformRichClipboard()` for the new source set and the
`RichClipboard` interface behind it. Three members — `peek`, `read`, `write` —
plus `capabilities`.

The rules the existing four follow, and which anything new should:

- **`peek` must not read content.** It exists so a paste button can ask on every
  focus change. Where the platform cannot answer without reading, return null —
  do not read.
- **Null means empty; a refusal throws.** See
  [`getting-started.md`](../using/getting-started.md#when-something-goes-wrong).
- **`capabilities` describes the platform, not the moment.** It is a `val`.
- **Be honest in it.** A `writesFiles = true` that silently drops files is worse
  than a false.

## Conventions

**Warnings are errors, in `main` only.** Test sources stay permissive: a build
that refuses to compile until every deprecation in every test is migrated makes
upgrading a dependency the most expensive thing you can do. A deprecation in
*shipped* code is a defect; one in a test is a migration waiting for a quiet
afternoon.

**No Foundation, no Material.** The library draws nothing.
`checkDependencyBudget` walks the resolved runtime graph and fails if either
arrives, transitively or otherwise — the risk was never someone typing the
import.

**KDoc carries the reasoning.** Dokka runs with `failOnWarning`, so a `[Link]`
to a symbol that no longer exists fails the build. Say *why*, not what: the
signature already says what.

**No commas in backtick-quoted test names.** Kotlin/Native rejects them, and
`commonTest` compiles for iOS.

## Before you push

```sh
xvfb-run -a ./gradlew :clipboard:jvmTest :clipboard:checkDependencyBudget
./gradlew :clipboard:dokkaGenerateHtml
./gradlew :clipboard:compileKotlinJs :clipboard:compileKotlinWasmJs \
          :clipboard:compileKotlinIosArm64 :clipboard:assemble
```

See [`testing.md`](testing.md) for what each of those actually asks.
