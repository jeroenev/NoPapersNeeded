import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.composeApp)
    implementation(projects.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.mlkit.document.scanner)
}

val npnVersion = NpnVersion.of(project)

// Release signing comes from the environment so the key never touches the repo: the release
// workflow decodes it from secrets, and a local release build can export the same variables.
// Without them assembleRelease still works but produces androidApp-release-unsigned.apk.
val releaseKeystore = providers.environmentVariable("NPN_KEYSTORE_FILE").orNull

android {
    namespace = "dev.npn"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "dev.npn"
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        versionCode = npnVersion.code
        versionName = npnVersion.name
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("androidApp/npn-debug.keystore")
            storePassword = "android"
            keyAlias = "npn-debug"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("NPN_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("NPN_KEY_ALIAS").get()
                // PKCS12 keystores have one password for the store and the key.
                keyPassword = providers.environmentVariable("NPN_KEYSTORE_PASSWORD").get()
            }
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            // Its own package (dev.npn.debug), so a debug build installs next to the release build
            // instead of colliding with it: different signing keys can't update each other's
            // package. src/debug/res gives it its own name and icon colour.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}
