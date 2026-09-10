package dev.npn.core.app

import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiFieldConfig
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.ai.AiFieldSpec
import dev.npn.core.ai.AiPromptContext
import dev.npn.core.ai.AiPrompts
import dev.npn.core.ai.LibraryAiTools
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldType
import dev.npn.core.pathrules.StoragePathContext
import dev.npn.core.pathrules.StoragePathTemplate
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The plan's acceptance gate (§8): one field definition, then assertions across the seams it has
 * to reach without a line of per-field code — the document value, the index, search, filter, sort,
 * the storage-path template, the AI contract and the assistant's field list.
 */
class FieldModelAcceptanceTest {
    @Test
    fun oneDefinitionReachesEverySeam() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("bravo"))
                rig.session.rescan()
                val a = rig.fs.sha256("a.pdf")
                val b = rig.fs.sha256("b.pdf")

                val amount =
                    FieldDef(
                        "amount",
                        "Amount",
                        FieldType.DECIMAL,
                        showByDefault = true,
                        aiEnabled = true,
                        position = 9,
                    )
                rig.session.fieldRegistry.upsert(amount)
                rig.session.meta.setField(a, "amount", "4210")
                // A reindex is what projects field rows into FTS; a manual setField does not itself.
                rig.session.rescan()

                // The document value, and the registry's vocabulary and usage.
                rig.session.documents
                    .observeDoc(a)
                    .first()!!
                    .doc.fields["amount"] shouldBe "4210"
                rig.session.fieldRegistry.distinctValuesNow("amount") shouldContainExactly listOf("4210")
                rig.session.fieldRegistry
                    .usageCounts()
                    .first()["amount"] shouldBe 1
                rig.session.fieldRegistry
                    .orphanKeys()
                    .first()
                    .map { it.fieldKey } shouldNotContain "amount"

                val repo = DocumentRepositoryImpl(rig.db)
                // Equality filter.
                repo
                    .observeDocuments(LibraryFilter(fieldEquals = mapOf("amount" to "4210")), LibrarySort())
                    .first()
                    .map { it.doc.docId } shouldContainExactly listOf(a)
                // Numeric range on the canonical value.
                repo
                    .observeDocuments(
                        LibraryFilter(fieldRanges = mapOf("amount" to FieldRange(from = "4000", to = "5000"))),
                        LibrarySort(),
                    ).first()
                    .map { it.doc.docId } shouldContainExactly listOf(a)
                // Sort by the field; a document without it sorts last in either direction.
                repo
                    .observeDocuments(
                        LibraryFilter(),
                        LibrarySort(field = SortField.Field("amount"), ascending = false),
                    ).first()
                    .map { it.doc.docId } shouldContainExactly listOf(a, b)
                // FTS indexes field values.
                rig.db.ftsDao().searchDocIds("4210") shouldContain a

                // Storage-path template: the generic placeholder reads the same map.
                StoragePathTemplate
                    .expand(
                        "{field:amount}/{title}.pdf",
                        StoragePathContext(mapOf("amount" to "4210", "title" to "A"), emptyList(), "a.pdf"),
                    ).getOrThrow() shouldBe "4210/A.pdf"

                // The assistant's field list names it, with its type and count.
                val listed =
                    LibraryAiTools
                        .ListFieldsTool(rig.db.documentFieldDao(), listOf(amount))
                        .execute(LibraryAiTools.ListFieldsTool.Args())
                listed.contains("amount · Amount · decimal") shouldBe true
                listed.contains("1 docs") shouldBe true

                // The AI contract asks for it under the nested fields object, canonicalized by type.
                val specs = listOf(AiFieldSpec(amount, AiFieldMode.UNFILLED))
                val system =
                    AiPrompts.editSystem(
                        request = AiEditRequest(fields = listOf(AiFieldConfig("amount"))),
                        fields = specs,
                        max = 5,
                        maxNew = 0,
                        vocabulary = emptyList(),
                        context = AiPromptContext.of(null),
                    )
                system.contains("\"fields\": {\"amount\": number|null}") shouldBe true
            } finally {
                rig.close()
            }
        }
}
