plugins {
    id("npn.kmp-module")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.kover)
}

val generateBuildInfo =
    tasks.register<GenerateBuildInfoTask>("generateBuildInfo") {
        version.set(NpnVersion.of(project).name)
        outDir.set(layout.buildDirectory.dir("generated/buildInfo/commonMain/kotlin"))
    }

kotlin {
    android {
        namespace = "dev.npn.app"
        androidResources {
            enable = true
        }

        // Runs commonTest (the viewmodel suite) against the Android target on a host JVM, so the
        // shared UI logic is verified on both targets rather than only through `desktop`.
        withHostTest {
            // Compose's resource reader probes for an instrumentation context and logs when there
            // is none; android.util.Log throws on a host JVM unless unmocked framework calls
            // return defaults. Nothing under test calls the framework itself.
            isReturnDefaultValues = true
        }
        // Share commonTest's session fakes through the test hierarchy, including the shared suite.
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildInfo.flatMap { it.outDir })
        }
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
            implementation(libs.navigationevent.compose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodel.compose)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            implementation(libs.filekit.dialogs.compose)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            // The daily reminder check runs as WorkManager work, so it survives the app closing.
            implementation(libs.androidx.work.runtime)
        }
        getByName("desktopMain") {
            dependencies {
                implementation(libs.compose.desktop)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        getByName("desktopTest") {
            dependencies {
                // Skiko natives. ViewModel tests assert real wording read from composeResources,
                // and the desktop resource reader initialises Skiko even with no composition.
                implementation(compose.desktop.currentOs)
                // The environment tests query a library's databases to see that a swap closed them.
                implementation(libs.room3.runtime)
            }
        }
        getByName("androidHostTest") {
            // Those same wording assertions, on the Android target. On a device Compose reads
            // composeResources out of the app's assets through a Context; a host test JVM has no
            // Context, and the reader falls back to the classpath. So put the directory the Compose
            // plugin stages for the Android assets on it — it already has the classpath-relative
            // layout the reader looks for — as java resources, which is the one route AGP does not
            // rebuild from scratch for this variant.
            resources.srcDir(
                files(
                    layout.buildDirectory
                        .dir("generated/assets/copyAndroidMainComposeResourcesToAndroidAssets"),
                ) { builtBy("copyAndroidMainComposeResourcesToAndroidAssets") },
            )
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
    kover(project(":core"))
}

// Kotest's JVM engine pulls this agent in through the shared property tests. Android cannot run
// its instrumentation API, and its desktop JNA jar conflicts with core's Android JNA AAR.
// https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-debug/
configurations.named("androidDeviceTestRuntimeClasspath") {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-debug")
}

compose.resources {
    publicResClass = true
}
