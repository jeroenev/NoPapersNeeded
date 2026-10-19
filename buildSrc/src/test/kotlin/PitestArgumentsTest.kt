import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PitestArgumentsTest {
    private fun arguments(
        packages: List<String> = listOf("dev.npn.core.index", "dev.npn.core.app"),
        threads: Int = 2,
        excludedTests: List<String> = emptyList(),
    ) = PitestArguments(
        targetPackages = packages,
        targetTests = listOf("dev.npn.core.*"),
        excludedTestClasses = excludedTests,
        excludedClasses = MutationTestTask.KotlinDefaults.EXCLUDED_CLASSES,
        excludedMethods = MutationTestTask.KotlinDefaults.EXCLUDED_METHODS,
        avoidCallsTo = MutationTestTask.KotlinDefaults.AVOID_CALLS_TO,
        mutators = listOf("DEFAULTS"),
        threads = threads,
        minionJvmArgs = listOf("-Xmx768m"),
    )

    private fun commandLine(arguments: PitestArguments) =
        arguments.toCommandLine(
            reportDir = File("/r"),
            classPathFile = File("/cp.txt"),
            mutableCodePaths = listOf(File("/main")),
            sourceDirs = listOf(File("/a"), File("/b")),
        )

    @Test
    fun eachPackageIsMutatedWithItsSubpackages() {
        val line = commandLine(arguments())
        assertTrue("--targetClasses=dev.npn.core.index.*,dev.npn.core.app.*" in line, line.toString())
        assertTrue("--sourceDirs=/a,/b" in line, line.toString())
        assertTrue("--classPathFile=/cp.txt" in line, line.toString())
        assertTrue("--threads=2" in line, line.toString())
    }

    @Test
    fun kotlinNullChecksAndGeneratedMembersAreLeftAlone() {
        val line = commandLine(arguments())
        assertTrue(line.any { it.startsWith("--avoidCallsTo=") && "kotlin.jvm.internal" in it }, line.toString())
        assertTrue(line.any { it.startsWith("--excludedMethods=") && "component*" in it }, line.toString())
    }

    @Test
    fun anEmptyListLeavesItsOptionOut() {
        val line = commandLine(arguments(excludedTests = emptyList()))
        assertFalse(line.any { it.startsWith("--excludedTestClasses") }, line.toString())
        assertEquals(1, line.count { it.startsWith("--targetTests=") })
    }

    @Test
    fun refusesWhatWouldMutateNothingOrEverything() {
        listOf(
            emptyList(),
            listOf("dev.npn.core.*"),
            listOf("dev.npn..core"),
            listOf(""),
        ).forEach { packages ->
            assertFailsWith<IllegalArgumentException>(packages.toString()) { arguments(packages = packages) }
        }
        assertFailsWith<IllegalArgumentException> { arguments(threads = 0) }
    }
}
