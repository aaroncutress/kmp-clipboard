import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.dokka)
    `maven-publish`
    signing
}

// The Gradle project is `:clipboard`; the published artifact is `kmp-clipboard`.
// Named once, here, because it is used by the coordinate task, by the artifactId
// rename and by the Central bundle, and three spellings of the same string is
// how two of them end up disagreeing.
val ARTIFACT = "kmp-clipboard"

// ---------------------------------------------------------------------------
// Publishing
// ---------------------------------------------------------------------------
//
// A release is `git tag v0.2.0` and nothing else. Three sources, in this order:
//
//  1. `-Pclipboard.version=…`, for a deliberate publish that is not a tag.
//  2. The tag being built — and **only** a tag.
//  3. `0.1.0-SNAPSHOT`, for everything else.
//
// Rule 2 reads `GITHUB_REF_TYPE` before `GITHUB_REF_NAME`, because the obvious
// version of this reads the name alone and a branch build then takes the
// *branch* as its version — which is how you come to publish a coordinate with
// a slash in it. The result is checked against a regex afterwards, because the
// failure mode is silent: a package registry has no undo.
//
// Everything is read through `providers`, never `System.getenv`. The
// configuration cache is on (`gradle.properties`), and a direct environment read
// at configuration time is invisible to it: the cached entry would be reused
// with last run's token baked in.
group = "io.github.aaroncutress"
version = providers.gradleProperty("clipboard.version")
    .orElse(
        providers.environmentVariable("GITHUB_REF_TYPE")
            .zip(providers.environmentVariable("GITHUB_REF_NAME")) { type, name ->
                if (type == "tag") name.removePrefix("v") else ""
            }
            .filter { it.isNotEmpty() }
    )
    .orElse("0.1.0-SNAPSHOT")
    .map { candidate ->
        require(Regex("""^\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?$""").matches(candidate)) {
            "`$candidate` is not a publishable version. A release tag is `v1.2.3`, " +
                "optionally with a `-rc1`-style suffix; anything else has to come " +
                "through -Pclipboard.version. This check exists because the " +
                "alternative is noticing after the upload."
        }
        candidate
    }
    .get()

/**
 * What this build would publish as.
 *
 *     ./gradlew :clipboard:coordinate
 *
 * The version comes from three sources with a precedence between them, and
 * without this there is no way to ask which one won short of running a publish
 * and reading the upload.
 */
tasks.register("coordinate") {
    group = "help"
    description = "Prints the group:artifact:version this build would publish as."
    // `project.group`, spelled out. Inside a task configuration block a bare
    // `group` is the *task's* group — the "help" set on the line above — so the
    // obvious version of this prints `help:kmp-clipboard:0.1.0-SNAPSHOT`.
    //
    // Captured at configuration time: a `doLast` that read `project` would hold
    // the project reference and break the configuration cache, which is on.
    val coordinate = "${project.group}:$ARTIFACT:${project.version}"
    doLast { println(coordinate) }
}

// The Gradle project is `:clipboard` because that is what it is called in a
// repository named `kmp-clipboard`; the artifact is `kmp-clipboard` because
// `io.github.aaroncutress:clipboard` says nothing about what it is. The
// multiplatform plugin derives every artifactId from the project name, so the
// rename happens here — once, over the whole set, so the root module and its
// `-jvm`/`-android`/`-js` siblings cannot drift apart.
publishing.publications.withType<MavenPublication>().configureEach {
    artifactId = artifactId.replaceFirst(project.name, ARTIFACT)
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/aaroncutress/kmp-clipboard")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }

        // Maven Central, the long way round.
        //
        // Central Portal replaced OSSRH in June 2025 and does not accept a
        // `maven-publish` deploy: it takes a *bundle* — one zip laid out as a
        // repository — uploaded to its publisher API. So this writes the
        // repository to disk and `centralBundle` zips it. See the task below.
        maven {
            name = "CentralBundle"
            url = uri(layout.buildDirectory.dir("central-bundle"))
        }
    }

    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "kmp-clipboard"
            description = "A rich clipboard for Compose Multiplatform: text, formatted " +
                "text, images and files on Android, iOS, desktop and web, behind one " +
                "LocalRichClipboard."
            url = "https://github.com/aaroncutress/kmp-clipboard"
            licenses {
                license {
                    name = "The Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                }
            }
            developers {
                developer {
                    id = "aaroncutress"
                    name = "Aaron Cutress"
                    url = "https://github.com/aaroncutress"
                }
            }
            scm {
                url = "https://github.com/aaroncutress/kmp-clipboard"
                connection = "scm:git:https://github.com/aaroncutress/kmp-clipboard.git"
                developerConnection = "scm:git:ssh://git@github.com/aaroncutress/kmp-clipboard.git"
            }
        }
    }
}

