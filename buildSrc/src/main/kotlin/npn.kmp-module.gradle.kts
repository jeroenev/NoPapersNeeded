// A multiplatform module of the app: the `desktop` JVM target and an Android library at the
// catalog's SDK levels, built on the shared toolchain (npn.toolchain). The module names its Android
// namespace and adds its own source sets, tests and dependencies.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("npn.toolchain")
}

val levels = NpnBuildLevels.of(project)

kotlin {
    jvm("desktop")

    android {
        compileSdk = levels.compileSdk
        minSdk = levels.minSdk
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}
