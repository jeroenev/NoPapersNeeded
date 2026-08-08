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
    jvmToolchain(17)
    jvm("desktop")

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
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.room3.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.koog.agents.core)
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
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.documentfile)
            implementation(libs.pdfbox.android)
            implementation(libs.mlkit.text.recognition)
            implementation(libs.ktor.client.okhttp)
        }
        getByName("desktopMain") {
            dependencies {
                implementation(libs.pdfbox)
                implementation(libs.pdfbox.xmpbox)
                implementation(libs.ktor.client.cio)
            }
        }
        getByName("desktopTest") {
            dependencies {
                implementation(libs.kotest.property)
                implementation(libs.kotest.assertions)
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
