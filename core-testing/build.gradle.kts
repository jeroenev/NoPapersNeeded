import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Test doubles of core's facets, shared by core's desktopTest and composeApp's commonTest so the
// two suites fake the same interfaces the same way. Only test source sets depend on it, so it never
// reaches a release artifact.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    jvmToolchain(
        libs.versions.jdk
            .get()
            .toInt(),
    )
    jvm("desktop") {
        compilerOptions {
            jvmTarget =
                JvmTarget.fromTarget(
                    libs.versions.jvm.target
                        .get(),
                )
            freeCompilerArgs.add("-Xjdk-release=${libs.versions.jvm.target.get()}")
        }
    }

    androidLibrary {
        namespace = "dev.npn.core.testing"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()

        compilerOptions {
            jvmTarget =
                JvmTarget.fromTarget(
                    libs.versions.jvm.target
                        .get(),
                )
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
