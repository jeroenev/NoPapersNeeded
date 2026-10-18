plugins {
    id("npn.kmp-module")
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
    alias(libs.plugins.room3)
}

kotlin {
    // `jvmShared` is what Android and desktop have in common beyond commonMain: java.nio and
    // java.security. The direct-filesystem library implementation lives there so Android's
    // non-SAF storage mode is the very same class the desktop app runs, not a port of it.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

    android {
        namespace = "dev.npn.core"

        // Runs commonTest against the Android target on a host JVM, and gives androidMain code
        // (SAF, MLKit wrappers) a test source set it never had. Without it, everything in
        // commonTest is only ever verified through the `desktop` target.
        withHostTest {}
        withDeviceTest {}
    }

    sourceSets {
        // The hierarchy template matches the `desktop` jvm target but not the AGP multiplatform
        // library target, so the Android edge into `jvmShared` is drawn by hand.
        val jvmSharedMain = getByName("jvmSharedMain")
        jvmSharedMain.dependencies {
            compileOnly(libs.jna)
        }
        androidMain.get().dependsOn(jvmSharedMain)

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.room3.runtime)
            implementation(libs.sqlite.bundled)
            implementation(libs.koog.agents.core)
            implementation(libs.koog.agents.features.memory)
            implementation(libs.koog.agents.tools)
            implementation(libs.koog.prompt.executor.model)
            implementation(libs.koog.prompt.executor.anthropic)
            implementation(libs.koog.prompt.executor.deepseek)
            implementation(libs.koog.prompt.executor.google)
            implementation(libs.koog.prompt.executor.mistralai)
            implementation(libs.koog.prompt.executor.ollama)
            implementation(libs.koog.prompt.executor.openai)
            implementation(libs.koog.prompt.executor.openrouter)
            implementation(libs.koog.http.client.ktor)
        }
        androidMain.dependencies {
            implementation(project.dependencies.variantOf(libs.jna) { artifactType("aar") })
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.documentfile)
            implementation(libs.pdfbox.android)
            implementation(libs.mlkit.text.recognition)
            implementation(libs.ktor.client.okhttp)
        }
        getByName("desktopMain") {
            dependencies {
                implementation(libs.jna)
                implementation(libs.pdfbox)
                implementation(libs.pdfbox.xmpbox)
                implementation(libs.ktor.client.cio)
            }
        }
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.room3.compiler)
    add("kspDesktop", libs.room3.compiler)
}

// Lint's model lists every component's source directories, KSP's output among them, but AGP does
// not order the test components' lint tasks after KSP; Gradle then refuses a build that runs both,
// such as a local `compileDeviceTests :androidApp:lintDebug` (CI runs them separately).
tasks.matching { it.name.endsWith("LintModel") || it.name.startsWith("lintAnalyze") }.configureEach {
    mustRunAfter(tasks.matching { it.name.startsWith("ksp") })
}

// The library simulation (docs/SIMULATION.md) reads how many seeds and steps to run, or the one seed
// to replay, from these: `./gradlew :core:desktopTest -Dnpn.sim.seed=1234` (or `-Pnpn.sim.seed=`)
// hands them to the test JVM, and a changed value reruns the task rather than reusing its result.
val simulationProperties =
    listOf("npn.sim.seed", "npn.sim.seeds", "npn.sim.firstSeed", "npn.sim.steps", "npn.sim.traceDir")
tasks.withType<Test>().configureEach {
    simulationProperties.forEach { key ->
        providers
            .systemProperty(key)
            .orElse(providers.gradleProperty(key))
            .orNull
            ?.let { systemProperty(key, it) }
    }
}

// On a fresh Windows machine PDFBox's first font lookup scans every system font to build its
// cache, which can outlast runTest's one-minute default in whichever test gets there first.
if (System.getProperty("os.name").startsWith("Windows")) {
    tasks.withType<Test>().configureEach {
        systemProperty("kotlinx.coroutines.test.default_timeout", "5m")
    }
}
