plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinxSerialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kover) apply false
    alias(libs.plugins.room3) apply false
    alias(libs.plugins.spotless)
}

spotless {
    ratchetFrom("a455ed19723be91ccba4c9274c731e58d910e508")

    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts", "**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
}

val checkLocalization by tasks.registering(LocalizationGuardTask::class) {
    group = "verification"
    description = "Checks translation parity, placeholders, and hardcoded Compose UI strings."
    defaultResourceDirectories.from(
        layout.projectDirectory.dir("composeApp/src/commonMain/composeResources/values"),
        layout.projectDirectory.dir("androidApp/src/main/res/values"),
    )
    dutchResourceDirectories.from(
        layout.projectDirectory.dir("composeApp/src/commonMain/composeResources/values-nl"),
        layout.projectDirectory.dir("androidApp/src/main/res/values-nl"),
    )
    uiSourceDirectory.set(layout.projectDirectory.dir("composeApp/src/commonMain/kotlin/dev/npn/app"))
    hardcodedAllowlist.set(layout.projectDirectory.file("config/localization-hardcoded-allowlist.txt"))
}

tasks.named("check") {
    dependsOn(checkLocalization)
}

// Static analysis for all Kotlin source across the four modules. Configured source-only (no type
// resolution) so it does not depend on the AGP/KMP compile classpath. A shared config lives at
// config/detekt/detekt.yml; the root `detekt` task runs over every module's source set.
allprojects {
    apply(
        plugin =
            rootProject.libs.plugins.detekt
                .get()
                .pluginId,
    )

    detekt {
        parallel = true
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        // Per-module baseline grandfathers pre-existing findings so CI fails only on NEW issues.
        // The baseline is expected to shrink as the flagged code is cleaned up.
        baseline = file("detekt-baseline.xml").takeIf { it.exists() }
        source.setFrom(
            files(
                "src/commonMain/kotlin",
                "src/androidMain/kotlin",
                "src/desktopMain/kotlin",
                "src/jvmSharedMain/kotlin",
                "src/main/kotlin",
                "src/commonTest/kotlin",
                "src/desktopTest/kotlin",
                "src/jvmSharedTest/kotlin",
            ),
        )
    }

    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        reports {
            html.required.set(true)
            sarif.required.set(false)
        }
    }
}
