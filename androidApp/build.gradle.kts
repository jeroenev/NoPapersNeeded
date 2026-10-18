plugins {
    id("com.android.application")
    id("npn.toolchain")
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.androidxBaselineProfile)
}

dependencies {
    implementation(projects.composeApp)
    implementation(projects.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.mlkit.document.scanner)
    // Installs the baseline profile (and the ones the libraries ship) on sideloaded installs, which
    // Play would otherwise do: the APK comes from GitHub releases.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(projects.baselineprofile)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

val npnVersion = NpnVersion.of(project)

// Release signing comes from the environment so the key never touches the repo: the release
// workflow decodes it from secrets, and a local release build can export the same variables.
// Without them assembleRelease still works but produces androidApp-release-unsigned.apk.
val releaseKeystore = providers.environmentVariable("NPN_KEYSTORE_FILE").orNull

android {
    namespace = "dev.npn"

    // compileSdk, minSdk, targetSdk and the test runner come from npn.toolchain.
    defaultConfig {
        applicationId = "dev.npn"
        versionCode = npnVersion.code
        versionName = npnVersion.name
    }
    // `lintDebug` also lints core and composeApp (checkDependencies), so one report covers the
    // Android code of the whole app. Errors fail the build; the configuration is npn.toolchain's.
    lint {
        checkDependencies = true
        abortOnError = true
    }
    // The app picks its language in its own settings, so an App Bundle install needs every
    // language's resources, not only the device's.
    bundle {
        language {
            enableSplit = false
        }
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
            // R8 drops unused code and resources and optimizes the rest. Libraries ship their own
            // keep rules; proguard-rules.pro only covers what reflects into code without them.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

// `mise baseline-profile` regenerates the committed profile; builds only package it.
baselineProfile {
    mergeIntoMain = true
}

// The baseline profile plugin adds release-like build types to profile and benchmark. Give them
// their own package, like debug, so they install next to the release build instead of colliding
// with its signature, and propose their own library folder.
androidComponents {
    onVariants(selector().withBuildType("nonMinifiedRelease")) { it.applicationId.set("dev.npn.benchmark") }
    onVariants(selector().withBuildType("benchmarkRelease")) { it.applicationId.set("dev.npn.benchmark") }
}
