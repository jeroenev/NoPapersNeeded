package dev.npn.core.fs

import java.nio.file.Path

/** Desktop external location: a plain filesystem path. */
actual class ExternalSource(
    val path: Path,
)

actual fun externalSourceOfPath(path: Path): ExternalSource = ExternalSource(path)
