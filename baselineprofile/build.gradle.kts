// Generates androidApp's baseline profile on a connected device: `mise baseline-profile`.
// The profile is committed (androidApp/src/main/generated/baselineProfiles), so builds and CI
// never run this module.
plugins {
    id("com.android.test")
    id("npn.toolchain")
    alias(libs.plugins.androidxBaselineProfile)
}

android {
    namespace = "dev.npn.baselineprofile"

    // compileSdk, minSdk and targetSdk come from the catalog through npn.toolchain.
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":androidApp"
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
