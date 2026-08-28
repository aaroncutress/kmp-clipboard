import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// The demo is one composable, shared by three hosts. It is not published and it
// is not a sample of good architecture — it is the place where every format this
// library carries gets exercised against a *real* system clipboard, which is the
// only test that matters for a clipboard and the one no unit test can perform.
//
// Material 3 lives here and nowhere else. `:clipboard` draws nothing, and
// `:clipboard:checkDependencyBudget` fails the build if Material or Foundation
// ever reaches it.
kotlin {
    jvm {
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }

    iosArm64()
    iosSimulatorArm64()

    js { browser() }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    android {
        namespace = "io.github.aaroncutress.clipboard.demo"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":clipboard"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
        }
    }
}
