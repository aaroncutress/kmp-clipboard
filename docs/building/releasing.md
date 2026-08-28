# Releasing

## Cutting a release

```sh
git tag v0.2.0 && git push origin v0.2.0
```

That is the whole thing. CI publishes `io.github.aaroncutress:kmp-clipboard:0.2.0`
to GitHub Packages.

**Tagging a commit that already passed CI does not rebuild it.** The `guard` job
looks for a successful run on the same SHA and, finding one, skips `test` and
`targets` and goes straight to publishing. Push to main, wait for green, then tag
— the tag run takes about as long as the publish itself.

To cut one without tagging — from a branch, or to re-publish — run the **CI**
workflow manually and give it a version. That is the only other way to get a
publishable version out of the build.

## Where the version comes from

Three sources, in this order:

1. `-Pclipboard.version=…`, for a deliberate publish that is not a tag.
2. The tag being built — and **only** a tag.
3. `0.1.0-SNAPSHOT`, for everything else.

Rule 2 reads `GITHUB_REF_TYPE` before `GITHUB_REF_NAME`. The obvious version of
this reads the name alone, and a branch build then takes the *branch* as its
version — which is how you come to publish a coordinate with a slash in it. The
result is then checked against a regex, because the failure mode is silent and a
package registry has no undo.

```sh
./gradlew :clipboard:coordinate   # what this build would publish as
```

Worth running before any manual publish. Without it there is no way to ask which
of the three sources won short of doing the upload and reading it back.

## Maven Central

Central Portal replaced OSSRH in June 2025 and does not accept a `maven-publish`
deploy — it takes a *bundle*, one zip laid out as a repository, uploaded to its
publisher API.

```sh
./gradlew :clipboard:centralBundle
# build/kmp-clipboard-<version>-central.zip → https://central.sonatype.com/publishing
```

Manual on purpose: an upload that happens as a side effect of a green build is an
upload nobody chose.

## Signing

Central requires a signature; GitHub Packages does not, and neither does
`publishToMavenLocal`. So the presence of a key is the switch:

```sh
SIGNING_KEY=$(cat private.asc) SIGNING_PASSWORD=… ./gradlew :clipboard:centralBundle
```

With no `SIGNING_KEY`, signing is off and `isRequired` is false. Making it
unconditional would mean every developer needs a GPG key to try anything.

## What gets published

Six publications: the root `kmp-clipboard` module plus `-android`, `-jvm`, `-js`,
`-wasm-js`, `-iosarm64` and `-iossimulatorarm64`. The Gradle project is
`:clipboard` and the artifact is `kmp-clipboard`, so the artifactIds are renamed
once over the whole set — three spellings of the same string is how two of them
end up disagreeing.

**Published from Linux.** Kotlin 2.x cross-compiles Apple *klibs*, which is
exactly what a library consumer resolves. What still needs Xcode is linking a
framework, and that happens in the consuming app.
