import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":demo"))
            implementation(compose.desktop.currentOs)
        }
    }
}

// Nothing here ships. This window exists so the clipboard can be exercised
// against a real desktop — copy an image out of it into an image editor, paste
// one in from a browser — which is the only test of a clipboard that counts.
compose.desktop {
    application {
        mainClass = "io.github.aaroncutress.clipboard.demo.MainKt"
    }
}