// Signed only when there is a key to sign with.
//
// Central requires a signature; GitHub Packages does not, and neither does a
// developer running `publishToMavenLocal` to try something. Making the signing
// plugin unconditional would mean every one of those needs a GPG key on the
// machine, so the key's presence is the switch. `required` is set the same way,
// because `signing.sign()` on an absent key otherwise fails at execution time
// with a message about a missing property rather than about signing.
val signingKey = providers.environmentVariable("SIGNING_KEY")
val signingPassword = providers.environmentVariable("SIGNING_PASSWORD")

signing {
    isRequired = signingKey.isPresent
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull.orEmpty())
        sign(publishing.publications)
    }
}

/**
 * The zip you upload to Central Portal.
 *
 *     ./gradlew :clipboard:centralBundle
 *     build/kmp-clipboard-<version>-central.zip  →  https://central.sonatype.com/publishing
 *
 * Central Portal wants a bundle rather than a deploy, so this is two steps:
 * publish into a local repository under `build/central-bundle`, then zip it.
 * Manual on purpose — an upload that happens as a side effect of a green build
 * is an upload nobody chose.
 */
val centralBundle = tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Packages the artifacts as a bundle for upload to Central Portal."
    dependsOn(tasks.named("publishAllPublicationsToCentralBundleRepository"))
    from(layout.buildDirectory.dir("central-bundle"))
    destinationDirectory = layout.buildDirectory
    archiveFileName = "$ARTIFACT-$version-central.zip"
}

// ---------------------------------------------------------------------------
// The API reference
// ---------------------------------------------------------------------------
//
//     ./gradlew :clipboard:dokkaGenerateHtml     # build/dokka/html
//
// A warning here is a broken cross-reference in the KDoc — a `[Link]` to a
// symbol that was renamed or deleted — and without `failOnWarning` Dokka prints
// it and succeeds, which makes the CI step an artifact upload rather than a
// gate. That is a class of defect nothing else in this build can see.
dokka {
    dokkaPublications.configureEach {
        failOnWarning = true
    }

    // A display name with a space in it becomes a slugified *directory* in the
    // output and every link on the index carries an unencoded space. This
    // matches the repository, and stays a legal path segment.
    moduleName = "kmp-clipboard"
    // The same value the artifact gets, so a page and a jar cannot disagree
    // about which release they are.
    moduleVersion = version.toString()

    dokkaSourceSets.configureEach {
        // The module and package overviews. Without this every package index is
        // a bare list of symbols with no statement of what the package is for.
        includes.from("Module.md")

        // The library is common code first; the JDK is an implementation detail
        // of one of its five targets. Linking to it makes `String` on a page
        // about a multiplatform type point at java.lang, and the lookup needs
        // the network at build time — which then fails on any runner that
        // cannot reach docs.oracle.com and prints identical warnings while
        // succeeding anyway.
        enableJdkDocumentationLink = false

        sourceLink {
            localDirectory = layout.projectDirectory.dir("src").asFile
            remoteUrl("https://github.com/aaroncutress/kmp-clipboard/tree/main/clipboard/src")
            remoteLineSuffix = "#L"
        }
    }
}

