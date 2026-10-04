package dev.npn.core.ai

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnattendedAiPolicyTest {
    private val configured =
        AiConfig(
            enabled = true,
            provider = AiProvider.CUSTOM,
            baseUrl = "http://localhost:1234",
            model = "m",
        )

    @Test
    fun devicePolicyFollowsTheUsersTogglesOnlyWhileConfigured() =
        runTest {
            var config = configured.copy(ocrAuto = true, autoOnOcr = true)
            val policy = UnattendedAiPolicy { config }
            assertTrue(policy.autoOcr())
            assertTrue(policy.autoEnrich())

            // The two toggles are independent.
            config = configured.copy(ocrAuto = true, autoOnOcr = false)
            assertTrue(policy.autoOcr())
            assertFalse(policy.autoEnrich())

            // An unconfigured endpoint spends nothing, whatever the toggles say.
            config = config.copy(ocrAuto = true, autoOnOcr = true, model = "")
            assertFalse(policy.autoOcr())
            assertFalse(policy.autoEnrich())
        }

    @Test
    fun devicePolicyReadsLive() =
        runTest {
            var config = configured
            val policy = UnattendedAiPolicy { config }
            assertFalse(policy.autoOcr())
            config = configured.copy(ocrAuto = true)
            assertTrue(policy.autoOcr())
        }

    @Test
    fun neverSpends() =
        runTest {
            assertFalse(NEVER_SPENDS.autoOcr())
            assertFalse(NEVER_SPENDS.autoEnrich())
        }
}

/** A policy that never spends unattended: AI is not even configured. */
internal val NEVER_SPENDS = UnattendedAiPolicy { AiConfig.DISABLED }
