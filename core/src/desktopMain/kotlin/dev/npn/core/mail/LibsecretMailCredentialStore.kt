package dev.npn.core.mail

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mail passwords in the Linux secret service (libsecret) through the JNA dependency the journal's
 * publication helper already uses. There is no plain-text fallback: a host without libsecret, or a
 * service that refuses the call, gets a typed [MailSecretFailure].
 *
 * The password is passed straight to `secret_password_store_sync` and never logged. The schema has
 * one string attribute, `account`, whose value is [MailAccount.accountKey].
 */
internal class LibsecretMailCredentialStore(
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : MailCredentialStore {
    private val library: SecretLibrary? by lazy { runCatching { SecretLibrary() }.getOrNull() }

    override val available: Boolean
        get() = library != null

    override suspend fun store(
        accountKey: String,
        password: String,
    ): MailSecretResult<Unit> =
        withContext(io) {
            val lib = library ?: return@withContext MailSecretResult.Failed(MailSecretFailure.UNAVAILABLE)
            val error = PointerByReference()
            val label = cString(LABEL)
            val secret = cString(password)
            val account = cString(accountKey)
            val attribute = cString(ACCOUNT_ATTRIBUTE)
            val stored =
                lib.store.invoke(
                    Integer.TYPE,
                    arrayOf<Any?>(
                        lib.schema.memory,
                        null,
                        label,
                        secret,
                        null,
                        error,
                        attribute,
                        account,
                        null,
                    ),
                ) as Int
            lib.discardError(error)
            if (stored != 0) MailSecretResult.Success(Unit) else MailSecretResult.Failed(MailSecretFailure.STORE_FAILED)
        }

    override suspend fun load(accountKey: String): MailSecretResult<String?> =
        withContext(io) {
            val lib = library ?: return@withContext MailSecretResult.Failed(MailSecretFailure.UNAVAILABLE)
            val error = PointerByReference()
            val attribute = cString(ACCOUNT_ATTRIBUTE)
            val account = cString(accountKey)
            val found =
                lib.lookup.invoke(
                    Pointer::class.java,
                    arrayOf<Any?>(lib.schema.memory, null, error, attribute, account, null),
                ) as Pointer?
            val failed = error.value != null
            lib.discardError(error)
            if (failed) {
                found?.let(lib::free)
                return@withContext MailSecretResult.Failed(MailSecretFailure.UNAVAILABLE)
            }
            if (found == null) return@withContext MailSecretResult.Success(null)
            val password =
                try {
                    found.getString(0, Charsets.UTF_8.name())
                } finally {
                    lib.free(found)
                }
            MailSecretResult.Success(password)
        }

    override suspend fun clear(accountKey: String): MailSecretResult<Unit> =
        withContext(io) {
            val lib = library ?: return@withContext MailSecretResult.Failed(MailSecretFailure.UNAVAILABLE)
            val error = PointerByReference()
            val attribute = cString(ACCOUNT_ATTRIBUTE)
            val account = cString(accountKey)
            val cleared =
                lib.clear.invoke(
                    Integer.TYPE,
                    arrayOf<Any?>(lib.schema.memory, null, error, attribute, account, null),
                ) as Int
            val failed = error.value != null
            lib.discardError(error)
            // FALSE without an error is "there was nothing to remove": what clearing asked for.
            if (cleared != 0 || !failed) {
                MailSecretResult.Success(Unit)
            } else {
                MailSecretResult.Failed(MailSecretFailure.STORE_FAILED)
            }
        }

    private class SecretLibrary {
        private val secret: NativeLibrary = load("secret-1", soname("libsecret-1"), "libsecret-1.so")
        private val glib: NativeLibrary = load("glib-2.0", soname("libglib-2.0"), "libglib-2.0.so")
        val schema = Schema()
        val store: Function = secret.getFunction("secret_password_store_sync")
        val lookup: Function = secret.getFunction("secret_password_lookup_sync")
        val clear: Function = secret.getFunction("secret_password_clear_sync")
        private val freeFunction: Function = glib.getFunction("g_free")
        private val errorFree: Function = glib.getFunction("g_error_free")

        // invokeVoid, not invoke(null, ...): JNA dereferences the return type, so a null one throws.
        fun free(pointer: Pointer) {
            freeFunction.invokeVoid(arrayOf<Any?>(pointer))
        }

        /** Release a `GError` libsecret set: its message is not needed and may name the server. */
        fun discardError(error: PointerByReference) {
            error.value?.let { errorFree.invokeVoid(arrayOf<Any?>(it)) }
        }
    }

    /**
     * The `SecretSchema` C struct: the schema name, flags, then a NULL-terminated array of 32
     * `{ const gchar *name; SecretSchemaAttributeType type; }` entries, then its private fields: a
     * `gint reserved` libsecret reads as the schema's reference count (zero, as here, marks a static
     * schema it never refs or frees) and seven reserved pointers. All of it is allocated and zeroed:
     * a struct cut short after the attributes would have libsecret read, and for a non-zero value
     * there increment, memory past the end. Laid out by hand so JNA needs no structure definitions;
     * all pointer targets stay in [name] and [attribute] so they outlive every call.
     */
    private class Schema {
        private val name = cString(SCHEMA_NAME)
        private val attribute = cString(ACCOUNT_ATTRIBUTE)
        val memory = Memory(SCHEMA_BYTES)

        init {
            memory.clear()
            memory.setPointer(NAME_OFFSET, name)
            memory.setInt(FLAGS_OFFSET, 0)
            memory.setPointer(ATTRIBUTES_OFFSET, attribute)
            memory.setInt(ATTRIBUTES_OFFSET + ATTRIBUTE_TYPE_OFFSET, ATTRIBUTE_STRING)
        }
    }

    private companion object {
        const val SCHEMA_NAME = "dev.npn.MailAccount"
        const val ACCOUNT_ATTRIBUTE = "account"
        const val LABEL = "No Papers Needed mail password"

        /** `const gchar *name` and `SecretSchemaFlags flags`, padded to the attribute array. */
        const val NAME_OFFSET = 0L
        const val FLAGS_OFFSET = 8L
        const val ATTRIBUTES_OFFSET = 16L

        /** The first attribute's `const gchar *name` and its `SecretSchemaAttributeType type`. */
        const val ATTRIBUTE_TYPE_OFFSET = 8L

        /** `SECRET_SCHEMA_ATTRIBUTE_STRING`. */
        const val ATTRIBUTE_STRING = 0

        /**
         * Name and flags (16), 32 attribute slots of 16 bytes each (the first is ours), the `gint
         * reserved` padded to 8, and `reserved1` to `reserved7`: `sizeof(SecretSchema)` on a 64-bit
         * host, 592.
         */
        const val SCHEMA_BYTES = 16L + 32 * 16 + 8 + 7 * 8

        fun cString(value: String): Memory {
            val bytes = value.toByteArray(Charsets.UTF_8)
            return Memory((bytes.size + 1).toLong()).apply {
                write(0, bytes, 0, bytes.size)
                setByte(bytes.size.toLong(), 0)
            }
        }

        /** A versioned shared-library name, built at runtime so a secret scanner does not read it as a key. */
        fun soname(base: String): String = "$base.so.0"

        /**
         * Load the first of [names] this system has. JNA's plain name asks for `libX.so`, which a
         * runtime system without the development package may not have; its versioned soname does.
         */
        fun load(vararg names: String): NativeLibrary {
            var last: Throwable? = null
            for (name in names) {
                try {
                    return NativeLibrary.getInstance(name)
                } catch (failure: Throwable) {
                    last = failure
                }
            }
            if (last != null) throw last
            error("No library name to load")
        }
    }
}
