import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView

/**
 * Copies a directory tree as `cp -a` would: every file keeps its POSIX mode and a symbolic link stays
 * a link. A jpackage app image has executables outside `bin/` (the bundled JDK's `jspawnhelper`,
 * which starts every subprocess), so a copy that drops modes makes an image that cannot run them.
 */
object TreeCopy {
    fun keepingModes(
        source: File,
        target: File,
    ) {
        val from = source.toPath()
        val to = target.toPath()

        fun copyOf(path: Path): Path = to.resolve(from.relativize(path).toString())

        Files.walkFileTree(
            from,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.createDirectories(copyOf(dir))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.copy(
                        file,
                        copyOf(file),
                        StandardCopyOption.COPY_ATTRIBUTES,
                        StandardCopyOption.REPLACE_EXISTING,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                    return FileVisitResult.CONTINUE
                }

                // Last, so a directory without write permission is filled before it gets its mode.
                override fun postVisitDirectory(
                    dir: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    if (exc != null) throw exc
                    val mode = Files.getFileAttributeView(dir, PosixFileAttributeView::class.java)
                    val copy = Files.getFileAttributeView(copyOf(dir), PosixFileAttributeView::class.java)
                    if (mode != null && copy != null) copy.setPermissions(mode.readAttributes().permissions())
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
