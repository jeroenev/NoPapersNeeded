// Kotlin, the Android Gradle plugin and detekt are not declared here: buildSrc's convention plugins
// put them on every build script's classpath.
plugins {
    alias(libs.plugins.androidxBaselineProfile) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.kotlinxSerialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.room3) apply false
    alias(libs.plugins.spotless)
}

// Every Kotlin file in the repository. Sources live under each module's src/, so the file walk never
// enters a build directory another task may be writing to.
spotless {
    kotlin {
        target("*/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts", "*/src/**/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}

val checkLocalization by tasks.registering(LocalizationGuardTask::class) {
    group = "verification"
    description = "Checks every translation's keys and placeholders, and hardcoded Compose UI strings."
    resourceRoots.from(
        layout.projectDirectory.dir("composeApp/src/commonMain/composeResources"),
        layout.projectDirectory.dir("androidApp/src/main/res"),
    )
    composeResourceRoots.from(layout.projectDirectory.dir("composeApp/src/commonMain/composeResources"))
    uiSourceDirectory.set(layout.projectDirectory.dir("composeApp/src/commonMain/kotlin/dev/npn/app"))
    hardcodedAllowlist.set(layout.projectDirectory.file("config/localization-hardcoded-allowlist.txt"))
}

tasks.named("check") {
    dependsOn(checkLocalization)
}

// Device tests only run on a device, so CI only builds them: it compiles each module's and runs the
// packaging steps an install would fail at (duplicate classes, merged Java resources).
tasks.register("compileDeviceTests") {
    group = "verification"
    description = "Compiles every module's device tests and checks they package, without a device."
    dependsOn(
        ":core:compileAndroidDeviceTest",
        ":composeApp:compileAndroidDeviceTest",
        ":androidApp:compileDebugAndroidTestKotlin",
        ":core:checkAndroidDeviceTestDuplicateClasses",
        ":composeApp:checkAndroidDeviceTestDuplicateClasses",
        ":androidApp:checkDebugAndroidTestDuplicateClasses",
        ":core:mergeAndroidDeviceTestJavaResource",
        ":composeApp:mergeAndroidDeviceTestJavaResource",
        ":androidApp:mergeDebugAndroidTestJavaResource",
    )
}

// Static analysis, the `detekt` and `detektTyped` runs, for every project (see npn.detekt).
allprojects {
    apply(plugin = "npn.detekt")
}
