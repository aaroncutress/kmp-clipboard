import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// The host that earns its keep. Web is the platform where the clipboard behaves
// least like the other three — permissions, user activation, a secure context,
// a short list of writable types — and none of that is visible from a compiler.
//
//     ./gradlew :showcase:web:wasmJsBrowserRun
//
// Then: press Copy, paste into a native application. Press Paste, answer the
// permission prompt. Try it once over http:// to watch it refuse.
kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "showcase.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":demo"))
            // `:demo` takes these as `implementation`, so they do not reach a
            // consumer's compile classpath — and this host needs `ComposeViewport`.
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.browser)
        }
    }
}
