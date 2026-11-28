package dev.npn.core.pathrules

import dev.npn.core.db.documentEntity
import dev.npn.core.index.indexTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** Where the storage-path rule files a document whose target another document holds. */
class StoragePathRuleApplicatorTest {
    /**
     * A document an older build filed beside its taken target (`acme-1.pdf`) is where the rule wants
     * it already: applying the rule again leaves it, rather than moving it to today's name for that
     * (`acme (7f3a).pdf`), a rename the user never asked for that every device then syncs. One at
     * another name still moves, and one whose target is free again takes it.
     */
    @Test
    fun aDocumentAnOlderBuildFiledBesideItsTakenTargetStays() =
        indexTest { rig ->
            val dao = rig.db.documentDao()
            val first = "a".repeat(64)
            val second = "7f3a" + "0".repeat(60)
            val elsewhere = "b".repeat(64)
            dao.upsert(documentEntity(first, relPath = "Invoices/acme.pdf"))
            dao.upsert(documentEntity(second, relPath = "Invoices/acme-1.pdf"))
            dao.upsert(documentEntity(elsewhere, relPath = "inbox/scan.pdf"))
            val moves = mutableListOf<Pair<String, String>>()
            var template = "Invoices/acme"
            val applicator =
                StoragePathRuleApplicator(
                    rig.fs,
                    rig.db,
                    ruleProvider = { StoragePathRule(template, enabled = true) },
                    setPath = { doc, path -> moves += doc to path },
                )

            applicator.applyFor(first)
            applicator.applyFor(second)
            moves.shouldBeEmpty()

            applicator.applyFor(elsewhere)
            moves shouldBe listOf(elsewhere to "Invoices/acme (bbbb).pdf")

            moves.clear()
            template = "Archive/acme"
            applicator.applyFor(second)
            moves shouldBe listOf(second to "Archive/acme.pdf")
        }
}
