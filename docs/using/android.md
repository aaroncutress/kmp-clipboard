# Android: the bundled file provider

This is the one part of the library with a component in your manifest. It is
worth a page because it is surprising, and because you may want to remove it.

## Why it exists

Android's clipboard cannot hold bytes. It holds a `content://` URI, and the
application doing the pasting resolves that URI against whichever provider serves
its authority — which means an image copy needs a `ContentProvider` in the app
doing the **copying**.

Every Android app that copies images has one. Without it, `setImage` has nothing
to put on the clipboard.

So the library ships one:

```xml
<provider
    android:name="io.github.aaroncutress.clipboard.ClipboardFileProvider"
    android:authorities="${applicationId}.kmpclipboard"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/kmp_clipboard_paths" />
</provider>
```

`${applicationId}` is substituted by the manifest merger with your app's own id,
so two apps that both use this library do not collide on an authority, and
neither collides with a provider you already declare.

`exported="false"` with `grantUriPermissions="true"` is the pair that matters:
nothing may query the provider on its own, and the system grants read access to
whichever application receives the clip. That grant is made by the *clipboard
service*, for URIs in the primary clip. The library does not hand out
permissions.

## Why a subclass rather than `FileProvider` itself

Two reasons, and the first is the important one.

**Authority collisions are install failures.** Two components declaring the same
authority cannot be installed together. If this library declared plain
`androidx.core.content.FileProvider`, an app that already has one would fail to
install, and it would be *this library* that broke their build.

**`getType` has to be answerable.** `FileProvider.getType` guesses from the file
extension, and there is no extension that means
`application/vnd.myapp.cells+json`. The subclass answers from what the caller
said the bytes were, falling back to the base class for anything it did not mint
— which is also what happens after a process restart, since the table is
in-memory. Files written by the library therefore get an extension matching their
type wherever one exists.

## Where the files go

`cacheDir/kmp-clipboard/`, pruned of anything older than an hour on each write.

The cache rather than `files/` because these are copies whose only job is to
outlive the copy that made them: the system may delete them when it needs space,
and losing one costs a paste rather than data. An hour is well past any paste
anybody was going to do, and short enough that a session of copying screenshots
does not fill the cache.

## Turning it off

An app with its own `FileProvider`, or a policy against extra manifest
components, removes it with a merger rule:

```xml
<provider
    android:name="io.github.aaroncutress.clipboard.ClipboardFileProvider"
    android:authorities="${applicationId}.kmpclipboard"
    tools:node="remove" />
```

and supplies its own authority:

```kotlin
val context = LocalContext.current
val clipboard = remember(context) {
    AndroidRichClipboard(context, authority = "${context.packageName}.fileprovider")
}
CompositionLocalProvider(LocalRichClipboard provides clipboard) { App() }
```

The authority you pass must serve a directory named `kmp-clipboard` inside the
app's cache — a `<cache-path name="…" path="kmp-clipboard/" />` entry in its paths
file — because that is where the library writes. Copy
[`kmp_clipboard_paths.xml`](../../clipboard/src/androidMain/res/xml/kmp_clipboard_paths.xml).

If you remove the provider and supply nothing, everything except images and files
keeps working, and `capabilities.writesImages` reports false rather than throwing.

## Verifying it

The only test that counts is another application resolving the URI:

```sh
./gradlew :showcase:android:installDebug
```

Copy an image in the demo, then paste it into Gmail or Messages. That exercises
the manifest merge, the provider, the `getType` override and the system's
permission grant in one go, and nothing on a Linux CI runner can stand in for it.
