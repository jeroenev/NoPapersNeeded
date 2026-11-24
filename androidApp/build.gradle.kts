import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.util.Properties
import javax.inject.Inject

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
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.work.runtime)
    implementation(libs.mlkit.document.scanner)
    // Installs the baseline profile (and the ones the libraries ship) on sideloaded installs, which
    // Play would otherwise do: the APK comes from GitHub releases.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(projects.baselineprofile)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// --- Bundled rclone --------------------------------------------------------
// The app shells out to rclone for cloud sync, and a phone has no rclone to find, so the engine is
// cross-compiled into the APK. Android may only execute files from an app's native library
// directory, so the executable travels as `jniLibs/<abi>/librclone.so` and is run from there (the
// same trick RoundSync uses). cgo is mandatory: without the NDK's libc, a pure-Go build has no
// working DNS on Android.
//
// `-Pnpn.rclone.skip=true` produces a build without the engine (fast local iteration on anything
// that does not touch sync); a release must not use it.
private val rcloneVersion = "v1.76.0"
private val rcloneNdkVersion = "29.0.14206865"
private val rcloneAndroidApi = "26"

val rcloneSkip: Boolean = providers.gradleProperty("npn.rclone.skip").orNull?.toBoolean() ?: false
val rcloneAbis: List<String> =
    (providers.gradleProperty("npn.rclone.abis").orNull ?: "arm64-v8a")
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

abstract class BuildRcloneAndroidTask
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:Input
        abstract val version: Property<String>

        @get:Input
        abstract val ndkVersion: Property<String>

        @get:Input
        abstract val apiLevel: Property<String>

        @get:Input
        abstract val abis: ListProperty<String>

        @get:Input
        abstract val sdkDir: Property<String>

        /**
         * The Go toolchain on PATH (`go env GOVERSION`), so a Go upgrade rebuilds the engine instead
         * of reusing one an older compiler produced.
         */
        @get:Input
        abstract val goVersion: Property<String>

        /** The module the go tool downloads rclone into; keeps the checkout per project. */
        @get:Internal
        abstract val moduleDir: DirectoryProperty

        /** `<abi>/librclone.so` per ABI, registered as a jniLibs source dir. */
        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun build() {
            val work = moduleDir.get().asFile.apply { mkdirs() }
            val ndk = File(sdkDir.get(), "ndk/${ndkVersion.get()}")
            if (!ndk.isDirectory) {
                throw GradleException(
                    "Android NDK ${ndkVersion.get()} not found at $ndk. Install it with\n" +
                        "  sdkmanager --install \"ndk;${ndkVersion.get()}\"",
                )
            }
            val toolchain = File(ndk, "toolchains/llvm/prebuilt/${hostTag()}/bin")
            if (!File(work, "go.mod").exists()) {
                runCommand("go", "mod", "init", "npn-rclone", workDir = work)
            }
            runCommand("go", "get", "github.com/rclone/rclone@${version.get()}", workDir = work)
            for (abi in abis.get()) {
                val out = File(outputDir.get().asFile, abi).apply { mkdirs() }
                runCommand(
                    "go",
                    "build",
                    "-tags",
                    "android noselfupdate",
                    "-trimpath",
                    "-ldflags",
                    "-buildid= -s -w -X github.com/rclone/rclone/fs.Version=${version.get()}",
                    "-o",
                    File(out, "librclone.so").absolutePath,
                    "github.com/rclone/rclone",
                    workDir = work,
                    environment = abiEnvironment(abi, toolchain),
                )
            }
        }

        private fun abiEnvironment(
            abi: String,
            toolchain: File,
        ): Map<String, String> {
            val (goarch, compilers) =
                when (abi) {
                    "armeabi-v7a" -> "arm" to listOf("armv7a-linux-androideabi", "arm-linux-androideabi")
                    "arm64-v8a" -> "arm64" to listOf("aarch64-linux-android")
                    "x86" -> "386" to listOf("i686-linux-android")
                    "x86_64" -> "amd64" to listOf("x86_64-linux-android")
                    else -> throw GradleException("Unsupported ABI '$abi'; use arm64-v8a, armeabi-v7a, x86 or x86_64.")
                }
            val prefix = compilers.first { toolchain.resolve("$it${apiLevel.get()}-clang").isFile }
            val env =
                mutableMapOf(
                    "GOOS" to "android",
                    "GOARCH" to goarch,
                    "CGO_ENABLED" to "1",
                    "CC" to toolchain.resolve("$prefix${apiLevel.get()}-clang").absolutePath,
                    // lld and both hash styles are what RoundSync ships; some devices/toolchains
                    // otherwise link against a hash style the platform loader will not accept.
                    "CGO_LDFLAGS" to "-fuse-ld=lld -Wl,--hash-style=both",
                )
            if (goarch == "arm") env["GOARM"] = "7"
            return env
        }

        private fun hostTag(): String {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            val archTag =
                if (os.contains("mac")) {
                    "x86_64"
                } else if (arch.contains("aarch64")) {
                    "aarch64"
                } else {
                    "x86_64"
                }
            return when {
                os.contains("win") -> "windows-x86_64"
                os.contains("mac") -> "darwin-$archTag"
                else -> "linux-$archTag"
            }
        }

        private fun runCommand(
            vararg command: String,
            workDir: File,
            environment: Map<String, String> = emptyMap(),
        ) {
            logger.lifecycle("rclone build: ${command.joinToString(" ")}")
            exec
                .exec {
                    commandLine(command.toList())
                    workingDir(workDir)
                    environment.forEach { (key, value) -> environment(key, value) }
                }.assertNormalExitValue()
        }
    }

