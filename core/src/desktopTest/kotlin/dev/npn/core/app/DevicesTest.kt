package dev.npn.core.app

import dev.npn.core.index.AckRecord
import dev.npn.core.index.AckRecords
import dev.npn.core.util.NpnJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** The devices that share a library, as their acknowledgment records show them, and their retirement. */
class DevicesTest {
    @Test
    fun anotherDeviceIsListedAfterThisOneAndRetiresForEveryDevice() =
        appSessionTest { rig ->
            val other = "22222222-2222-4222-8222-222222222222"
            val record = AckRecord(device = other, n = 1, atMillis = rig.clock.now)
            rig.fs.writeBytesOnce(
                AckRecords.path(other, 1),
                NpnJson.Persisted.encodeToString(AckRecord.serializer(), record).encodeToByteArray(),
            )
            val devices = rig.session.devices

            devices.devices().map { it.deviceId to it.thisDevice } shouldBe
                listOf(rig.session.deviceId to true, other to false)

            devices.retire(other)
            devices.devices().single { it.deviceId == other }.retired shouldBe true
            rig.session.coordinator
                .setting(CausalPurge.retiredKey(other))
                .shouldNotBeNull()

            devices.unretire(other)
            devices.devices().single { it.deviceId == other }.retired shouldBe false

            shouldThrow<IllegalArgumentException> { devices.retire(rig.session.deviceId) }
        }

    /** A device whose latest record is more than 90 days old shows as silent, and holds nothing back. */
    @Test
    fun aDeviceSilentForNinetyDaysShowsSilent() =
        appSessionTest { rig ->
            val other = "33333333-3333-4333-8333-333333333333"
            val record = AckRecord(device = other, n = 1, atMillis = rig.clock.now)
            rig.fs.writeBytesOnce(
                AckRecords.path(other, 1),
                NpnJson.Persisted.encodeToString(AckRecord.serializer(), record).encodeToByteArray(),
            )
            rig.clock.now += CausalPurge.SILENT_AFTER_MILLIS + 1

            rig.session.devices
                .devices()
                .single { it.deviceId == other }
                .silent shouldBe true
        }
}
