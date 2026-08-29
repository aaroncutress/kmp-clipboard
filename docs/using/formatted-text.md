# Formatted text

```kotlin
clipboard.setAnnotatedString(styled)
val styled = clipboard.getAnnotatedString()
```

Under the covers this is `text/html` plus a `text/plain` fallback, because HTML
is what every application on every platform understands formatted text to be.

## Why the library carries a converter

Compose has `AnnotatedString.fromHtml`, and off Android it throws:

```
UnsupportedOperationException: Compose Multiplatform doesn't support fromHtml
```

It is a thin wrapper over Android's `Html.fromHtml`. There is nothing to
delegate to, so the conversion is written out in `commonMain` — see
[`html/`](../../clipboard/src/commonMain/kotlin/io/github/aaroncutress/clipboard/html).

## What survives

Both directions: weight, slant, underline, strikethrough, foreground colour,
background colour, absolute font size, and links. Paragraph alignment survives as
`<p style="text-align:…">`.

Semantic tags are used wherever HTML has one — `<b>`, `<i>`, `<u>`, `<s>`, `<a>`
— because those survive a paste into places a `<span style="font-weight:bold">`
does not: plain-text-with-markdown editors and email clients, both common
clipboard destinations. Everything else becomes inline CSS.

Two things are dropped deliberately:

**`FontFamily`.** A Compose `FontFamily` can be a bundled resource with no name a
stylesheet could refer to. Writing `font-family:sans-serif` for it would be
inventing information.

**Most of `ParagraphStyle`.** Line height, indent and text direction go. The
receiving editor owns its own block layout, and a `line-height` pasted into a
document that has one of its own is noise at best.

## Reading other applications' HTML

The parser is tolerant on purpose: **unknown tags are dropped and their text is
kept**. Clipboard HTML is whatever the source application felt like emitting, and
the two commonest sources are awkward:

- **Word** writes `<o:p>` and a pile of `mso-` CSS.
- **Google Docs** writes `<span style="font-weight:700">` rather than `<b>`, and
  `color:rgb(0,0,0)` rather than hex, inside nested spans carrying
  `id="docs-internal-guid-…"`.

A parser that gave up on either would be useless for the two commonest sources of
formatted text there are. So inline CSS is read for `color`, `background-color`,
`font-size`, `font-weight`, `font-style` and `text-decoration`; colours are
accepted as `#rgb`, `#rrggbb`, `#rrggbbaa`, `rgb(…)`, `rgba(…)` and the common
names; sizes in `px` and `pt`.

Handled tags: `b`, `strong`, `i`, `em`, `cite`, `var`, `u`, `ins`, `s`, `strike`,
`del`, `code`, `kbd`, `samp`, `tt`, `pre`, `sub`, `sup`, `small`, `big`, `a`,
`span`, `font`, `br`, `hr`, `img` (as its `alt` text), `p`, `div`, `blockquote`,
`h1`–`h6`, `ul`, `ol`, `li`, `table`, `tr`, `td`, `th` and the sectioning
elements. `<script>` and `<style>` contents are discarded — they are code, not
text.

Whitespace collapses the way HTML says it does — runs become one space, and space
at the edge of a block disappears — except inside `<pre>`.

Windows `CF_HTML` fragment markers (`<!--StartFragment-->`) are honoured, because
AWT hands over the whole document on some JDKs and only the fragment on others.

## What it does not do

**No CSS cascade.** A `<style>` block at the top of a document is ignored; only
`style` attributes on the elements themselves are read. Implementing the cascade
means implementing selector matching, and clipboard HTML from every major
application carries its formatting inline precisely because it cannot rely on a
stylesheet travelling with it.

**No images.** An `<img>` becomes its `alt` text, or nothing.

**Sp and CSS px are treated as the same unit.** They are not the same thing — sp
scales with the user's font-size setting and a CSS px does not — but they are the
same *number* at default settings, and a clipboard has nowhere to record which
one it meant. The alternative is dropping font sizes entirely.

## Related work

[HtmlConverterCompose](https://github.com/cbeyls/HtmlConverterCompose) is a good
multiplatform HTML → `AnnotatedString` converter with the same target set and a
compatible licence, and it was considered for this. Three things kept it out:

- It converts **one way**. Writing formatted text to a clipboard needs
  `AnnotatedString` → HTML, so that half had to be written regardless.
- It reads only `color` and `background-color` from inline CSS, so the
  `font-weight` / `font-style` / `font-size` that Docs and Word emit inline are
  dropped — which is most of the formatting, from the two sources that matter
  most.
- It skips `<table>` and its contents entirely, and people copy tables constantly.

None of that is a criticism: it is built to render untrusted HTML in a `Text`
(hence colours off by default, for contrast), which is a different objective from
round-trip fidelity. If you want HTML *rendering* rather than clipboard
round-tripping, use it.
