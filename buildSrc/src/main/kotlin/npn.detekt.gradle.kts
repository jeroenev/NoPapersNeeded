import dev.detekt.gradle.Detekt

// Static analysis, configured by config/detekt/detekt.yml, in two runs. The root build applies this to
// every project, itself included:
// - `detekt`: source-only (no type resolution) over every module's source sets. It needs no
//   compile classpath, so it is fast and runs before the tests.
// - `detektTyped`: the per-compilation tasks below, with type resolution. Only these run the
//   rules that need the Kotlin Analysis API (LongParameterList, UnusedPrivate*,
//   SuspendFunSwallowedCancellation, ...) and the Compose ruleset; the source-only run skips them
//   silently. They read the compiled classpath, so CI runs them after compiling.
// There are no baselines: a finding is fixed, or a false positive suppressed with a reason.
// Device tests, release variants and :baselineprofile are left to the source-only run: typing
// them would mean compiling builds CI otherwise never needs.
plugins {
    id("dev.detekt")
}

val typedDetektCompilations =
    mapOf(
        ":core" to listOf("MainDesktop", "MainAndroid", "TestDesktop", "HostTestAndroid"),
        // All commonMain: the desktop compilation sees every line of it.
        ":core-testing" to listOf("MainDesktop"),
        ":composeApp" to listOf("MainDesktop", "MainAndroid", "TestDesktop", "HostTestAndroid"),
        ":androidApp" to listOf("Debug"),
        ":desktopApp" to listOf("Main"),
    )

detekt {
    parallel = true
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
    // Every source set's Kotlin directory, read from disk so a new source set is never left out.
    source.setFrom(
        file("src")
            .listFiles()
            .orEmpty()
            .map { it.resolve("kotlin") }
            .filter { it.isDirectory },
    )
}

val typedCompilations = typedDetektCompilations[path].orEmpty()
val typedTasks = typedCompilations.map { "detekt$it" }
// The Compose ruleset resolves types for part of its checks, so only the typed run loads it.
val typedPlugins = configurations.create("detektTypedPlugins") { isCanBeConsumed = false }
if (typedCompilations.isNotEmpty()) {
    dependencies.add(
        typedPlugins.name,
        // The root build applies this plugin before a subproject's own script, when only the root
        // project has the version catalog extension yet.
        rootProject.extensions
            .getByType<VersionCatalogsExtension>()
            .named("libs")
            .findLibrary("compose-rules-detekt")
            .get(),
    )
}

tasks.withType<Detekt>().configureEach {
    // Nothing reads a report file; findings are read from the console.
    reports {
        checkstyle.required.set(false)
        html.required.set(false)
        markdown.required.set(false)
        sarif.required.set(false)
    }
    if (name in typedTasks) {
        // A compilation's sources include generated code (Compose resources, Room, BuildInfo).
        exclude { it.file.invariantSeparatorsPath.contains("/build/generated/") }
        pluginClasspath.from(typedPlugins)
    }
}

val detektTyped =
    tasks.register("detektTyped") {
        group = "verification"
        description = "Runs detekt with type resolution over the module's main and test compilations."
        dependsOn(typedTasks)
    }
tasks.matching { it.name == "check" }.configureEach { dependsOn(detektTyped) }
