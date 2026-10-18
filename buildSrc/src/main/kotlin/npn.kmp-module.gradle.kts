// A multiplatform module of the app: the `desktop` JVM target and an Android library at the
// catalog's SDK levels, built on the shared toolchain (npn.toolchain), with the test libraries every
// module's tests share. The module names its Android namespace and adds its own source sets, test
// targets and dependencies.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    // Android lint for the main code: the multiplatform library plugin only lints host tests.
    id("com.android.lint")
    id("npn.toolchain")
}

val levels = NpnBuildLevels.of(project)
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

// The one lint configuration, set on both lint extensions: the project's (the desktop lint `check`
// runs here) and the Android target's (its lint, and androidApp's lintDebug through
// checkDependencies). Neither reaches the other's lint model. The app modules get the same file
// from npn.toolchain.
val lintXml = rootProject.file("config/lint/lint.xml")
extensions.configure<com.android.build.api.dsl.Lint> {
    lintConfig = lintXml
}

fun library(alias: String) = catalog.findLibrary(alias).orElseThrow { error("No library '$alias' in the catalog") }

kotlin {
    jvm("desktop")

    android {
        compileSdk = levels.compileSdk
        minSdk = levels.minSdk
        lint.lintConfig = lintXml
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        // Tests drive virtual time and test dispatchers, kotlinx-coroutines-test's experimental API.
        matching { it.name.endsWith("Test") }.configureEach {
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
        // commonTest asserts through kotest matchers, so they belong to every test target that
        // compiles it, not just `desktopTest`.
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(library("kotlinx-coroutines-test"))
            implementation(library("kotest-assertions"))
            implementation(library("kotest-property"))
            // core-testing's fakes, for every module but core-testing itself.
            if (path != ":core-testing") implementation(project(":core-testing"))
        }
        matching { it.name == "androidDeviceTest" }.configureEach {
            dependencies {
                implementation(library("androidx-test-junit"))
                implementation(library("androidx-test-runner"))
            }
        }
    }
}
