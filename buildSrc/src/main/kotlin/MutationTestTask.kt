import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Runs PIT (pitest) from its command-line jar over one compilation's classes and the tests that
 * cover them. The pitest Gradle plugin assumes a plain JVM project, so a multiplatform module
 * wires the compiled classes, the test runtime classpath and the source directories here itself.
 *
 * The defaults are the Kotlin-aware part: no mutants in compiler-generated members and classes,
 * and none that only remove Kotlin's own null checks. `classpath` is PIT's, not the code's.
 */
abstract class MutationTestTask : JavaExec() {
    /** Packages to mutate, with their subpackages (`dev.npn.core.index`). */
    @get:Input
    abstract val targetPackages: ListProperty<String>

    /** PIT globs over the test classes that may run against a mutant. */
    @get:Input
    abstract val targetTests: ListProperty<String>

    /** PIT globs over test classes kept out: slow, live or non-deterministic ones. */
    @get:Input
    abstract val excludedTestClasses: ListProperty<String>

    @get:Input
    abstract val excludedClasses: ListProperty<String>

    @get:Input
    abstract val excludedMethods: ListProperty<String>

    @get:Input
    abstract val avoidCallsTo: ListProperty<String>

    @get:Input
    abstract val mutators: ListProperty<String>

    @get:Input
    abstract val threads: Property<Int>

    /** JVM arguments of the minion processes that run the tests. */
    @get:Input
    abstract val minionJvmArgs: ListProperty<String>

    /** Everything the tests run with: test classes, the classes under mutation, dependencies. */
    @get:Classpath
    abstract val testClasspath: ConfigurableFileCollection

    /** The class directories PIT may mutate; the rest of [testClasspath] is left alone. */
    @get:Classpath
    abstract val mutableCodePaths: ConfigurableFileCollection

    /** Where the report finds the source of a mutated class, by package and file name. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectories: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val reportDirectory: DirectoryProperty

    init {
        mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")
        excludedClasses.convention(KotlinDefaults.EXCLUDED_CLASSES)
        excludedMethods.convention(KotlinDefaults.EXCLUDED_METHODS)
        avoidCallsTo.convention(KotlinDefaults.AVOID_CALLS_TO)
        mutators.convention(listOf("DEFAULTS"))
        excludedTestClasses.convention(emptyList())
        minionJvmArgs.convention(emptyList())
    }

    @TaskAction
    override fun exec() {
        // The test classpath runs far past a command line's length, so PIT reads it from a file.
        val classPathFile = File(temporaryDir, "classpath.txt")
        classPathFile.writeText(testClasspath.files.joinToString("\n", postfix = "\n") { it.absolutePath })
        val report = reportDirectory.get().asFile
        report.deleteRecursively()
        setArgs(
            PitestArguments(
                targetPackages = targetPackages.get(),
                targetTests = targetTests.get(),
                excludedTestClasses = excludedTestClasses.get(),
                excludedClasses = excludedClasses.get(),
                excludedMethods = excludedMethods.get(),
                avoidCallsTo = avoidCallsTo.get(),
                mutators = mutators.get(),
                threads = threads.get(),
                // A mutant can crash a minion in native code (the bundled SQLite), which PIT counts
                // as a kill; its crash log belongs with the task's files, not in the module.
                minionJvmArgs =
                    minionJvmArgs.get() +
                        listOf("-XX:ErrorFile=${temporaryDir.absolutePath}/hs_err_pid%p.log", "-XX:-CreateCoredumpFile"),
            ).toCommandLine(
                reportDir = report,
                classPathFile = classPathFile,
                mutableCodePaths = mutableCodePaths.files.filter { it.exists() },
                sourceDirs = sourceDirectories.files.filter { it.exists() },
            ),
        )
        super.exec()
    }

    /** What a Kotlin compilation adds that no test should have to pin down. */
    object KotlinDefaults {
        val EXCLUDED_CLASSES =
            listOf(
                // kotlinx.serialization's generated serializers.
                "*\$\$serializer",
                // Room's generated database and DAO implementations.
                "*_Impl",
                "*_Impl\$*",
                // The lookup tables behind a `when` over an enum, and an enum's `entries`.
                "*\$WhenMappings",
                "*\$EntriesMappings",
            )

        // A data class's generated members. The target packages write none of these by hand; one
        // written later would be skipped too.
        val EXCLUDED_METHODS = listOf("equals", "hashCode", "toString", "component*", "copy", "copy\$default")

        // Kotlin's parameter and platform-type null checks (Intrinsics) and the coroutine state
        // machine's result check: removing them changes nothing a correct caller can see.
        val AVOID_CALLS_TO = listOf("kotlin.jvm.internal", "kotlin.ResultKt")
    }
}

/** PIT's command line for one run, apart from the paths only known when the task runs. */
data class PitestArguments(
    val targetPackages: List<String>,
    val targetTests: List<String>,
    val excludedTestClasses: List<String>,
    val excludedClasses: List<String>,
    val excludedMethods: List<String>,
    val avoidCallsTo: List<String>,
    val mutators: List<String>,
    val threads: Int,
    val minionJvmArgs: List<String>,
) {
    init {
        require(targetPackages.isNotEmpty()) { "No package to mutate" }
        targetPackages.forEach { name ->
            require(PACKAGE.matches(name)) { "Not a package name: '$name'" }
        }
        require(targetTests.isNotEmpty()) { "No test class to run" }
        require(threads >= 1) { "PIT needs at least one thread, not $threads" }
    }

    /** A package and its subpackages, as PIT globs over class names. */
    val targetClasses: List<String> get() = targetPackages.map { "$it.*" }

    fun toCommandLine(
        reportDir: File,
        classPathFile: File,
        mutableCodePaths: List<File>,
        sourceDirs: List<File>,
    ): List<String> =
        buildList {
            option("reportDir", reportDir.absolutePath)
            option("targetClasses", targetClasses)
            option("targetTests", targetTests)
            option("excludedTestClasses", excludedTestClasses)
            option("excludedClasses", excludedClasses)
            option("excludedMethods", excludedMethods)
            option("avoidCallsTo", avoidCallsTo)
            option("mutators", mutators)
            option("classPathFile", classPathFile.absolutePath)
            option("mutableCodePaths", mutableCodePaths.map { it.absolutePath })
            option("sourceDirs", sourceDirs.map { it.absolutePath })
            option("threads", threads.toString())
            option("jvmArgs", minionJvmArgs)
            option("outputFormats", listOf("HTML", "XML"))
            option("timestampedReports", "false")
            // A spinner writes a character per tick, which buries a CI log or a redirected run.
            option("verbosity", "NO_SPINNER")
        }

    private fun MutableList<String>.option(
        name: String,
        values: List<String>,
    ) {
        if (values.isNotEmpty()) option(name, values.joinToString(","))
    }

    private fun MutableList<String>.option(
        name: String,
        value: String,
    ) {
        add("--$name=$value")
    }

    private companion object {
        val PACKAGE = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
    }
}
