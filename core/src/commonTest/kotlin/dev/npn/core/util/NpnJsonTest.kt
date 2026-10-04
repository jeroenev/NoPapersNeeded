package dev.npn.core.util

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

class NpnJsonTest {
    @Serializable
    private enum class Kind { TEXT, DATE }

    @Serializable
    private data class Draft(
        val value: String = "",
        val kind: Kind = Kind.TEXT,
        val saving: Boolean = false,
    )

    /** A record from another build of the app still loads instead of being silently discarded. */
    @Test
    fun persistedReadsWhatAnotherBuildWrote() {
        val newer = """{"value":"x","kind":"DURATION","saving":null,"addedLater":{"a":1}}"""
        assertEquals(Draft(value = "x"), NpnJson.Persisted.decodeFromString(Draft.serializer(), newer))
    }

    @Test
    fun persistedWritesDefaultsOut() {
        assertEquals(
            """{"value":"","kind":"TEXT","saving":false}""",
            NpnJson.Persisted.encodeToString(Draft.serializer(), Draft()),
        )
    }

    @Test
    fun lenientAcceptsTheLooseJsonModelsProduce() {
        val loose = """{value: unquoted, kind: DATE, extra: 1}"""
        assertEquals(Draft("unquoted", Kind.DATE), NpnJson.Lenient.decodeFromString(Draft.serializer(), loose))
    }
}
