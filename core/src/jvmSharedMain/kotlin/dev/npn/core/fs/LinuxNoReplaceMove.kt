package dev.npn.core.fs

import com.sun.jna.Function
import com.sun.jna.LastErrorException
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Path

/**
 * Exclusive, complete-file publication on Linux and Android, including shared-storage FUSE.
 * Files.move performs a stat followed by a replacing rename; only RENAME_NOREPLACE closes that
 * race with other processes. Use syscall because older Android libc versions do not export
 * renameat2. JNA's Android AAR supplies the native bridge for each supported Android ABI.
 */
internal object LinuxNoReplaceMove {
    private const val AT_FDCWD = -100
    private const val RENAME_NOREPLACE = 1
    private const val EEXIST = 17

    private val syscall by lazy {
        NativeLibrary.getInstance(Platform.C_LIBRARY_NAME).getFunction("syscall", Function.THROW_LAST_ERROR)
    }

    // Linux UAPI: arch/{arm,x86}/ syscall tables and include/uapi/asm-generic/unistd.h.
    // Unknown ABIs fail closed instead of risking a call to a different syscall.
    @Suppress("MagicNumber")
    private fun renameNumber(): Long {
        if (!Platform.isLinux() && !Platform.isAndroid()) {
            throw VerifiedPublicationUnavailableException("Exclusive rename requires Linux or Android")
        }
        return when (Platform.ARCH) {
            "aarch64", "riscv64" -> 276L

            // Android's os.arch can retain the kernel's ARM name rather than the APK ABI name.
            "arm", "armv7l", "armv8l", "armel" -> 382L

            "x86" -> 353L

            "x86-64" -> 316L

            else -> throw VerifiedPublicationUnavailableException("Unsupported rename ABI: ${Platform.ARCH}")
        }
    }

    fun move(
        from: Path,
        to: Path,
    ) {
        val number = renameNumber()
        try {
            syscall.invoke(
                NativeLong::class.java,
                arrayOf(NativeLong(number), AT_FDCWD, from.toString(), AT_FDCWD, to.toString(), RENAME_NOREPLACE),
            )
        } catch (e: LastErrorException) {
            // Do not degrade to a replacing rename, including on EXDEV or unsupported FUSE.
            val failure =
                if (e.errorCode == EEXIST) {
                    FileAlreadyExistsException(to.toString())
                } else {
                    FileSystemException(from.toString(), to.toString(), "Exclusive rename failed: errno ${e.errorCode}")
                }
            throw failure.apply { initCause(e) }
        } catch (e: LinkageError) {
            throw VerifiedPublicationUnavailableException("Exclusive rename native bridge unavailable", e)
        }
    }
}