/** The Android SDK location, from `local.properties` or the environment, as AGP resolves it. */
fun androidSdkDirectory(): File {
    rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
        val properties = Properties()
        file.inputStream().use(properties::load)
        properties.getProperty("sdk.dir")?.let { return File(it) }
    }
    (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))
        ?.takeIf { it.isNotBlank() }
        ?.let { return File(it) }
    throw GradleException("Android SDK not found; set sdk.dir in local.properties or ANDROID_HOME.")
}

val rcloneJniLibsDir = layout.buildDirectory.dir("generated/rclone/jniLibs")

val buildRcloneAndroid =
    tasks.register<BuildRcloneAndroidTask>("buildRcloneAndroid") {
        group = "build"
        description = "Cross-compiles the bundled rclone for each Android ABI into jniLibs."
        enabled = !rcloneSkip
        version.set(rcloneVersion)
        ndkVersion.set(rcloneNdkVersion)
        apiLevel.set(rcloneAndroidApi)
        abis.set(rcloneAbis)
        // Read when the task runs, so a build that never compiles the engine needs no SDK lookup here.
        sdkDir.set(providers.provider { androidSdkDirectory().absolutePath })
        goVersion.set(
            providers
                .exec { commandLine("go", "env", "GOVERSION") }
                .standardOutput.asText
                .map { it.trim() },
        )
        moduleDir.set(layout.buildDirectory.dir("rclone/module"))
        outputDir.set(rcloneJniLibsDir)
    }

val npnVersion = NpnVersion.of(project)

// Play's package name (dev.npn was taken there). The namespace and Kotlin packages stay dev.npn.
val npnApplicationId = "io.github.jeroenev.NoPapersNeeded"

// Release signing comes from the environment so the key never touches the repo: the release
// workflow decodes it from secrets, and a local release build can export the same variables.
// Without them assembleRelease still works but produces androidApp-release-unsigned.apk.
val releaseKeystore = providers.environmentVariable("NPN_KEYSTORE_FILE").orNull

android {
    namespace = "dev.npn"

    // compileSdk, minSdk, targetSdk and the test runner come from npn.toolchain.
    defaultConfig {
        applicationId = npnApplicationId
        versionCode = npnVersion.code
        versionName = npnVersion.name

        // Package only the ABIs the bundled engine was built for, so no device gets an APK whose
        // `librclone.so` is missing.
        if (!rcloneSkip && rcloneAbis.isNotEmpty()) {
            ndk {
                abiFilters.addAll(rcloneAbis)
            }
        }
    }
    // A skipped engine build leaves the directory an earlier build filled; packaging it anyway would
    // ship a stale engine, and with the ABI filter lifted, for some ABIs only.
    if (!rcloneSkip) {
        sourceSets.getByName("main") {
            jniLibs.directories.add(rcloneJniLibsDir.get().asFile.absolutePath)
        }
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
        // The bundled rclone is executed from nativeLibraryDir, which only holds a library when the
        // installer extracts it: with minSdk 30 AGP otherwise keeps every `.so` stored inside the APK
        // (`extractNativeLibs="false"`), and the engine would be missing on every phone. Extracted
        // libraries are also stored compressed, which keeps the ~90 MB engine from bloating the APK.
        jniLibs {
            useLegacyPackaging = true
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
            // Its own package (io.github.jeroenev.NoPapersNeeded.debug), so a debug build installs next to the release build
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

// The merge is what copies the generated .so into the APK; make it wait for the compile, without
// dragging the Go/NDK toolchain into ordinary compile/test tasks.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildRcloneAndroid)
}

// `mise baseline-profile` regenerates the committed profile; builds only package it.
baselineProfile {
    mergeIntoMain = true
}

// The baseline profile plugin adds release-like build types to profile and benchmark. Give them
// their own package, like debug, so they install next to the release build instead of colliding
// with its signature, and propose their own library folder.
androidComponents {
    onVariants(selector().withBuildType("nonMinifiedRelease")) { it.applicationId.set("$npnApplicationId.benchmark") }
    onVariants(selector().withBuildType("benchmarkRelease")) { it.applicationId.set("$npnApplicationId.benchmark") }
}
