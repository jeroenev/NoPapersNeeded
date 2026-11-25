import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class GenerateBuildInfoTaskTest {
    private val root: File = createTempDirectory("build-info").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun generate(
        clientId: String? = null,
        clientSecret: String? = null,
    ): String {
        val project = ProjectBuilder.builder().withProjectDir(root).build()
        val task =
            project.tasks
                .register("generateBuildInfo", GenerateBuildInfoTask::class.java) {
                    version.set("1.2.3")
                    clientId?.let { driveClientId.set(it) }
                    clientSecret?.let { driveClientSecret.set(it) }
                    outDir.set(root.resolve("out"))
                }.get()
        task.generate()
        return root.resolve("out/dev/npn/app/BuildInfo.kt").readText()
    }

    @Test
    fun aBuildWithoutADriveAppLeavesItBlank() {
        val source = generate()
        assertContains(source, """const val VERSION = "1.2.3"""")
        assertContains(source, """const val DRIVE_CLIENT_ID = """"")
        assertContains(source, """const val DRIVE_CLIENT_SECRET = """"")
    }

    @Test
    fun aConfiguredDriveAppIsWrittenTrimmed() {
        val source = generate(" 123-abc.apps.googleusercontent.com\n", "test_secret-1")
        assertContains(source, """const val DRIVE_CLIENT_ID = "123-abc.apps.googleusercontent.com"""")
        assertContains(source, """const val DRIVE_CLIENT_SECRET = "test_secret-1"""")
    }

    @Test
    fun aClientIdWithoutItsSecretIsRefused() {
        assertFailsWith<GradleException> { generate(clientId = "123-abc.apps.googleusercontent.com") }
    }

    @Test
    fun aValueThatWouldBreakOutOfTheLiteralIsRefused() {
        assertFailsWith<GradleException> { generate("id", "a\" + evil() + \"") }
    }
}
