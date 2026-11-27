package dev.npn.core.app

import dev.npn.core.db.toFieldDef
import dev.npn.core.index.IndexRig
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.journalObjects
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * A library seeded by a build from before the per-document flag and the seed hints gets them once,
 * from whichever device opens it first with this build, without losing what its users changed.
 */
class FieldSeedUpgradeTest {
    private val rig = IndexRig.create()
    private val seeds = FieldKeys.seedDefinitions().associateBy { it.key }

    /** The definitions the first seed wrote: no flag, no hints. */
    private val firstSeed = FieldKeys.seedDefinitions().map { it.copy(hint = null, perDocument = false) }

    @AfterTest
    fun tearDown() = rig.close()

    private suspend fun registry(): Map<String, FieldDef> =
        rig.db
            .fieldDefDao()
            .all()
            .mapNotNull { it.toFieldDef() }
            .associateBy { it.key }

    private suspend fun journalObjects(): Int = rig.fs.journalObjects().size

    @Test
    fun aLibrarySeededEarlierGetsTheNewSeedOnceAndKeepsWhatItsUsersChanged() =
        runTest {
            val coordinator = rig.coordinator()
            coordinator.refreshState()
            coordinator.seedFieldRegistryIfNeeded(firstSeed, version = 1)
            val ownHint = firstSeed.single { it.key == FieldKeys.CORRESPONDENT }.copy(hint = "Who wrote it")
            val relabelled = firstSeed.single { it.key == FieldKeys.TITLE }.copy(label = "Onderwerp")
            coordinator.commitAndApply(
                listOf(
                    PendingOp.fieldDefSet(ownHint),
                    PendingOp.fieldDefSet(relabelled),
                    PendingOp.fieldDefRemove(FieldKeys.DOC_DATE),
                ),
            )

            coordinator.seedFieldRegistryIfNeeded(FieldKeys.seedDefinitions())

            val upgraded = registry()
            upgraded.getValue(FieldKeys.TITLE) shouldBe
                relabelled.copy(perDocument = true, hint = seeds.getValue(FieldKeys.TITLE).hint)
            upgraded.getValue(FieldKeys.CORRESPONDENT) shouldBe ownHint
            upgraded.containsKey(FieldKeys.DOC_DATE) shouldBe false
            upgraded.getValue(FieldKeys.DOC_TYPE) shouldBe firstSeed.single { it.key == FieldKeys.DOC_TYPE }
            coordinator.setting(FieldKeys.SEEDED_SETTING) shouldBe FieldKeys.SEED_VERSION.toString()

            // Once: the next open finds the library at this version and writes nothing.
            val objects = journalObjects()
            rig.coordinator().apply { refreshState() }.seedFieldRegistryIfNeeded(FieldKeys.seedDefinitions())
            journalObjects() shouldBe objects
        }

    /**
     * A device that opens the library before another device's journal reached it sees no seeding
     * marker and seeds too, later than the other device's user changed the fields. Its seeds lose
     * to those changes once they arrive, rather than undoing them on every device.
     */
    @Test
    fun aDeviceSeedingBeforeAnotherDevicesChangesArriveDoesNotUndoThem() =
        runTest {
            val other = rig.writer("other-device")
            val relabelled = seeds.getValue(FieldKeys.TITLE).copy(label = "Onderwerp")
            val written =
                listOf(
                    other.commit(
                        seeds.values.map { PendingOp.fieldDefSet(it) } +
                            PendingOp.settingSet(FieldKeys.SEEDED_SETTING, FieldKeys.SEED_VERSION.toString()),
                    ),
                    other.commit(
                        listOf(PendingOp.fieldDefSet(relabelled), PendingOp.fieldDefRemove(FieldKeys.DOC_DATE)),
                    ),
                )
            // Not here yet: the sync tool brings them only after this device opened the library.
            val arriving = written.associate { it.relPath to rig.fs.readBytes(it.relPath) }
            arriving.keys.forEach { rig.fs.delete(it) }
            rig.clock.advance(MINUTE)
            val coordinator = rig.coordinator()
            coordinator.refreshState()
            coordinator.seedFieldRegistryIfNeeded(FieldKeys.seedDefinitions())
            registry().getValue(FieldKeys.TITLE) shouldBe seeds.getValue(FieldKeys.TITLE)

            arriving.forEach { (path, bytes) -> rig.fs.writeBytesOnce(path, bytes) }
            coordinator.reconcileMetadata()

            val registry = registry()
            registry.getValue(FieldKeys.TITLE) shouldBe relabelled
            registry.containsKey(FieldKeys.DOC_DATE) shouldBe false
            coordinator.setting(FieldKeys.SEEDED_SETTING) shouldBe FieldKeys.SEED_VERSION.toString()
        }

