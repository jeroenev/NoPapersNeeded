import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NpnVersionTest {
    @Test
    fun acceptedVersionsKeepTheirPersistedSpellingInPackageNames() {
        listOf("0.0.0", "1.2.3", "255.99.99").forEach { version ->
            assertEquals(version, NpnVersion.parse(version).name)
        }
        assertEquals(10203, NpnVersion.parse("1.2.3").code)
    }

    @Test
    fun refusesNoncanonicalComponentsBeforeTheyCanNameARelease() {
        listOf("01.2.3", "1.02.3", "1.2.03", "00.0.0", " 1.2.3 ").forEach { version ->
            assertFailsWith<IllegalArgumentException>(version) { NpnVersion.parse(version) }
        }
    }

    @Test
    fun refusesOversizedComponentsWithoutIntegerOverflow() {
        listOf("256.0.0", "1.100.0", "1.0.100", "999999999999999999999.0.0").forEach { version ->
            assertFailsWith<IllegalArgumentException>(version) { NpnVersion.parse(version) }
        }
    }
}
