import org.gradle.api.GradleException
import org.gradle.api.file.FileTree
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Holds an archive to the modes of the directory it was made from. Gradle's archive tasks store
 * fixed modes unless told otherwise, and an app unpacked from an archive that lost its execute bits
 * cannot start.
 */
object ArchiveModes {
    private const val OWNER_EXECUTE = 0b001_000_000

    /**
     * Fails unless [archive] stores every file of [source] that its owner can execute, under [into],
     * with the owner's execute bit, [launcher] (a path in [source]) among them.
     */
    fun requireExecutablesKept(
        archive: FileTree,
        source: File,
        into: String,
        launcher: String,
    ) {
        val stored = HashMap<String, Int>()
        archive.visit { if (!isDirectory) stored[relativePath.pathString] = permissions.toUnixNumeric() }
        val executables =
            source
                .walkTopDown()
                .filter { it.isFile && PosixFilePermission.OWNER_EXECUTE in Files.getPosixFilePermissions(it.toPath()) }
                .map { it.relativeTo(source).invariantSeparatorsPath }
                .toList()
        if (launcher !in executables) throw GradleException("$launcher in $source is not executable")
        val lost = executables.filter { (stored["$into/$it"] ?: 0) and OWNER_EXECUTE == 0 }
        if (lost.isNotEmpty()) {
            throw GradleException("The archive stores these executables without their execute bit: $lost")
        }
    }
}
