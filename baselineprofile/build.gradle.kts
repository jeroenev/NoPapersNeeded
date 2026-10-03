import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Generates androidApp's baseline profile on a connected device: `mise baseline-profile`.
// The profile is committed (androidApp/src/main/generated/baselineProfiles), so builds and CI
// never run this module.
plugins {
    alias(libs.plugins.androidTest)
    alias(libs.plugins.androidxBaselineProfile)
}

android {
    namespace = "dev.npn.baselineprofile"
    compileSdk =
        libs.versions.android.compileSdk
            .get()
            .toInt()

    defaultConfig {
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()
        targetSdk =
            libs.versions.android.targetSdk
                .get()
                .toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility =
            JavaVersion.toVersion(
                libs.versions.jvm.target
                    .get(),
            )
        targetCompatibility =
            JavaVersion.toVersion(
                libs.versions.jvm.target
                    .get(),
            )
    }

    targetProjectPath = ":androidApp"
}

kotlin {
    jvmToolchain(
        libs.versions.jdk
            .get()
            .toInt(),
    )
    compilerOptions {
        jvmTarget =
            JvmTarget.fromTarget(
                libs.versions.jvm.target
                    .get(),
            )
    }
}

baselineProfile {
    useConnectedDevices = true
}

// Tells the generator which package to drive: the tested APK's applicationId, which carries the
// `.benchmark` suffix androidApp gives its profiling build types.
androidComponents {
    onVariants { variant ->
        val artifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
        variant.instrumentationRunnerArguments.put(
            "targetAppId",
            variant.testedApks.map { artifactsLoader.load(it)?.applicationId },
        )
    }
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
