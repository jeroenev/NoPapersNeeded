import dev.detekt.gradle.Detekt
import dev.detekt.gradle.DetektCreateBaselineTask

// Static analysis, configured by config/detekt/detekt.yml, in two runs. The root build applies this to
// every project, itself included:
// - `detekt`: source-only (no type resolution) over every module's source sets. It needs no
//   compile classpath, so it is fast and runs before the tests. Baseline: detekt-baseline.xml.
// - `detektTyped`: the per-compilation tasks below, with type resolution. Only these run the
//   rules that need the Kotlin Analysis API (LongParameterList, UnusedPrivate*,
//   SuspendFunSwallowedCancellation, ...) and the Compose ruleset; the source-only run skips them
//   silently. They read the compiled classpath, so CI runs them after compiling. Baseline:
//   detekt-baseline-typed.xml, which `detektTypedBaseline` rebuilds from all of a module's
//   compilations.
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
    // Grandfathers the source-only run's old findings so CI fails only on NEW issues. It is
    // expected to shrink as the flagged code is cleaned up. The typed tasks use their own file.
    baseline = file("detekt-baseline.xml").takeIf { it.exists() }
    // Every source set's Kotlin directory, read from disk so a new source set is never left out.
    source.setFrom(
        file("src")
            .listFiles()
            .orEmpty()
            .map { it.resolve("kotlin") }
            .filter { it.isDirectory },
    )
}

tasks.withType<Detekt>().configureEach {
    reports {
        html.required.set(true)
        sarif.required.set(false)
    }
}

val typedCompilations = typedDetektCompilations[path].orEmpty()
val typedTasks = typedCompilations.map { "detekt$it" }
val typedBaselineTasks = typedCompilations.map { "detektBaseline$it" }
val typedBaseline = file("detekt-baseline-typed.xml")
val typedBaselineParts = layout.buildDirectory.dir("detekt-baseline-typed")
// The Compose ruleset resolves types for part of its checks, so only the typed run loads it.
val typedPlugins = configurations.create("detektTypedPlugins") { isCanBeConsumed = false }
if (typedCompilations.isNotEmpty()) {
    dependencies.add(
        typedPlugins.name,
        rootProject.extensions
            .getByType<VersionCatalogsExtension>()
            .named(
                "libs",
            ).findLibrary("compose-rules-detekt")
            .get(),
    )
}

tasks.withType<Detekt>().configureEach {
    if (name in typedTasks) {
        // A compilation's sources include generated code (Compose resources, Room, BuildInfo).
        exclude { it.file.invariantSeparatorsPath.contains("/build/generated/") }
        pluginClasspath.from(typedPlugins)
        // Without this, the plugin falls back to the source-only detekt-baseline.xml.
        if (typedBaseline.exists()) baseline.set(typedBaseline)
    }
}
// Each compilation writes its own part; detektTypedBaseline merges them into one file.
tasks.withType<DetektCreateBaselineTask>().configureEach {
    if (name in typedBaselineTasks) {
        exclude { it.file.invariantSeparatorsPath.contains("/build/generated/") }
        pluginClasspath.from(typedPlugins)
        baseline.set(typedBaselineParts.map { it.file("$name.xml") })
    }
}

val detektTyped =
    tasks.register("detektTyped") {
        group = "verification"
        description = "Runs detekt with type resolution over the module's main and test compilations."
        dependsOn(typedTasks)
    }
tasks.matching { it.name == "check" }.configureEach { dependsOn(detektTyped) }

tasks.register("detektTypedBaseline") {
    group = "verification"
    description = "Rebuilds detekt-baseline-typed.xml from every typed compilation's findings."
    dependsOn(typedBaselineTasks)
    val parts = typedBaselineTasks.map { typedBaselineParts.get().file("$it.xml").asFile }
    val target = typedBaseline
    onlyIf { parts.isNotEmpty() }
    doLast {
        val ids =
            parts
                .filter { it.exists() }
                .flatMap { part -> Regex("<ID>(.*)</ID>").findAll(part.readText()).map { it.groupValues[1] } }
                .toSortedSet()
        if (ids.isEmpty()) {
            target.delete()
        } else {
            target.writeText(
                buildString {
                    appendLine("<?xml version=\"1.0\" ?>")
                    appendLine("<SmellBaseline>")
                    appendLine("  <ManuallySuppressedIssues/>")
                    appendLine("  <CurrentIssues>")
                    ids.forEach { appendLine("    <ID>$it</ID>") }
                    appendLine("  </CurrentIssues>")
                    appendLine("</SmellBaseline>")
                },
            )
        }
    }
}
