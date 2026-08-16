import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.kover)
}

kotlin {
    jvmToolchain(17)
    jvm("desktop")

    androidLibrary {
        namespace = "dev.npn.app"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core)

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.material.icons.core)
            // Full icon set so semantically correct icons are available (filter, sort, zoom,
            // grid/list, label, ...). R8 strips unused vectors on Android; acceptable on desktop.
            implementation(libs.compose.material.icons.extended)

            implementation(libs.navigation.compose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodel.compose)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            implementation(libs.filekit.dialogs.compose)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotest.assertions)
            implementation(libs.kotest.property)
        }
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }
        getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.common)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        getByName("desktopTest") {
            dependencies {
                // Skiko natives. ViewModel tests assert real wording read from composeResources,
                // and the desktop resource reader initialises Skiko even with no composition.
                implementation(compose.desktop.currentOs)
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
    kover(project(":core"))
}

// ViewModel tests assert real user-facing wording read from `values/common.xml`. Compose resources
// pick a locale from the JVM default, so pin the test JVM to English: without this the suite passes
// on an English machine and fails on a Dutch one by resolving `values-nl` instead.
tasks.withType<Test>().configureEach {
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
}

compose.resources {
    publicResClass = true
}
