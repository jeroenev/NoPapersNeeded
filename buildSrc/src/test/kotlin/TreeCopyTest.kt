import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The AppImage's AppDir is a copy of the jpackage app image that must keep every file's mode. */
class TreeCopyTest {
    private val root: File = createTempDirectory("tree-copy").toFile()
    private val source = root.resolve("app")
    private val target = root.resolve("AppDir/usr")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun file(
        path: String,
        mode: String,
    ) = source.resolve(path).apply {
        parentFile.mkdirs()
        writeText(path)
        Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString(mode))
    }

    private fun modeOf(path: String) =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(target.resolve(path).toPath()))

    @Test
    fun anExecutableOutsideBinKeepsItsExecuteBit() {
        file("lib/runtime/lib/jspawnhelper", "rwxr-xr-x")

        TreeCopy.keepingModes(source, target)

        assertEquals("rwxr-xr-x", modeOf("lib/runtime/lib/jspawnhelper"))
    }

    @Test
    fun everyFileKeepsTheModeItHad() {
        file("bin/launcher", "rwxr-xr-x")
        file("lib/libapplauncher.so", "rw-r--r--")
        file("lib/runtime/legal/java.base/LICENSE", "r--r--r--")
        file("lib/app/app.jar", "rw-rw-r--")

        TreeCopy.keepingModes(source, target)

        assertEquals("rwxr-xr-x", modeOf("bin/launcher"))
        assertEquals("rw-r--r--", modeOf("lib/libapplauncher.so"))
        assertEquals("r--r--r--", modeOf("lib/runtime/legal/java.base/LICENSE"))
        assertEquals("rw-rw-r--", modeOf("lib/app/app.jar"))
        assertEquals("lib/app/app.jar", target.resolve("lib/app/app.jar").readText())
    }

    @Test
    fun aLinkStaysALink() {
        file("lib/runtime/legal/java.base/LICENSE", "r--r--r--")
        val link = source.resolve("lib/runtime/legal/java.sql/LICENSE").toPath()
        Files.createDirectories(link.parent)
        Files.createSymbolicLink(link, Path.of("../java.base/LICENSE"))

        TreeCopy.keepingModes(source, target)

        val copy = target.resolve("lib/runtime/legal/java.sql/LICENSE").toPath()
        assertTrue(Files.isSymbolicLink(copy))
        assertEquals("lib/runtime/legal/java.base/LICENSE", copy.toFile().readText())
    }
}
