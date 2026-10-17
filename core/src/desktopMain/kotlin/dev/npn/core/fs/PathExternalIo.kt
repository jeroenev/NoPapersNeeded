package dev.npn.core.fs

import java.io.InputStream
import java.nio.file.Files

/** Desktop external files are plain paths. */
object PathExternalIo : ExternalIo {
    override fun openInput(source: ExternalSource): InputStream = Files.newInputStream(source.path)
}
