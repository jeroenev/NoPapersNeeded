import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.KotlinMultiplatformAndroidDeviceTestCompilation
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.android.build.api.dsl.TestExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

// Every module compiles with the catalog's `jdk` and emits `jvm-target` bytecode. Desktop code also
// compiles against that release's class library (-Xjdk-release), so it cannot call an API that the
// Android versions it shares code with lack. Applied by every module, whichever Kotlin or Android
// plugin it uses. Every Android module's device tests also run on the one instrumentation runner.

val levels = NpnBuildLevels.of(project)
val bytecode = JvmTarget.fromTarget(levels.jvmTarget)
val jdkRelease = "-Xjdk-release=${levels.jvmTarget}"
val testRunner = "androidx.test.runner.AndroidJUnitRunner"

// Every Kotlin compilation treats warnings as errors, so a deprecation or an unused result is
// fixed where it appears instead of piling up. `-Pnpn.werror=false` (or the same line in
// ~/.gradle/gradle.properties) turns that off locally, e.g. while trying a dependency upgrade.
val warningsAsErrors = providers.gradleProperty("npn.werror").map { it.toBoolean() }.orElse(true)
tasks.withType<KotlinCompilationTask<*>>().configureEach {
    compilerOptions.allWarningsAsErrors.set(warningsAsErrors)
    // Fixes for language bugs take effect now instead of after a deprecation cycle.
    compilerOptions.progressiveMode.set(true)
}

pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    configure<KotlinMultiplatformExtension> {
        jvmToolchain(levels.jdk)
        targets.withType<KotlinJvmTarget>().configureEach {
            compilerOptions {
                jvmTarget.set(bytecode)
                freeCompilerArgs.add(jdkRelease)
            }
        }
        targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
            compilerOptions.jvmTarget.set(bytecode)
            // A module that turns device tests on (`withDeviceTest`) gets the runner here.
            compilations.withType<KotlinMultiplatformAndroidDeviceTestCompilation>().configureEach {
                instrumentationRunner = testRunner
            }
        }
    }
}

pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
    configure<KotlinJvmProjectExtension> {
        jvmToolchain(levels.jdk)
        compilerOptions {
            jvmTarget.set(bytecode)
            freeCompilerArgs.add(jdkRelease)
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(levels.jvmTarget.toInt())
    }
}

// The app and the baseline-profile test app also get the catalog's SDK levels and the test runner
// here; the multiplatform libraries get their SDK levels from npn.kmp-module.
listOf("com.android.application", "com.android.test").forEach { android ->
    pluginManager.withPlugin(android) {
        val extension = extensions.getByType<CommonExtension>()
        extension.compileSdk = levels.compileSdk
        extension.defaultConfig.minSdk = levels.minSdk
        extension.defaultConfig.testInstrumentationRunner = testRunner
        when (extension) {
            is ApplicationExtension -> extension.defaultConfig.targetSdk = levels.targetSdk
            is TestExtension -> extension.defaultConfig.targetSdk = levels.targetSdk
        }
        extension.compileOptions.apply {
            sourceCompatibility = JavaVersion.toVersion(levels.jvmTarget)
            targetCompatibility = JavaVersion.toVersion(levels.jvmTarget)
        }
        configure<KotlinAndroidProjectExtension> {
            jvmToolchain(levels.jdk)
            compilerOptions.jvmTarget.set(bytecode)
        }
    }
}
