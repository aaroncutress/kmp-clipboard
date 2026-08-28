# Documentation

Two halves, split by who is reading.

## Using it — you are building a screen

| | |
|---|---|
| [`using/getting-started.md`](using/getting-started.md) | Install, the one-liners, `LocalRichClipboard`, the failure model |
| [`using/formats.md`](using/formats.md) | One payload in several representations, custom types, lazy producers, files |
| [`using/formatted-text.md`](using/formatted-text.md) | `AnnotatedString` and HTML, what survives, and what other applications emit |
| [`using/platforms.md`](using/platforms.md) | The capability table, what each platform does differently, native handles |
| [`using/android.md`](using/android.md) | The bundled file provider — why it exists, and how to replace it |

The **API reference** is generated from the KDoc and lists every public symbol,
which is the half these pages do not attempt:

```sh
./gradlew :clipboard:dokkaGenerateHtml     # clipboard/build/dokka/html
```

It is published alongside the demo at
**[aaroncutress.github.io/kmp-clipboard/api/](https://aaroncutress.github.io/kmp-clipboard/api/)**.

It knows every signature and no reasons; these pages carry the trade-offs, the
platform differences and the defects that shaped a decision. Read the page first.

## Building it — you are changing the library

| | |
|---|---|
| [`building/contributing.md`](building/contributing.md) | Repository shape, source sets, adding a format or a platform, conventions |
| [`building/testing.md`](building/testing.md) | Every gate, what each asks, and what they have caught |
| [`building/releasing.md`](building/releasing.md) | Tags, versions, Maven Central, signing |

## The demo

**[aaroncutress.github.io/kmp-clipboard](https://aaroncutress.github.io/kmp-clipboard/)**
— every format, in your browser, against your own clipboard. The same
composable runs on all four platforms:

```sh
./gradlew :showcase:desktop:run
./gradlew :showcase:android:installDebug
./gradlew :showcase:web:wasmJsBrowserRun
```

The web build is the one deployed. It is also the platform where the clipboard
behaves least like the others — permissions, user activation, a secure context,
a short list of writable types — so it is worth trying there and then trying the
same thing on desktop.
