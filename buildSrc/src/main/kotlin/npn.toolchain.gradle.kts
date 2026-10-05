import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

// Every module compiles with the catalog's `jdk` and emits `jvm-target` bytecode. Desktop code also
// compiles against that release's class library (-Xjdk-release), so it cannot call an API that the
// Android versions it shares code with lack. Applied by every module, whichever Kotlin or Android
// plugin it uses.

val levels = NpnBuildLevels.of(project)
val bytecode = JvmTarget.fromTarget(levels.jvmTarget)
val jdkRelease = "-Xjdk-release=${levels.jvmTarget}"

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

listOf("com.android.application", "com.android.test").forEach { android ->
    pluginManager.withPlugin(android) {
        extensions.getByType<CommonExtension>().compileOptions.apply {
            sourceCompatibility = JavaVersion.toVersion(levels.jvmTarget)
            targetCompatibility = JavaVersion.toVersion(levels.jvmTarget)
        }
        configure<KotlinAndroidProjectExtension> {
            jvmToolchain(levels.jdk)
            compilerOptions.jvmTarget.set(bytecode)
        }
    }
}
