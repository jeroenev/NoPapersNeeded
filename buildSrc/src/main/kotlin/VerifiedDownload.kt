import org.gradle.api.GradleException
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Downloads a build runs or ships, pinned to a SHA-256. A release build runs next to signing
 * secrets, so nothing fetched may be used before its hash is checked.
 *
 * The bytes land in `<target>.part` and replace [target] only once they match, so an interrupted or
 * altered download is never mistaken for the file; an existing [target] is reused only while its
 * own hash still matches.
 */
object VerifiedDownload {
    fun fetch(
        url: String,
        sha256: String,
        target: File,
        log: (String) -> Unit = {},
    ): File {
        if (target.isFile && sha256Of(target) == sha256) return target
        log("Downloading $url")
        target.absoluteFile.parentFile.mkdirs()
        val partial = File(target.path + ".part")
        URI(url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
        val actual = sha256Of(partial)
        if (actual != sha256) {
            partial.delete()
            throw GradleException("$url has SHA-256 $actual, expected $sha256")
        }
        Files.move(
            partial.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        return target
    }

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream(), digest).use { it.copyTo(OutputStream.nullOutputStream()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
