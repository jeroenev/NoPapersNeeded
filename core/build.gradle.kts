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
    listOf(
        "npn.sim.seed",
        "npn.sim.seeds",
        "npn.sim.firstSeed",
        "npn.sim.steps",
        "npn.sim.traceDir",
        "npn.sim.maxReported",
        "npn.sim.bisync",
    )
tasks.withType<Test>().configureEach {
    simulationProperties.forEach { key ->
        providers
            .systemProperty(key)
            .orElse(providers.gradleProperty(key))
            .orNull
            ?.let { systemProperty(key, it) }
    }
}

// The simulation's seeds are dealt over eight test classes (LibrarySimulationTest1..8) so a long
// run spreads over this many test JVMs, each running its seeds one after the other: seeds sharing
// a JVM slow each other down far more than they share the machine (docs/SIMULATION.md). Gradle
// also caps the forks at --max-workers.
tasks.withType<Test>().matching { it.name == "desktopTest" }.configureEach {
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 8)
}

// Mutation testing over the desktop target (docs/MUTATION_TESTING.md): on demand only, locally or
// through mutation.yml, never in `check`. -Pnpn.mutation.packages=a.b,c.d picks other packages (each with
// its subpackages), -Pnpn.mutation.threads the number of test processes.
val pitest =
    configurations.create("pitest") {
        isCanBeConsumed = false
    }
dependencies {
    pitest(libs.pitest.command.line)
}
tasks.register<MutationTestTask>("mutationTest") {
    group = "verification"
    description = "Mutation-tests core's index and app packages against the desktop tests (PIT)."
    val desktopTest = tasks.named<Test>("desktopTest")
    val desktopMain = kotlin.jvm("desktop").compilations.getByName("main")
    classpath = pitest
    javaLauncher.set(desktopTest.flatMap { it.javaLauncher })
    workingDir = projectDir
    testClasspath.from(desktopTest.map { it.classpath })
    mutableCodePaths.from(desktopMain.output.classesDirs)
    sourceDirectories.from(desktopMain.allKotlinSourceSets.map { it.kotlin.srcDirs })
    targetPackages.set(
        providers
            .gradleProperty("npn.mutation.packages")
            .map { list -> list.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
            .orElse(listOf("dev.npn.core.index", "dev.npn.core.app")),
    )
    targetTests.set(listOf("dev.npn.core.*"))
    excludedTestClasses.set(
        listOf(
            // Calls a paid AI endpoint when NPN_LIVE_AI is set, and is skipped otherwise.
            "dev.npn.core.ai.LiveEndpointProbeTest",
            // The library simulation runs its steps concurrently, so whether it kills a mutant can
            // change from run to run, and each run takes seconds against every mutant it covers.
            "dev.npn.core.sim.*",
        ),
    )
    threads.set(
        providers
            .gradleProperty("npn.mutation.threads")
            .map { it.toInt() }
            .orElse((Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)),
    )
    minionJvmArgs.set(listOf("-Xmx768m", "-Djava.awt.headless=true"))
    reportDirectory.set(layout.buildDirectory.dir("reports/pitest"))
}

// On a fresh Windows machine PDFBox's first font lookup scans every system font to build its
// cache, which can outlast runTest's one-minute default in whichever test gets there first.
if (System.getProperty("os.name").startsWith("Windows")) {
    tasks.withType<Test>().configureEach {
        systemProperty("kotlinx.coroutines.test.default_timeout", "5m")
    }
}
