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
    alias(libs.plugins.room3) apply false
}

// Static analysis for all Kotlin source across the four modules. Configured source-only (no type
// resolution) so it does not depend on the AGP/KMP compile classpath. A shared config lives at
// config/detekt/detekt.yml; the root `detekt` task runs over every module's source set.
allprojects {
    apply(plugin = rootProject.libs.plugins.detekt.get().pluginId)

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
                "src/main/kotlin",
                "src/commonTest/kotlin",
                "src/desktopTest/kotlin",
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
