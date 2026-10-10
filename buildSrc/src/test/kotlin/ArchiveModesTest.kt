import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/** The Linux tarball's guard: Flathub unpacks it as stored, so an executable must stay one. */
class ArchiveModesTest {
    private val root: File = createTempDirectory("archive-modes").toFile()
    private val stage = root.resolve("bundle")
    private val project = ProjectBuilder.builder().withProjectDir(root.resolve("project")).build()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun staged(
        path: String,
        mode: String,
    ) = stage.resolve(path).apply {
        parentFile.mkdirs()
        writeText(path)
        chmod(mode)
    }

    private fun File.chmod(mode: String) {
        Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString(mode))
    }

    /** The stage as `tar` stores it, with every mode as it is now. */
    private fun tarball(): File {
        val archive = root.resolve("bundle.tar.gz")
        val tar = ProcessBuilder("tar", "czf", archive.path, "-C", root.path, stage.name).inheritIO().start()
        check(tar.waitFor() == 0) { "tar failed" }
        return archive
    }

    private fun check(archive: File) =
        ArchiveModes.requireExecutablesKept(project.tarTree(archive), stage, stage.name, "app/bin/launcher")

    @Test
    fun anArchiveWithTheStagedModesPasses() {
        staged("app/bin/launcher", "rwxr-xr-x")
        staged("app/lib/runtime/lib/jspawnhelper", "rwxr-xr-x")
        staged("app/lib/libapplauncher.so", "rw-r--r--")

        check(tarball())
    }

    @Test
    fun anExecutableStoredWithoutItsExecuteBitFails() {
        staged("app/bin/launcher", "rwxr-xr-x")
        val helper = staged("app/lib/runtime/lib/jspawnhelper", "rw-r--r--")
        val archive = tarball()
        helper.chmod("rwxr-xr-x")

        val failure = assertFailsWith<GradleException> { check(archive) }

        assertContains(failure.message.orEmpty(), "app/lib/runtime/lib/jspawnhelper")
    }

    @Test
    fun aLauncherThatIsNotExecutableFails() {
        staged("app/bin/launcher", "rw-r--r--")

        assertFailsWith<GradleException> { check(tarball()) }
    }
}
