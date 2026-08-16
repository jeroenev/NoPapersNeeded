package dev.npn.core.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Android [SettingsRepository] backed by [SharedPreferences]. Values are mirrored into a
 * [MutableStateFlow] loaded at init and kept in sync via an [OnSharedPreferenceChangeListener].
 *
 * SharedPreferences avoids the extra DataStore dependency
 * and okio path handling, and these are a handful of small app-global settings.
 *
 * All key mapping, defaults and clamping live in the shared [SettingsCodec]; this class only owns
 * the storage primitive (SharedPreferences and its change listener).
 */
class SettingsRepositoryImpl(
    context: Context,
) : SettingsRepository {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Adapters that let the shared codec read from / write to SharedPreferences. Readers return
    // null when a key is absent (the codec then supplies the default) and treat empty strings as
    // null, matching the pre-refactor behaviour. Declared before `state` because `state`'s
    // initializer calls load(), which uses `reader`.
    private val reader =
        object : SettingsCodec.Reader {
            override fun string(key: String): String? = prefs.getString(key, null)?.ifEmpty { null }

            override fun int(key: String): Int? = if (prefs.contains(key)) prefs.getInt(key, 0) else null

            override fun boolean(key: String): Boolean? =
                if (prefs.contains(key)) prefs.getBoolean(key, false) else null
        }

    private val state = MutableStateFlow(load())

    // `edit` is a read-modify-write over the whole snapshot, so two concurrent calls could
    // interleave and have the later commit rewrite every key from a stale mirror — losing the
    // earlier edit across all settings, not just the field it touched. Serialise them, as the
    // desktop implementation does.
    private val persistMutex = Mutex()

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            state.value = load()
        }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    private fun writer(editor: SharedPreferences.Editor) =
        object : SettingsCodec.Writer {
            override fun putString(
                key: String,
                value: String,
            ) {
                editor.putString(key, value)
            }

            override fun putInt(
                key: String,
                value: Int,
            ) {
                editor.putInt(key, value)
            }

            override fun putBoolean(
                key: String,
                value: Boolean,
            ) {
                editor.putBoolean(key, value)
            }

            override fun remove(key: String) {
                editor.remove(key)
            }
        }

    private fun load(): SettingsSnapshot {
        val installationId =
            prefs.getString(SettingsKeys.INSTALLATION_ID, null)
                ?: UUID.randomUUID().toString().also {
                    prefs.edit().putString(SettingsKeys.INSTALLATION_ID, it).apply()
                }
        return SettingsCodec.load(reader, installationId)
    }

    override val settings: Flow<SettingsSnapshot> = state

    override suspend fun edit(transform: (SettingsSnapshot) -> SettingsSnapshot) =
        persistMutex.withLock {
            val next = SettingsCodec.normalise(transform(state.value))
            withContext(Dispatchers.IO) {
                val editor = prefs.edit()
                SettingsCodec.store(writer(editor), next)
                // commit() (synchronous) rather than apply() so a following settings read — e.g.
                // the rules flow re-reading autoMatchRulesJson right after a write — never sees the
                // previous value. It writes to disk inline, hence the IO dispatcher: callers reach
                // here from viewModelScope (Dispatchers.Main.immediate), and the text settings
                // write on every keystroke.
                editor.commit()
            }
            state.value = next
        }

    companion object {
        private const val PREFS_NAME = "npn_settings"
    }
}
