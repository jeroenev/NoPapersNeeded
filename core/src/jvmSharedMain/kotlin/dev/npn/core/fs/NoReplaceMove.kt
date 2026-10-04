package dev.npn.core.fs

import com.sun.jna.Function
import com.sun.jna.LastErrorException
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import com.sun.jna.WString
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Path

/**
 * Exclusive, complete-file rename: installs [move]'s target only if nothing is there, as one
 * step the OS performs, and never replaces an entry another process created in the meantime.
 * Files.move with ATOMIC_MOVE replaces the target, and without it the JDK may stat the target first
 * and then rename over it, so neither closes the race with a sync client.
 *
 * Linux and Android (including shared-storage FUSE) use renameat2(RENAME_NOREPLACE). Windows uses
 * MoveFileExW without MOVEFILE_REPLACE_EXISTING, which fails on an existing target. Neither falls
 * back to copying across volumes. Any other platform, and a Linux filesystem that does not support
 * the flag, fails closed with [VerifiedPublicationUnavailableException].
 */
internal object NoReplaceMove {
    private const val AT_FDCWD = -100
    private const val RENAME_NOREPLACE = 1
    private const val EEXIST = 17

    // EINVAL: the filesystem rejects RENAME_NOREPLACE (FUSE without rename2, NFS); ENOSYS: no
    // renameat2 in the kernel; EOPNOTSUPP: the operation is not supported there.
    private val UNSUPPORTED_ERRNOS = setOf(22, 38, 95)

    private const val ERROR_FILE_EXISTS = 80
    private const val ERROR_ALREADY_EXISTS = 183

    // Use syscall because older Android libc versions do not export renameat2. JNA's Android AAR
    // supplies the native bridge for each supported Android ABI.
    private val syscall by lazy {
        NativeLibrary.getInstance(Platform.C_LIBRARY_NAME).getFunction("syscall", Function.THROW_LAST_ERROR)
    }

    private val moveFileEx by lazy {
        NativeLibrary.getInstance("kernel32").getFunction("MoveFileExW", Function.ALT_CONVENTION)
    }

    // Linux UAPI: arch/{arm,x86}/ syscall tables and include/uapi/asm-generic/unistd.h.
    // Unknown ABIs fail closed instead of risking a call to a different syscall.
    private fun renameNumber(): Long =
        when (Platform.ARCH) {
            "aarch64", "riscv64" -> 276L

            // Android's os.arch can retain the kernel's ARM name rather than the APK ABI name.
            "arm", "armv7l", "armv8l", "armel" -> 382L

            "x86" -> 353L

            "x86-64" -> 316L

            else -> throw VerifiedPublicationUnavailableException("Unsupported rename ABI: ${Platform.ARCH}")
        }

    fun move(
        from: Path,
        to: Path,
    ) {
        try {
            when {
                Platform.isLinux() || Platform.isAndroid() -> moveLinux(from, to)

                Platform.isWindows() -> moveWindows(from, to)

                else -> throw VerifiedPublicationUnavailableException(
                    "Exclusive rename requires Linux, Android or Windows",
                )
            }
        } catch (e: LinkageError) {
            throw VerifiedPublicationUnavailableException("Exclusive rename native bridge unavailable", e)
        }
    }

    private fun moveLinux(
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
            // Do not degrade to a replacing rename, including on EXDEV or unsupported FUSE. A
            // filesystem or kernel that does not know the flag says so the same way every time,
            // which is a capability, not a failed attempt.
            if (e.errorCode in UNSUPPORTED_ERRNOS) {
                throw VerifiedPublicationUnavailableException("Exclusive rename unsupported: errno ${e.errorCode}", e)
            }
            throw failure(from, to, e.errorCode == EEXIST, "errno ${e.errorCode}").apply { initCause(e) }
        }
    }

    private fun moveWindows(
        from: Path,
        to: Path,
    ) {
        // Flags 0: no MOVEFILE_REPLACE_EXISTING, and no MOVEFILE_COPY_ALLOWED, so a move across
        // volumes fails instead of degrading to copy-and-delete.
        val moved = moveFileEx.invokeInt(arrayOf(WString(win32Path(from)), WString(win32Path(to)), 0)) != 0
        if (moved) return
        val error = Native.getLastError()
        val exists = error == ERROR_FILE_EXISTS || error == ERROR_ALREADY_EXISTS
        throw failure(from, to, exists, "Win32 error $error")
    }

    // The \\?\ prefix lifts MAX_PATH, as the JDK does for its own Win32 calls.
    private fun win32Path(path: Path): String {
        val absolute = path.toAbsolutePath().toString()
        return when {
            absolute.startsWith("""\\?\""") -> absolute
            absolute.startsWith("""\\""") -> """\\?\UNC\""" + absolute.removePrefix("""\\""")
            else -> """\\?\$absolute"""
        }
    }

    private fun failure(
        from: Path,
        to: Path,
        exists: Boolean,
        detail: String,
    ): FileSystemException =
        if (exists) {
            FileAlreadyExistsException(to.toString())
        } else {
            FileSystemException(from.toString(), to.toString(), "Exclusive rename failed: $detail")
        }
}
