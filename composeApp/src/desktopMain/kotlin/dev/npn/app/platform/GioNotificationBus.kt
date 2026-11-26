package dev.npn.app.platform

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.StringArray
import com.sun.jna.ptr.PointerByReference

/**
 * [NotificationBus] over GLib's GDBus (`libgio-2.0`), through the JNA dependency the libsecret mail
 * store already uses: no D-Bus library of our own, and every Linux desktop, the Flatpak runtime
 * included, has GLib. Each GVariant is built with GLib's non-variadic constructors, which JNA calls
 * the same way on every architecture. Calls block, so callers run them off the UI thread.
 */
internal class GioNotificationBus private constructor(
    private val gio: Gio,
) : NotificationBus {
    /** The session bus, or null when there is none (no desktop session, no D-Bus). */
    private val connection: Pointer? by lazy {
        val error = PointerByReference()
        val bus = gio.call("g_bus_get_sync", G_BUS_TYPE_SESSION, null, error) as Pointer?
        gio.discardError(error)
        bus
    }

    override fun capabilities(): List<String> {
        val reply = call(HOST_NAME, HOST_PATH, HOST_INTERFACE, "GetCapabilities", tuple()) ?: return emptyList()
        return try {
            val list = gio.call("g_variant_get_child_value", reply, 0L) as Pointer
            try {
                val strings = gio.call("g_variant_get_strv", list, null) as Pointer?
                try {
                    strings?.getStringArray(0, "UTF-8")?.toList().orEmpty()
                } finally {
                    strings?.let { gio.callVoid("g_free", it) }
                }
            } finally {
                gio.callVoid("g_variant_unref", list)
            }
        } finally {
            gio.callVoid("g_variant_unref", reply)
        }
    }

    override fun notify(
        summary: String,
        body: String,
    ): UInt? {
        val hints =
            dictionary(
                "desktop-entry" to string(APP_ID),
                "urgency" to gio.call("g_variant_new_byte", URGENCY_NORMAL) as Pointer,
            )
        val params =
            tuple(
                string(APP_NAME),
                gio.call("g_variant_new_uint32", 0) as Pointer,
                string(APP_ID),
                string(summary),
                string(body),
                gio.call("g_variant_new_strv", StringArray(arrayOf(DEFAULT_ACTION, OPEN_LABEL)), 2L) as Pointer,
                hints,
                gio.call("g_variant_new_int32", -1) as Pointer,
            )
        val reply = call(HOST_NAME, HOST_PATH, HOST_INTERFACE, "Notify", params) ?: return null
        return try {
            val id = gio.call("g_variant_get_child_value", reply, 0L) as Pointer
            try {
                (gio.callInt("g_variant_get_uint32", id)).toUInt()
            } finally {
                gio.callVoid("g_variant_unref", id)
            }
        } finally {
            gio.callVoid("g_variant_unref", reply)
        }
    }

    override fun addPortalNotification(
        id: String,
        title: String,
        body: String,
    ): Boolean {
        val notification =
            dictionary(
                "title" to string(title),
                "body" to string(body),
                "default-action" to string(PORTAL_ACTION),
                "default-action-target" to gio.call("g_variant_new_variant", string(id)) as Pointer,
            )
        val reply = call(PORTAL_NAME, PORTAL_PATH, PORTAL_INTERFACE, "AddNotification", tuple(string(id), notification))
        reply?.let { gio.callVoid("g_variant_unref", it) }
        return reply != null
    }

    override fun listen(
        onHost: (UInt) -> Unit,
        onPortal: (String) -> Unit,
    ) {
        val bus = connection ?: return
        val hostClicks =
            signalCallback { params ->
                // ActionInvoked (u id, s action_key)
                val id = child(params, 0) { gio.callInt("g_variant_get_uint32", it).toUInt() }
                val action = child(params, 1) { readString(it) }
                if (action == DEFAULT_ACTION) onHost(id)
            }
        val portalClicks =
            signalCallback { params ->
                // ActionInvoked (s id, s action, av parameter)
                val id = child(params, 0) { readString(it) }
                val action = child(params, 1) { readString(it) }
                if (action == PORTAL_ACTION || "app.$action" == PORTAL_ACTION) onPortal(id)
            }
        callbacks += hostClicks
        callbacks += portalClicks
        // GDBus delivers a signal in the main context that was the subscribing thread's default,
        // so the subscriptions are made on the thread that then runs that context.
        Thread({
            val context = gio.call("g_main_context_new") as Pointer
            gio.callVoid("g_main_context_push_thread_default", context)
            subscribe(bus, HOST_NAME, HOST_INTERFACE, HOST_PATH, hostClicks)
            subscribe(bus, PORTAL_NAME, PORTAL_INTERFACE, PORTAL_PATH, portalClicks)
            while (true) gio.callInt("g_main_context_iteration", context, 1)
        }, "npn-notification-clicks").apply {
            isDaemon = true
            start()
        }
    }

    /** Callbacks GLib holds a pointer to: JNA frees one whose Java object is collected. */
    private val callbacks = ArrayList<SignalCallback>()

    private fun subscribe(
        bus: Pointer,
        sender: String,
        iface: String,
        path: String,
        callback: SignalCallback,
    ) {
        gio.callInt(
            "g_dbus_connection_signal_subscribe",
            bus,
            sender,
            iface,
            "ActionInvoked",
            path,
            null,
            0,
            callback,
            null,
            null,
        )
    }

    /** A method call on the session bus, its reply, or null when the bus or the service is not there. */
    private fun call(
        name: String,
        path: String,
        iface: String,
        method: String,
        params: Pointer,
    ): Pointer? {
        val bus =
            connection ?: run {
                // Nothing will take the floating parameters: sink and drop them.
                gio.callVoid("g_variant_unref", gio.call("g_variant_ref_sink", params) as Pointer)
                return null
            }
        val error = PointerByReference()
        val reply =
            gio.call(
                "g_dbus_connection_call_sync",
                bus,
                name,
                path,
                iface,
                method,
                params,
                null,
                0,
                CALL_TIMEOUT_MS,
                null,
                error,
            ) as Pointer?
        gio.discardError(error)
        return reply
    }

    private fun string(value: String): Pointer = gio.call("g_variant_new_string", value) as Pointer

    private fun tuple(vararg children: Pointer): Pointer {
        val array = pointers(children.toList())
        return gio.call("g_variant_new_tuple", array, children.size.toLong()) as Pointer
    }

    /** An `a{sv}` of [entries], each value wrapped in a variant. */
    private fun dictionary(vararg entries: Pair<String, Pointer>): Pointer {
        val items =
            entries.map { (key, value) ->
                gio.call(
                    "g_variant_new_dict_entry",
                    string(key),
                    gio.call("g_variant_new_variant", value) as Pointer,
                ) as Pointer
            }
        return gio.call("g_variant_new_array", DICT_ENTRY_TYPE, pointers(items), items.size.toLong()) as Pointer
    }

    private fun pointers(values: List<Pointer>): Memory? {
        if (values.isEmpty()) return null
        val memory = Memory(Native.POINTER_SIZE.toLong() * values.size)
        values.forEachIndexed { index, pointer -> memory.setPointer(Native.POINTER_SIZE.toLong() * index, pointer) }
        return memory
    }

    private fun <T> child(
        tuple: Pointer,
        index: Long,
        read: (Pointer) -> T,
    ): T {
        val value = gio.call("g_variant_get_child_value", tuple, index) as Pointer
        return try {
            read(value)
        } finally {
            gio.callVoid("g_variant_unref", value)
        }
    }

    private fun readString(variant: Pointer): String =
        (gio.call("g_variant_get_string", variant, null) as Pointer).getString(0, "UTF-8")

    /** `GDBusSignalCallback`: JNA calls the one method of an interface that extends [Callback]. */
    private interface SignalCallback : Callback {
        @Suppress("LongParameterList") // The C signature GLib calls.
        fun callback(
            connection: Pointer?,
            sender: Pointer?,
            path: Pointer?,
            iface: Pointer?,
            signal: Pointer?,
            parameters: Pointer,
            userData: Pointer?,
        )
    }

    /** A [SignalCallback] that reads only the signal's parameters, and never throws back into GLib. */
    private fun signalCallback(handle: (Pointer) -> Unit): SignalCallback =
        object : SignalCallback {
            override fun callback(
                connection: Pointer?,
                sender: Pointer?,
                path: Pointer?,
                iface: Pointer?,
                signal: Pointer?,
                parameters: Pointer,
                userData: Pointer?,
            ) {
                runCatching { handle(parameters) }
            }
        }

    /** `libgio-2.0` and what it calls through. */
    private class Gio {
        private val library: NativeLibrary = load("gio-2.0", "libgio-2.0.so.0", "libgio-2.0.so")
        private val functions = HashMap<String, Function>()

        private fun function(name: String): Function =
            synchronized(functions) { functions.getOrPut(name) { library.getFunction(name) } }

        fun call(
            name: String,
            vararg args: Any?,
        ): Any? = function(name).invoke(Pointer::class.java, args)

        fun callInt(
            name: String,
            vararg args: Any?,
        ): Int = function(name).invokeInt(args)

        // invokeVoid, not invoke(null, ...): JNA dereferences the return type, so a null one throws.
        fun callVoid(
            name: String,
            vararg args: Any?,
        ) = function(name).invokeVoid(args)

        /** Free a `GError` GLib set; its message is not needed. */
        fun discardError(error: PointerByReference) {
            error.value?.let { callVoid("g_error_free", it) }
        }

        private fun load(vararg names: String): NativeLibrary {
            var last: Throwable? = null
            for (name in names) {
                try {
                    return NativeLibrary.getInstance(name)
                } catch (failure: UnsatisfiedLinkError) {
                    last = failure
                }
            }
            throw last ?: UnsatisfiedLinkError("No GIO library")
        }
    }

    companion object {
        /** The bus over this system's GLib, or null where there is none (not Linux, or no GLib). */
        fun createOrNull(): GioNotificationBus? = runCatching { GioNotificationBus(Gio()) }.getOrNull()

        private const val G_BUS_TYPE_SESSION = 2
        private const val CALL_TIMEOUT_MS = 5_000
        private const val URGENCY_NORMAL: Byte = 1

        private const val APP_NAME = "No Papers Needed"

        /** The desktop entry and icon name the packages install. */
        private const val APP_ID = "io.github.jeroenev.NoPapersNeeded"
        private const val DEFAULT_ACTION = "default"
        private const val OPEN_LABEL = "Open"
        private const val PORTAL_ACTION = "app.open"

        private const val HOST_NAME = "org.freedesktop.Notifications"
        private const val HOST_PATH = "/org/freedesktop/Notifications"
        private const val HOST_INTERFACE = "org.freedesktop.Notifications"
        private const val PORTAL_NAME = "org.freedesktop.portal.Desktop"
        private const val PORTAL_PATH = "/org/freedesktop/portal/desktop"
        private const val PORTAL_INTERFACE = "org.freedesktop.portal.Notification"

        /** `G_VARIANT_TYPE ("{sv}")`: a `GVariantType *` is its type string. */
        private val DICT_ENTRY_TYPE = Memory(5).apply { setString(0, "{sv}", "US-ASCII") }
    }
}