kotlin {
    // ---------------------------------------------------------------------
    // Warnings are errors, in the library's own code
    // ---------------------------------------------------------------------
    //
    // **Main compilations only.** Test sources stay permissive deliberately: a
    // build that refuses to compile until every deprecation in every test is
    // migrated is a build that makes upgrading a dependency the most expensive
    // thing you can do. A deprecation in *shipped* code is a defect; a
    // deprecation in a test is a migration waiting for a quiet afternoon.
    compilerOptions {
        // `expect object ImageCodec` is an expect *class*, and the compiler
        // reports every one of those as "in Beta" (KT-61573). It has been Beta
        // since 1.9 and is what the whole `expect`/`actual` mechanism is made
        // of; with `allWarningsAsErrors` below, not opting in means the library
        // cannot declare a platform seam at all.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    targets.configureEach {
        compilations.configureEach {
            if (name == "main") {
                compileTaskProvider.configure {
                    compilerOptions.allWarningsAsErrors.set(true)
                }
            }
        }
    }

    jvm()

    iosArm64()
    iosSimulatorArm64()

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    android {
        namespace = "io.github.aaroncutress.clipboard"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        // The library ships one XML file: `res/xml/kmp_clipboard_paths.xml`,
        // which is what `ClipboardFileProvider` reads to decide which cache
        // directory it will serve. Without resources enabled the provider has
        // nothing to point at and every image copy fails at runtime.
        androidResources {
            enable = true
        }
        // `commonTest` exists, so without this the Android target quietly has no
        // test compilation and the build says so on every run. The model and
        // HTML tests are pure Kotlin and pass here as they do on the JVM; what
        // this adds is that they are compiled against the *Android* actuals.
        withHostTest {}
    }

    // ---------------------------------------------------------------------
    // `skikoMain`, and why there is an intermediate source set at all
    // ---------------------------------------------------------------------
    //
    // The clipboard carries images as encoded bytes, so somewhere this library
    // has to turn an `ImageBitmap` into a PNG and back. There are exactly two
    // ways to do that across five targets: `android.graphics.Bitmap` on
    // Android, and Skia everywhere else — because desktop, iOS and web all
    // render through Skiko already, so the encoder is on the classpath whether
    // this library uses it or not.
    //
    // Written per target that would be four implementations of the same twenty
    // lines, three of them identical. This edge makes it two.
    // Declared as an *extension* of the default hierarchy template, not as
    // hand-written `dependsOn` edges. Adding edges by hand switches the default
    // template off — the build says so — and the template is what creates
    // `iosMain` and `webMain` in the first place, so doing it that way leaves
    // both orphaned and this library with no iOS or web source set at all.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("skiko") {
                withJvm()
                withIos()
                withJs()
                withWasmJs()
            }
        }
    }

    sourceSets {
        val skikoMain by getting

        commonMain.dependencies {
            // `api`, not `implementation`: `ImageBitmap` and `AnnotatedString`
            // are in the public signatures of the convenience layer, and
            // `CompositionLocal` is in `LocalRichClipboard`'s. A consumer
            // cannot call into this library without them on its own compile
            // classpath.
            //
            // Foundation and Material are deliberately absent — this library
            // draws nothing — and `checkDependencyBudget` at the bottom of this
            // file fails the build if either arrives transitively.
            api(libs.compose.runtime)
            api(libs.compose.ui)

            // `implementation`: the public API is `suspend`, which needs no
            // coroutines type in a signature. Dispatchers and `withContext` are
            // an implementation detail of the platform bridges.
            implementation(libs.kotlinx.coroutines.core)
        }

        skikoMain.dependencies {
            // Already present transitively through `compose.ui`. Declared so
            // that the version this compiles against is a decision rather than
            // a consequence of Compose's dependency graph.
            implementation(libs.skiko)
        }

        androidMain.dependencies {
            // `FileProvider`, and nothing else.
            implementation(libs.androidx.core)
        }

        webMain.dependencies {
            // `Blob`, `window`, and the DOM types the Clipboard API traffics in.
            implementation(libs.kotlinx.browser)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmTest.dependencies {
            // Skiko's *native* library. `compose.ui` brings the Kotlin half of
            // Skia on the JVM but not the `.so`, so `ImageBitmap(w, h)` throws
            // `LibraryLoadException` at class-init time without this — which
            // takes out the whole image codec suite, the one that covers the
            // encoder three of the five targets share.
            //
            // Test-only, so it stays clear of `checkDependencyBudget`, which
            // reads the *main* runtime classpath.
            implementation(compose.desktop.currentOs)
        }
    }
}

