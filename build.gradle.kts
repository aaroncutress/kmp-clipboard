plugins {
    // Loaded once here so subproject classloaders share them.
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.dokka) apply false
}

// ---------------------------------------------------------------------------
// Compose compiler reports
// ---------------------------------------------------------------------------
//
// What the compiler already knows and never said out loud: which composables are
// skippable, which are restartable, and which parameters it considers unstable.
//
//     ./gradlew :clipboard:compileKotlinJvm -Pclipboard.compose.reports
//     build/compose-reports/<module>/*-composables.txt   what is skippable
//     build/compose-reports/<module>/*-classes.txt       what is stable
//
// Behind a property because it makes every Compose compilation slower and writes
// files nobody wants in a normal build. Off, this block costs one `isPresent`
// check at configuration time.
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.plugin.compose") {
        extensions.configure<org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension> {
            // Always on: it changes what the compiler emits, so it has to be the
            // same in a normal build as in a measured one. See the file itself.
            stabilityConfigurationFiles.add(
                rootProject.layout.projectDirectory.file("compose-stability.conf")
            )

            if (providers.gradleProperty("clipboard.compose.reports").isPresent) {
                val into = rootProject.layout.buildDirectory.dir("compose-reports/${project.name}")
                metricsDestination.set(into)
                reportsDestination.set(into)
            }
        }
    }
}
