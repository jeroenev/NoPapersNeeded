package dev.npn.core.app

import dev.npn.core.db.toFieldDef
import dev.npn.core.fs.NpnPaths
import dev.npn.core.index.IndexRig
import dev.npn.core.journal.PendingOp
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

    private suspend fun journalObjects(): Int =
        rig.fs
            .listAllUnder(NpnPaths.JOURNAL_DIR)
            .count { NpnPaths.journalPath(it.relPath) is NpnPaths.JournalPath.ObjectV2 }

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
}