// ---------------------------------------------------------------------------
// The dependency budget
// ---------------------------------------------------------------------------
//
// This library is a clipboard. It draws nothing, lays nothing out, and has no
// opinion about how anything looks — so Compose Foundation and Material have no
// business on its classpath, and neither does anything else that arrives
// without being asked for.
//
// The risk is not that someone types `import androidx.compose.material3` — that
// is easy to spot in review. It is that a convenience library pulls one in
// transitively and nobody notices until a consumer's app is 400 KB heavier for
// a dependency they were told they did not have.
//
// This walks the fully resolved JVM runtime graph and fails if a forbidden
// group appears anywhere in it. One target is enough: the only per-platform
// dependencies are `androidx.core` and `kotlinx-browser`, so anything general
// enough to reach Android or web reaches the JVM classpath too.
//
// It also writes the whole resolved graph to a report, because "did this get
// bigger" is a question the pass/fail answer cannot settle.
// ---------------------------------------------------------------------------
val forbiddenGroups = setOf(
    "androidx.compose.foundation",
    "androidx.compose.material",
    "androidx.compose.material3",
    "org.jetbrains.compose.foundation",
    "org.jetbrains.compose.material",
    "org.jetbrains.compose.material3",
)

val jvmRuntimeGraph: Provider<ResolvedComponentResult> =
    configurations.named("jvmRuntimeClasspath")
        .flatMap { it.incoming.resolutionResult.rootComponent }

val checkDependencyBudget = tasks.register("checkDependencyBudget") {
    group = "verification"
    description = "Fails if a drawing dependency reaches the :clipboard classpath."

    val root = jvmRuntimeGraph
    val forbidden = forbiddenGroups
    inputs.property("forbiddenGroups", forbidden)
    outputs.file(layout.buildDirectory.file("reports/dependency-budget.txt"))

    val report = layout.buildDirectory.file("reports/dependency-budget.txt")

    doLast {
        val visited = mutableSetOf<String>()
        val resolved = sortedSetOf<String>()
        val offenders = sortedSetOf<String>()

        fun walk(component: ResolvedComponentResult) {
            if (!visited.add(component.id.displayName)) return
            component.moduleVersion?.let { id ->
                resolved += "${id.group}:${id.name}:${id.version}"
                if (id.group in forbidden) {
                    offenders += "${id.group}:${id.name}:${id.version}"
                }
            }
            component.dependencies
                .filterIsInstance<ResolvedDependencyResult>()
                .forEach { walk(it.selected) }
        }
        walk(root.get())

        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(resolved.joinToString("\n", postfix = "\n"))
        }

        if (offenders.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("A drawing dependency reached the :clipboard classpath:")
                    offenders.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("This library is a clipboard: it needs the Compose runtime to hold a")
                    appendLine("CompositionLocal and compose-ui for ImageBitmap and AnnotatedString.")
                    appendLine("Nothing above that. Find what pulls this in with:")
                    appendLine("  ./gradlew :clipboard:dependencies --configuration jvmRuntimeClasspath")
                }
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkDependencyBudget)
}
