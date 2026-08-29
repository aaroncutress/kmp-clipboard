# Platforms

The four are not one clipboard with four spellings. This page is what actually
differs, and how to ask about it in code rather than finding out from a bug
report.

## Capabilities

```kotlin
val clipboard = LocalRichClipboard.current
MenuItem("Copy as RTF", enabled = clipboard.capabilities.writesRtf) { … }
```

| | Android | iOS | Desktop | Web |
|---|---|---|---|---|
| `available` | yes | yes | no if headless | no on `http://` |
| `writesImages` | yes | yes | yes | yes |
| `writesFiles` | yes | yes | yes | **no** |
| `readsFilePaths` | content URIs | yes | yes | **no** |
| `writesRtf` | yes | yes | yes | **no** |
| `writesCustomFormats` | yes | yes | yes | `web `-prefixed |
| `multipleItems` | yes | yes | **no** | **no** |
| `readsWithoutUserGesture` | yes | yes | yes | **no** |
| `observesChanges` | foreground only | yes | best-effort | **no** |

These describe the *platform*, not the moment. `writesImages` being true does not
promise the next write succeeds — a browser can still refuse for want of a user
gesture — so it is not a substitute for handling
`ClipboardAccessDeniedException`. What it does promise is that the capability
exists at all, which is what a menu needs to know.

## Android

Reads and writes through `android.content.ClipboardManager`.

**Images and files go through a content provider.** The clipboard cannot hold
bytes; it holds a `content://` URI that the pasting application resolves. The
library ships the provider that serves it. This is the one part of the design
with a component in your manifest, and it has [its own page](android.md).

**`peek()` is quiet.** It reads `primaryClipDescription`, which does not trip the
"pasted from clipboard" toast that touching the contents does. Anything that runs
on a focus change should go through it.

**One URI per item.** An Android `ClipData.Item` holds text, HTML text and *one*
URI, so an item offering two binary formats can carry only the first out of the
process. A round trip through this library still finds both, because the second
is in the clip's description; another application will see one.

**Change observation stops at the background.** `OnPrimaryClipChangedListener`
fires only in the foreground — and since Android 10 the foreground is the only
time an app may read the clipboard at all, so nothing is lost that could have
been offered.

## iOS

Reads and writes through `UIPasteboard.generalPasteboard`.

**`peek()` uses `pasteboardTypes()`**, which is metadata and does not trigger the
iOS 16 "pasted from" banner. Reading the contents does.

**UTIs are a table, not a transformation.** `public.png`, `public.html`,
`public.utf8-plain-text` and the rest map to MIME both ways. A type outside the
table passes through when it looks like a MIME type — which is what a clip
written by this library uses for a custom format — and is skipped when it looks
like a UTI, because inventing `application/x-com.apple.iwork.pages` would be
inventing a fact. Anything skipped is reachable through `uiPasteboard`.

**Change observation covers the task switch.** `UIPasteboardChangedNotification`
only reports changes made by this process, so the library also re-checks on
`UIApplicationDidBecomeActive` — which is what makes a paste button correct after
the user copies something in Safari and switches back.

## Desktop

Reads and writes through `java.awt.datatransfer`.

**Headless means no clipboard.** `capabilities.available` is false and every
operation throws `ClipboardUnavailableException`. That is most CI runners; run
tests under `xvfb-run` to get a real one.

**One item, many flavours.** AWT has no notion of a second item, so a multi-item
clip is flattened to its first — except files, which are gathered across every
item, because `javaFileListFlavor` is a list and a three-file copy is the point
of it.

**Images go out twice.** As `DataFlavor.imageFlavor`, holding a live
`java.awt.Image`, and as the encoded stream. Desktop applications are split on
which they read, and offering one loses half of them.

**Files need a real path.** A `ClipFile` built from bytes has none, so the
transferable spills it to a temp file, deleted on JVM exit rather than after the
write — because "after the write" is before the paste.

**Change observation is best-effort.** `FlavorListener` fires when the *set of
available flavours* changes, so an application replacing "some text" with "some
other text" may not trigger it. The alternative is polling `getContents`, which is
a cross-process transfer on every tick and on X11 can block on an application
that is not answering. A missed update is cheaper.

## The web

Reads and writes through `navigator.clipboard`, from a single `webMain` source
set that serves both the JS and Wasm targets.

**`peek()` returns null, always.** There is no way to ask a browser what is on
the clipboard: `read()` is the only route to the answer and it is the same
permission-gated, gesture-gated call as reading the contents. A `peek` that
worked would be a `read` wearing a hat, and would put a permission prompt in
front of a user who only moved the window focus.

So a paste button on the web cannot be disabled ahead of time. Leave it enabled,
do the read in its `onClick`, and handle `ClipboardAccessDeniedException`.

**Reads need a user gesture.** Chrome and Safari allow a read only from inside a
handler for a real user gesture. The same read from a `LaunchedEffect` on first
composition is refused.

**Writes need user activation, which an `await` spends.** See
[formats](formats.md#lazy-by-default) — this is why `ClipScope.bytes` takes a
producer.

**Secure context only.** The Clipboard API does not exist on plain `http://`,
with the standard exception for `localhost`. Nothing in code works around it;
`capabilities.available` is false and `ClipboardUnavailableException` says so.

**Firefox before 127 has only the text half.** No `ClipboardItem`, no `read()`,
no `write()`. The library falls back to `readText`/`writeText` so text still
works, and `capabilities.writesImages` is false.

**Only some types are writable.** Browsers restrict `clipboard.write` to a short
list of "sanitized" types — the browser parses and re-serialises them, which is
what stops a page putting hostile markup on the system clipboard. `text/plain`,
`text/html`, `image/png` and `image/svg+xml` go through as themselves; everything
else is prefixed `web ` and is visible to other web pages but not to native
applications. RTF is not writable at all.

## Files

What a "file" is differs more between platforms than anything else the library
carries. `ClipFile` is the smallest thing all four agree on: a name, a type, and
bytes you can ask for.

| | `path` holds |
|---|---|
| Desktop | a filesystem path |
| iOS | a file URL |
| Android | a `content://` URI — **not** a path; `File(path)` will not open it |
| Web | null. `hasFiles` is false, because there is no file — only its contents |

## Reaching the platform

Everything a MIME-shaped abstraction has to leave out is one property away. Each
is declared in its own source set, so it exists only where it means something.

```kotlin
clipboard.androidClipboardManager   // android.content.ClipboardManager
clipboard.uiPasteboard              // platform.UIKit.UIPasteboard
clipboard.awtClipboard              // java.awt.datatransfer.Clipboard
clipboard.w3cClipboard              // navigator.clipboard
```

Each is null on a `RichClipboard` that is not that platform's — an
`InMemoryRichClipboard` in a test, most likely — and `awtClipboard` is also null
on a headless JVM.

What they are for: `ClipDescription.EXTRA_IS_SENSITIVE` on Android, which keeps a
password out of the clipboard preview on Android 13 and up;
`UIPasteboard.detectPatternsForPatterns` on iOS, which reports that a URL is
present *without* the banner; a `DataFlavor` carrying a live Java object on
desktop; and on the web, whatever ships next.
