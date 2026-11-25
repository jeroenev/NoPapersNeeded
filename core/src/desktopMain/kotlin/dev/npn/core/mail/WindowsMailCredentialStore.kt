package dev.npn.core.mail

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mail passwords in the Windows Credential Manager (`CredWriteW`/`CredReadW`/`CredDeleteW` in
 * advapi32), through the JNA dependency libsecret already uses on Linux. Each password is a generic
 * credential of the signed-in Windows user, kept on this machine only (never roamed) and protected
 * by Windows for that user; it shows in Control Panel → Credential Manager → Windows Credentials as
 * `dev.npn.MailAccount:<account>`.
 *
 * There is no plain-text fallback: a host whose Credential Manager cannot be reached gets a typed
 * [MailSecretFailure]. The password is passed only to `CredWriteW`, its buffer is zeroed after,
 * and it is never logged.
 */
internal class WindowsMailCredentialStore(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MailCredentialStore {
    private val api: CredentialApi? by lazy {
        if (!isWindows()) null else runCatching { CredentialApi() }.getOrNull()
    }

    override val available: Boolean
        get() = api != null

    override suspend fun store(
        accountKey: String,
        password: String,
    ): MailSecretResult<Unit> =
        withContext(io) {
            val lib = api ?: return@withContext MailSecretResult.Failed(MailSecretFailure.UNAVAILABLE)
            val secret = password.toByteArray(Charsets.UTF_8)
            if (secret.size > MAX_BLOB_BYTES) return@withContext MailSecretResult.Failed(MailSecretFailure.STORE_FAILED)
            val blob = Memory(maxOf(secret.size, 1).toLong())
            try {
                blob.write(0, secret, 0, secret.size)
                val credential =
                    Credential().apply {
                        type = CRED_TYPE_GENERIC
                        targetName = WString(targetName(accountKey))
                        userName = WString(accountKey)
                        credentialBlobSize = secret.size
                        credentialBlob = blob
                        persist = CRED_PERSIST_LOCAL_MACHINE
                    }
                val written = lib.write.invokeInt(arrayOf<Any?>(credential, 0)) != 0
                if (written) MailSecretResult.Success(Unit) else MailSecretResult.Failed(MailSecretFailure.STORE_FAILED)
            } finally {
                blob.clear()
                secret.fill(0)
            }
        }

    override suspend fun load(accountKey: String): MailSecretResult<String?> =
        withContext(io) {
            val lib = api ?: return@withContext MailSecretResult.Failed(MailSecretFailure.UNAVAILABLE)
            val found = PointerByReference()
            val read =
                lib.read.invokeInt(arrayOf<Any?>(WString(targetName(accountKey)), CRED_TYPE_GENERIC, 0, found)) != 0
            if (!read) {
                return@withContext if (Native.getLastError() == ERROR_NOT_FOUND) {
                    MailSecretResult.Success(null)
                } else {
                    MailSecretResult.Failed(MailSecretFailure.UNAVAILABLE)
                }
            }
            val pointer = found.value ?: return@withContext MailSecretResult.Success(null)
            try {
                val credential = Credential(pointer).apply { read() }
                val size = credential.credentialBlobSize
                val bytes = credential.credentialBlob?.takeIf { size > 0 }?.getByteArray(0, size) ?: ByteArray(0)
                MailSecretResult.Success(String(bytes, Charsets.UTF_8).also { bytes.fill(0) })
            } finally {
                lib.free.invokeVoid(arrayOf<Any?>(pointer))
            }
        }

    override suspend fun clear(accountKey: String): MailSecretResult<Unit> =
        withContext(io) {
            val lib = api ?: return@withContext MailSecretResult.Failed(MailSecretFailure.UNAVAILABLE)
            val deleted =
                lib.delete.invokeInt(arrayOf<Any?>(WString(targetName(accountKey)), CRED_TYPE_GENERIC, 0)) != 0
            // Nothing to remove is what clearing asked for.
            if (deleted || Native.getLastError() == ERROR_NOT_FOUND) {
                MailSecretResult.Success(Unit)
            } else {
                MailSecretResult.Failed(MailSecretFailure.STORE_FAILED)
            }
        }

    private class CredentialApi {
        private val advapi: NativeLibrary = NativeLibrary.getInstance("Advapi32")

        // ALT_CONVENTION is stdcall, which 32-bit Windows APIs use; 64-bit has one convention.
        val write: Function = advapi.getFunction("CredWriteW", Function.ALT_CONVENTION)
        val read: Function = advapi.getFunction("CredReadW", Function.ALT_CONVENTION)
        val delete: Function = advapi.getFunction("CredDeleteW", Function.ALT_CONVENTION)
        val free: Function = advapi.getFunction("CredFree", Function.ALT_CONVENTION)
    }

    /**
     * `CREDENTIALW`, field for field (`FILETIME` as its two `DWORD`s). JNA lays it out with the
     * platform's alignment, so it is right on 32- and 64-bit Windows alike.
     */
    @Structure.FieldOrder(
        "flags",
        "type",
        "targetName",
        "comment",
        "lastWrittenLow",
        "lastWrittenHigh",
        "credentialBlobSize",
        "credentialBlob",
        "persist",
        "attributeCount",
        "attributes",
        "targetAlias",
        "userName",
    )
    class Credential(
        pointer: Pointer? = null,
    ) : Structure(pointer) {
        @JvmField var flags: Int = 0

        @JvmField var type: Int = 0

        @JvmField var targetName: WString? = null

        @JvmField var comment: WString? = null

        @JvmField var lastWrittenLow: Int = 0

        @JvmField var lastWrittenHigh: Int = 0

        @JvmField var credentialBlobSize: Int = 0

        @JvmField var credentialBlob: Pointer? = null

        @JvmField var persist: Int = 0

        @JvmField var attributeCount: Int = 0

        @JvmField var attributes: Pointer? = null

        @JvmField var targetAlias: WString? = null

        @JvmField var userName: WString? = null
    }

    private companion object {
        const val CRED_TYPE_GENERIC = 1

        /** Kept for this user on this machine, across sign-ins, and never roamed to other machines. */
        const val CRED_PERSIST_LOCAL_MACHINE = 2

        /** `CRED_MAX_CREDENTIAL_BLOB_SIZE`: five 512-byte pages. */
        const val MAX_BLOB_BYTES = 5 * 512

        const val ERROR_NOT_FOUND = 1168

        fun targetName(accountKey: String): String = "dev.npn.MailAccount:$accountKey"

        fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    }
}

/**
 * The keyring this desktop has: the Credential Manager on Windows, the secret service (libsecret)
 * on Linux, and none elsewhere, where email import is then not offered.
 */
internal fun desktopMailCredentials(): MailCredentialStore {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.startsWith("windows") -> WindowsMailCredentialStore()
        os.contains("linux") -> LibsecretMailCredentialStore()
        else -> UnsupportedMailCredentialStore
    }
}
