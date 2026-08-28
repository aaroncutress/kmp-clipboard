# Module kmp-clipboard

A rich clipboard for Compose Multiplatform. Text, formatted text, images, files
and arbitrary formats, on Android, iOS, desktop (JVM) and web (JS and Wasm),
behind one `LocalRichClipboard` that needs no setup.

**Why this exists.** Compose ships `LocalClipboard`, and its `ClipEntry` is an
opaque `expect class` with no common accessors — `clipMetadata` is
`TODO("not implemented")` on every target but Android, and on iOS the class has
an `internal` constructor, so common code cannot build one at all. Anything
richer than a `String` therefore means writing four platform implementations by
hand. This is that work, done once.

Everything below is derived from the KDoc on the sources. The prose that is not
in a signature — which format survives which round trip, what a browser will
refuse and when — is in the README and in `ClipboardCapabilities`.

# Package io.github.aaroncutress.clipboard

The whole public API. `RichClipboard` and `LocalRichClipboard`; the clip model
(`Clip`, `ClipItem`, `ClipInfo`, `ClipFormat`, `ClipFile`); the `write { }`
builder; the one-line conveniences (`setText`, `getImage`, and the rest); and
`ClipboardCapabilities`, which is how a UI asks what this platform will actually
accept rather than finding out by catching an exception.

# Package io.github.aaroncutress.clipboard.html

`AnnotatedString` to HTML and back, in common code.

It is here rather than borrowed because `AnnotatedString.fromHtml` throws
`UnsupportedOperationException("Compose Multiplatform doesn't support fromHtml")`
on every target except Android — there is nothing to delegate to. The supported
tag set is documented on [io.github.aaroncutress.clipboard.html.parseHtml].

# Package io.github.aaroncutress.clipboard.testing

A `RichClipboard` that keeps a clip in memory. Provide it through
`LocalRichClipboard` and a test can assert on what a screen copied without a
system clipboard, a window, or a device in the loop.
