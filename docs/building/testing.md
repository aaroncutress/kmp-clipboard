# Testing

A clipboard is unusual to test: the thing that decides whether it worked is
always *another application*. Nothing on a CI runner can stand in for Gmail
accepting a pasted image. So the gates here are arranged by how much of that gap
each one closes.

## The gates

| | What it asks |
|---|---|
| `:clipboard:jvmTest` | Does the model, the DSL, the HTML conversion and the desktop bridge behave? |
| `:clipboard:jvmTest` under `xvfb-run` | …and does a real X11 clipboard accept what we hand it? |
| `:clipboard:checkDependencyBudget` | Has anything that draws reached the classpath? |
| `:clipboard:dokkaGenerateHtml` | Does every `[Link]` in the KDoc still resolve? |
| the per-target compiles | Does each platform bridge still exist and typecheck? |
| the demo, by hand | Does another application accept it? |

## `jvmTest`

Roughly 70 tests, in four groups.

**The model and the DSL.** `ClipFormat.parse` normalisation, lazy producers not
running until asked, memoisation, files becoming one item each, `hasText` matching
the whole `text/` category. Written against `InMemoryRichClipboard`, so they run
before any platform exists.

**The HTML conversion, both directions and round trip.** The largest body of
hand-written logic in the library and the one most likely to rot. Includes the
cases that come from real applications rather than from a spec: `rgb(…)` colours,
`font-weight:700` instead of `<b>`, Windows `CF_HTML` fragment markers, unclosed
tags, stray `<`, unknown tags whose text must survive.

**The Skia image codec.** Runs here because `skikoMain` is one source set — a
green run covers the encoder that desktop, iOS *and* web use. Android's
`Bitmap`-based one is the odd one out and needs a device.

**The desktop bridge**, against a private `java.awt.datatransfer.Clipboard`. Same
class and same `Transferable` contract as the system one; what differs is that
nothing else on the machine can overwrite it mid-test, and that it exists at all
on a headless runner.

`compose.desktop.currentOs` is a `jvmTest`-only dependency, for Skiko's *native*
library. Without it `ImageBitmap(w, h)` throws `LibraryLoadException` at
class-init and takes the whole codec suite with it. It is test-only so it stays
clear of `checkDependencyBudget`, which reads the main runtime classpath.

## Why `xvfb-run`

`AwtRichClipboardTest` has two halves and the machine decides which runs:

- **Headless** — the assertion is that the library says so
  (`capabilities.available == false`) and throws `ClipboardUnavailableException`
  cleanly, rather than an `ExceptionInInitializerError` from inside AWT.
- **With a display** — the assertion is a real round trip through the actual X11
  selection. This is the only test in the repository that proves a transferable
  is accepted by something other than the code that built it.

```sh
xvfb-run -a ./gradlew :clipboard:jvmTest
```

A runner that lost its display would silently skip the second half and still go
green, so `capabilities agree with the environment` asserts which branch is live.
That test is the reason the CI job installs Xvfb rather than treating it as
optional.

## `checkDependencyBudget`

Walks the fully resolved `jvmRuntimeClasspath` and fails if any Compose
Foundation or Material module appears anywhere in it. One target is enough: the
only per-platform dependencies are `androidx.core` and `kotlinx-browser`, so
anything general enough to reach Android or web reaches the JVM classpath too.

It also writes the whole resolved graph to
`clipboard/build/reports/dependency-budget.txt`, because "did this get bigger" is
a question a pass/fail cannot answer.

The risk it guards is not someone typing `import androidx.compose.material3` —
that is easy to spot in review. It is a convenience library pulling one in
transitively, and nobody noticing until a consumer's app is heavier for a
dependency they were told they did not have.

## Dokka as a gate

`failOnWarning` is on, so a `[Link]` to a renamed or deleted symbol fails the
build. Without it Dokka prints the warning and succeeds, which turns the CI step
into an artifact upload rather than a check.

One consequence worth knowing: Dokka has no Android SDK on its classpath, so
`[ClipData]` in an `androidMain` KDoc resolves to nothing and fails the build.
Write framework types as code spans — `` `ClipData` `` — rather than links.

## The per-target compiles

Four of the five bridges cannot be unit-tested on a Linux runner, so compiling
every one of them is the gate that stops a wrong `UIPasteboard` binding name or a
malformed `js(…)` body sitting undiscovered for months. `:demo` is compiled too,
because it is the only caller that exercises the whole public API — a signature
change that breaks it should fail in CI rather than the next time somebody runs
it.

iOS compiles to a klib here. Linking a framework and running a simulator needs
macOS, which is a slower job than this gate should be.

## The in-memory clipboard

```kotlin
val clipboard = InMemoryRichClipboard()
CompositionLocalProvider(LocalRichClipboard provides clipboard) { PriceRow(price) }
// …
assertEquals("£4.20", clipboard.getText())
```

Shipped in the main artifact rather than a `-testing` sibling. The tidier
arrangement would cost every consumer a second dependency and a second version to
keep in step, for a class of sixty lines that pulls in nothing — and it gets used
from demos and previews nearly as often as from tests.

It reports every capability as true by default, because the point of a fake is to
take the platform out of the question. To test how a screen behaves somewhere
more restricted, say so:

```kotlin
InMemoryRichClipboard(ClipboardCapabilities(writesFiles = false, readsWithoutUserGesture = false))
```

It also counts `readCount` and `writeCount`, which is how the suite asserts that
`hasText()` goes through `peek()` and does not read — the Android toast rule, in
a test that runs everywhere.

## What is not covered

**Android, iOS and web have no runtime coverage.** They compile and nothing runs
them. Closing that gap needs a device, a simulator and a browser respectively;
until then the demo is the test.

The Android one matters most, because the bundled `ClipboardFileProvider` is the
only library-owned component in the design and manifest merging cannot be checked
by a compiler:

```sh
./gradlew :showcase:android:installDebug   # copy an image, paste into Gmail
./gradlew :showcase:web:wasmJsBrowserRun   # copy under a gesture; answer the permission prompt
./gradlew :showcase:desktop:run            # copy an image out to a native editor
```

The desktop one can be automated, and was during development: run it under Xvfb,
drive it with `java.awt.Robot`, screenshot the result. That found a defect the
unit tests did not — `DataFlavor.stringFlavor` answers true to
`isFlavorTextType()` despite a MIME type of
`application/x-java-serialized-object`, so a text-first branch reported AWT's
internal transport as a clipboard format, and it showed up in the demo's format
inspector next to `text/html`. There is a regression test for it now.