    /**
     * An older build's label edit writes a newer title definition without the per-document flag, as
     * it drops every key it does not know. The next open gives the flag back and keeps the edit; an
     * open that finds the flag in place writes nothing.
     */
    @Test
    fun aDefinitionAnOlderBuildWroteWithoutTheFlagGetsItBackOnce() =
        runTest {
            val coordinator = rig.coordinator()
            coordinator.refreshState()
            coordinator.seedFieldRegistryIfNeeded(FieldKeys.seedDefinitions())
            val olderEdit = seeds.getValue(FieldKeys.TITLE).copy(label = "Onderwerp", perDocument = false)
            rig.clock.advance()
            coordinator.commitAndApply(listOf(PendingOp.fieldDefSet(olderEdit)))
            registry().getValue(FieldKeys.TITLE).perDocument shouldBe false

            val before = journalObjects()
            rig.clock.advance()
            coordinator.restoreSeededPerDocument(FieldKeys.seedDefinitions(), registry().values.toList())

            registry().getValue(FieldKeys.TITLE) shouldBe olderEdit.copy(perDocument = true)
            registry().filterKeys { it != FieldKeys.TITLE } shouldBe seeds.filterKeys { it != FieldKeys.TITLE }
            journalObjects() shouldBe before + 1

            val reopened = rig.coordinator().apply { refreshState() }
            reopened.restoreSeededPerDocument(FieldKeys.seedDefinitions(), registry().values.toList())
            journalObjects() shouldBe before + 1
        }

    @Test
    fun aFreshLibraryIsSeededAtTheCurrentVersion() =
        runTest {
            val coordinator = rig.coordinator()
            coordinator.refreshState()

            coordinator.seedFieldRegistryIfNeeded(FieldKeys.seedDefinitions())

            registry() shouldBe seeds
            registry().getValue(FieldKeys.TITLE).sharesVocabulary shouldBe false
            coordinator.setting(FieldKeys.SEEDED_SETTING) shouldBe FieldKeys.SEED_VERSION.toString()
        }

    /**
     * The e-invoice fields are the library's own choice now ([FieldRegistry.setEInvoiceFields]):
     * a library seeded at 2 comes up to the current version without them.
     */
    @Test
    fun aLibrarySeededAtTwoDoesNotGainTheEInvoiceFields() =
        runTest {
            val coordinator = rig.coordinator()
            coordinator.refreshState()
            coordinator.seedFieldRegistryIfNeeded(FieldKeys.seedDefinitions(), version = 2)

            coordinator.seedFieldRegistryIfNeeded(FieldKeys.seedDefinitions())

            registry().keys shouldBe seeds.keys
            FieldKeys.EINVOICE_FIELDS.forEach { registry().containsKey(it) shouldBe false }
            coordinator.setting(FieldKeys.SEEDED_SETTING) shouldBe FieldKeys.SEED_VERSION.toString()
        }

    /** The user's own library is at three: it gains the email Message-ID, and nothing else changes. */
    @Test
    fun aLibrarySeededAtThreeGainsTheEmailMessageIdOnce() =
        runTest {
            val coordinator = rig.coordinator()
            coordinator.refreshState()
            val v3Seeds = FieldKeys.seedDefinitions().filterNot { it.key == FieldKeys.EMAIL_MESSAGE_ID }
            coordinator.seedFieldRegistryIfNeeded(v3Seeds, version = 3)

            coordinator.seedFieldRegistryIfNeeded(FieldKeys.seedDefinitions())

            registry() shouldBe seeds
            coordinator.setting(FieldKeys.SEEDED_SETTING) shouldBe "4"
            val objects = journalObjects()
            rig.coordinator().apply { refreshState() }.seedFieldRegistryIfNeeded(FieldKeys.seedDefinitions())
            journalObjects() shouldBe objects
        }

    /**
     * What an earlier build seeded at 3: the five, and the e-invoice fields with them. None of it is
     * taken away; the upgrade to 4 adds only the email Message-ID.
     */
    private val earlierThreeSeeds =
        FieldKeys.seedDefinitions().filterNot { it.key == FieldKeys.EMAIL_MESSAGE_ID } +
            FieldKeys.eInvoiceDefinitions()

    @Test
    fun aLibrarySeededAtThreeWithTheEInvoiceFieldsKeepsThem() =
        runTest {
            val coordinator = rig.coordinator()
            coordinator.refreshState()
            coordinator.seedFieldRegistryIfNeeded(earlierThreeSeeds, version = 3)

            rig.coordinator().apply { refreshState() }.seedFieldRegistryIfNeeded(FieldKeys.seedDefinitions())

            registry() shouldBe
                earlierThreeSeeds.associateBy { it.key } + seeds.filterKeys { it == FieldKeys.EMAIL_MESSAGE_ID }
        }

    @Test
    fun aLibrarySeededAtThreeKeepsADeletedEInvoiceFieldDeleted() =
        runTest {
            val coordinator = rig.coordinator()
            coordinator.refreshState()
            coordinator.seedFieldRegistryIfNeeded(earlierThreeSeeds, version = 3)
            coordinator.commitAndApply(listOf(PendingOp.fieldDefRemove(FieldKeys.INVOICE_NUMBER)))

            coordinator.seedFieldRegistryIfNeeded(FieldKeys.seedDefinitions())

            registry().containsKey(FieldKeys.INVOICE_NUMBER) shouldBe false
        }

    private companion object {
        const val MINUTE = 60_000L
    }
}
