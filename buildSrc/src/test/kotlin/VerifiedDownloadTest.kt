import org.gradle.api.GradleException
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class VerifiedDownloadTest {
    private val root: File = createTempDirectory("verified-download").toFile()
    private val bytes = "the tool".encodeToByteArray()
    private val source = root.resolve("served/tool").apply { parentFile.mkdirs() }.apply { writeBytes(bytes) }
    private val url = source.toURI().toString()
    private val sha256 = VerifiedDownload.sha256Of(source)
    private val target = root.resolve("build/tool")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun aMatchingDownloadReplacesTheTargetAndLeavesNoPart() {
        VerifiedDownload.fetch(url, sha256, target)

        assertContentEquals(bytes, target.readBytes())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun aMismatchFailsAndKeepsNothing() {
        assertFailsWith<GradleException> { VerifiedDownload.fetch(url, "0".repeat(64), target) }

        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun aCorruptOrPartialEarlierCopyIsFetchedAgain() {
        target.parentFile.mkdirs()
        target.writeBytes(bytes.copyOf(3))
        File(target.path + ".part").writeText("interrupted")

        VerifiedDownload.fetch(url, sha256, target)

        assertContentEquals(bytes, target.readBytes())
    }

    @Test
    fun aVerifiedCopyIsReusedWithoutDownloading() {
        VerifiedDownload.fetch(url, sha256, target)
        source.delete()
        val logged = mutableListOf<String>()

        VerifiedDownload.fetch(url, sha256, target) { logged += it }

        assertEquals(emptyList(), logged)
        assertContentEquals(bytes, target.readBytes())
    }
}
