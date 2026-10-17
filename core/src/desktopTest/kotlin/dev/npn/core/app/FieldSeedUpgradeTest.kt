package dev.npn.core.app

import dev.npn.core.db.toFieldDef
import dev.npn.core.fs.NpnPaths
import dev.npn.core.index.IndexRig
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
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
}
