# Formats

## One payload, several representations

This is what a clipboard item actually is, and it is the reason this library
exists rather than a `setText`/`getText` pair:

```kotlin
clipboard.write(label = "Invoice total") {
    html("<b>Total:</b> £4.20", plainText = "Total: £4.20")
    image(chart)
    bytes(ClipFormat("application/vnd.myapp.cells+json")) { encodeSelection() }
}
```

An application pasting into a plain-text field asks for `text/plain` and gets
the text. A word processor asks for `text/html` and gets the markup. An image
editor asks for `image/png` and gets the chart. Your own app asks for its own
type and gets its own structured data back exactly as it wrote it. None of them
has to know about the others, and none of them gets a worse answer because the
others exist.

## `ClipFormat`

A format is a MIME type:

```kotlin
ClipFormat.PlainText   // text/plain
ClipFormat.Html        // text/html
ClipFormat.Rtf         // text/rtf
ClipFormat.UriList     // text/uri-list
ClipFormat.Png         // image/png
ClipFormat.Jpeg        // image/jpeg
ClipFormat.Gif         // image/gif
ClipFormat.Svg         // image/svg+xml
ClipFormat.Pdf         // application/pdf
ClipFormat.OctetStream // application/octet-stream
```

MIME, and not something richer, because it is the one vocabulary all four
platforms can be mapped onto without inventing a fifth. Android and the web
already speak it. AWT speaks `DataFlavor`, which names a *Java class* alongside
the type. iOS speaks UTI, which is a point in a conformance hierarchy where
`public.png` conforms to `public.image` conforms to `public.data`. Both are
richer than MIME in ways a common abstraction cannot usefully expose, so the
mapping is per platform and lives beside each implementation — and what it
cannot express is reachable through the [native handle](platforms.md#reaching-the-platform)
rather than through a lossy common type.

`ClipFormat.parse` normalises: `Text/HTML; class=java.lang.String; charset=Unicode`
— which is genuinely what AWT calls its HTML flavour — becomes `text/html`. A
set in which `text/plain` and `text/plain;charset=utf-8` are two entries is a set
that makes `hasText()` wrong.

## Custom formats

Any MIME string works, and an app copying its own structured data should use one:

```kotlin
val Cells = ClipFormat("application/vnd.myapp.cells+json")

clipboard.write {
    text(selection.asTsv())              // for anything else
    bytes(Cells) { selection.asJson() }  // for us
}
```

A custom format survives a round trip on every platform. Whether it leaves the
process is a different question — see
[`writesCustomFormats`](platforms.md#capabilities). On the web a browser drops
any type it does not recognise unless it is prefixed `web `, Chrome's opt-in for
custom formats; the library adds that prefix and strips it again on read, so
your code never sees it. The consequence is that on the web a custom format
reaches other web pages and not native applications.

## Lazy by default

```kotlin
clipboard.write {
    bytes(ClipFormat.Png) { expensiveRender() }   // not called yet
}
```

`bytes(format) { … }` takes a **producer**, not a `ByteArray`, and `image(…)`
uses it internally so encoding a PNG happens at most once and only if something
asks.

On the web it is more than an optimisation: Safari honours
`navigator.clipboard.write` only while the page still has user activation, and
an `await` in between spends it. Handing `ClipboardItem` a promise per type moves
the encoding to *after* the call, which is what the API is shaped for. This is
why a copy button that encodes first works in Chrome and fails in Safari.

Desktop is the opposite case and worth knowing: AWT's `Transferable` is called
by the *receiving* application, synchronously, on its own thread, possibly
minutes later. It cannot wait on a coroutine, so `write` resolves every producer
before handing the clipboard over. A desktop copy costs whatever the largest
representation costs even if nobody ever pastes.

## Reading

`read()` returns a `Clip`, which is a list of `ClipItem`, each of which offers a
set of formats over lazily loaded bytes:

```kotlin
val clip = clipboard.read() ?: return
val json = clip.firstOrNull(Cells)?.text(Cells)
val png  = clip.firstOrNull(ClipFormat.Png)?.bytes(ClipFormat.Png)
```

Getting a `Clip` back does not mean anything has been read yet. Items load on
demand and memoise, so `hasImage()` followed by `getImage()` costs one clipboard
access rather than two — and a `Clip` you keep hold of after reading a 12 MB
image out of it is 12 MB you are keeping hold of. Read what you need and let it
go.

## Several items

```kotlin
clipboard.write {
    for (row in selection) item { text(row.asTsv()); html(row.asHtml()) }
}
```

Separate *things*, as against separate representations of one thing. A user
selecting three files in a file manager produces three items; a screenshot
copied from a browser produces one item that is both `image/png` and `text/html`.

Android and iOS model this natively. Desktop and the web do not and keep the
first item only — check
[`capabilities.multipleItems`](platforms.md#capabilities) before relying on it.

## Files

```kotlin
clipboard.setFiles(listOf(ClipFile("report.pdf", bytes)))
clipboard.setFiles(listOf(ClipFile("report.pdf") { renderPdf() }))   // lazy
```

Each file becomes its own item, because two files are two things.

Reading gives back `name`, `format`, `size` where the platform reports one, and
`path` — which means different things per platform and is null on the web. See
[platforms](platforms.md#files).
