// checkAll's per-test PropTestConfig overload is still marked experimental by kotest.
@file:OptIn(ExperimentalKotest::class)

package dev.npn.core.journal

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.test.Test

/** [CanonicalEventTest]'s two shortcuts, over generated multi-device histories. */
class CanonicalEventPropertyTest {
    @Test
    fun theShortcutsAgreeWithTheValidatingDecoder() =
        propTest {
            checkAll(PropTestConfig(iterations = 50), Arb.long()) { seed ->
                for (event in EventHistoryGen(seed, deviceCount = 3, eventCount = 60).generate()) {
                    val decoded = EventDecoderRegistry.decode(JournalCodec.encode(event), allowMissingV1 = false)
                    require(EventDecoderRegistry.canonical(event) == decoded) { "canonical differs for $event" }
                    require(EventDecoderRegistry.decodeStored(decoded.canonicalJson) == decoded.event) {
                        "stored decode differs for $event"
                    }
                }
            }
        }
}
