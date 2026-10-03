import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
    alias(libs.plugins.room3)
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

    // `jvmShared` is what Android and desktop have in common beyond commonMain: java.nio and
    // java.security. The direct-filesystem library implementation lives there so Android's
    // non-SAF storage mode is the very same class the desktop app runs, not a port of it.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

    androidLibrary {
        namespace = "dev.npn.core"
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

        // Runs commonTest against the Android target on a host JVM, and gives androidMain code
        // (SAF, MLKit wrappers) a test source set it never had. Without it, everything in
        // commonTest is only ever verified through the `desktop` target.
        withHostTest {}
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        // The hierarchy template matches the `desktop` jvm target but not the AGP multiplatform
        // library target, so the Android edge into `jvmShared` is drawn by hand.
        val jvmSharedMain by getting
        val jvmSharedTest by getting
        jvmSharedMain.dependencies {
            compileOnly(libs.jna)
        }
        androidMain.get().dependsOn(jvmSharedMain)
        getByName("androidHostTest").dependsOn(jvmSharedTest)

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.room3.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.koog.agents.core)
            implementation(libs.koog.agents.features.memory)
            implementation(libs.koog.agents.tools)
            implementation(libs.koog.prompt.executor.model)
            implementation(libs.koog.prompt.executor.anthropic)
            implementation(libs.koog.prompt.executor.deepseek)
            implementation(libs.koog.prompt.executor.google)
            implementation(libs.koog.prompt.executor.mistralai)
            implementation(libs.koog.prompt.executor.ollama)
            implementation(libs.koog.prompt.executor.openai)
            implementation(libs.koog.prompt.executor.openrouter)
            implementation(libs.koog.http.client.ktor)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            // commonTest asserts through kotest matchers, so they belong to every test target that
            // compiles it, not just `desktopTest`.
            implementation(libs.kotest.assertions)
        }
        androidMain.dependencies {
            implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.documentfile)
            implementation(libs.pdfbox.android)
            implementation(libs.mlkit.text.recognition)
            implementation(libs.ktor.client.okhttp)
        }
        getByName("androidDeviceTest") {
            dependencies {
                implementation(libs.androidx.test.junit)
                implementation(libs.androidx.test.runner)
            }
        }
        getByName("desktopMain") {
            dependencies {
                implementation(libs.jna)
                implementation(libs.pdfbox)
                implementation(libs.pdfbox.xmpbox)
                implementation(libs.ktor.client.cio)
            }
        }
        getByName("desktopTest") {
            dependencies {
                implementation(libs.kotest.property)
                implementation(libs.pdfbox)
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.room3.compiler)
    add("kspDesktop", libs.room3.compiler)
}
