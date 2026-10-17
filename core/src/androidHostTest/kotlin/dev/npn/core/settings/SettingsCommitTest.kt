package dev.npn.core.settings

import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Android's settings: a write the disk refused fails, and leaves the settings as the disk has them. */
class SettingsCommitTest {
    private val noBackup: File = Files.createTempDirectory("npn-no-backup").toFile()
    private val prefs = FakePreferences()

    @AfterTest
    fun cleanUp() {
        noBackup.deleteRecursively()
    }

    @Test
    fun aCommitTheDiskRefusesFailsAndChangesNothing() =
        runTest {
            val repository = SettingsRepositoryImpl(prefs, noBackup)
            repository.edit { it.copy(libraryLocation = "/storage/emulated/0/Documents/A") }
            prefs.diskFull = true

            assertFailsWith<IOException> {
                repository.edit { it.copy(libraryLocation = "/storage/emulated/0/Documents/B") }
            }

            repository.settings.first().libraryLocation shouldBe "/storage/emulated/0/Documents/A"
            SettingsRepositoryImpl(prefs, noBackup).settings.first().libraryLocation shouldBe
                "/storage/emulated/0/Documents/A"
        }

    /**
     * Preferences held in memory, which, like the platform's, take a commit's values into memory and
     * tell their listeners before the disk write's outcome is known; [diskFull] makes that write fail.
     */
    private class FakePreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        private val listeners = mutableListOf<OnSharedPreferenceChangeListener>()
        var diskFull = false

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()

        override fun getString(
            key: String,
            defValue: String?,
        ): String? = values[key] as? String ?: defValue

        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = defValues

        override fun getInt(
            key: String,
            defValue: Int,
        ): Int = values[key] as? Int ?: defValue

        override fun getLong(
            key: String,
            defValue: Long,
        ): Long = values[key] as? Long ?: defValue

        override fun getFloat(
            key: String,
            defValue: Float,
        ): Float = values[key] as? Float ?: defValue

        override fun getBoolean(
            key: String,
            defValue: Boolean,
        ): Boolean = values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = key in values

        override fun edit(): SharedPreferences.Editor = Edit()

        override fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
            listeners += listener
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
            listeners -= listener
        }

        private inner class Edit : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()

            private fun put(
                key: String,
                value: Any?,
            ) = apply { changes[key] = value }

            override fun putString(
                key: String,
                value: String?,
            ) = put(key, value)

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ) = put(key, values)

            override fun putInt(
                key: String,
                value: Int,
            ) = put(key, value)

            override fun putLong(
                key: String,
                value: Long,
            ) = put(key, value)

            override fun putFloat(
                key: String,
                value: Float,
            ) = put(key, value)

            override fun putBoolean(
                key: String,
                value: Boolean,
            ) = put(key, value)

            override fun remove(key: String) = apply { removals += key }

            override fun clear() = apply { removals += values.keys }

            override fun commit(): Boolean {
                values -= removals
                values += changes
                listeners.forEach { it.onSharedPreferenceChanged(this@FakePreferences, null) }
                return !diskFull
            }

            override fun apply() {
                commit()
            }
        }
    }
}
