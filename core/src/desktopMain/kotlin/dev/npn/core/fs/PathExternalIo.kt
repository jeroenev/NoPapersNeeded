package dev.npn.core.fs

import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Desktop external files are plain paths. */
object PathExternalIo : ExternalIo {
    override fun openInput(source: ExternalSource): InputStream = Files.newInputStream(source.path)

    override fun openOutput(target: ExternalSource): OutputStream {
        target.path.parent?.let { Files.createDirectories(it) }
        return Files.newOutputStream(
            target.path,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }
}

actual fun defaultExternalIo(): ExternalIo = PathExternalIo

actual fun externalSourceOfPath(path: Path): ExternalSource = ExternalSource(path)
