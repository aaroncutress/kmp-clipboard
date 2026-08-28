rootProject.name = "kmp-clipboard"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The library, and the only module that gets published. Android, iOS, desktop
// (JVM) and web (JS + Wasm) from one source set, with four platform bridges
// under it.
include(":clipboard")

// The demo, as shared Compose UI: a panel per format, a live inspector showing
// exactly what `peek()` reports, and a readout of what the platform underneath
// admits it can do. It is the only place the whole API is exercised end to end,
// because a clipboard's real test is whether *another application* accepts what
// it wrote.
include(":demo")

// Hosts that put the demo on a screen. None of them ships; they exist so the
// library can be run against a real system clipboard on each platform it claims
// to support — which, for this library, is the entire point.
include(":showcase:desktop")
include(":showcase:android")
include(":showcase:web")
