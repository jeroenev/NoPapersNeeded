package dev.npn.core.app

import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FieldRegistryTest {
    private fun def(
        key: String,
        type: FieldType = FieldType.TEXT,
        position: Int = -1,
        label: String = key,
    ) = FieldDef(key, label, type, showByDefault = false, aiEnabled = false, position = position)

    @Test
    fun freshLibraryIsSeededOnceWithTheWellKnownFive() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val defs =
                    rig.session.fieldRegistry.fields
                        .first { it.isNotEmpty() }
                defs.map { it.key } shouldContainExactly listOf("title", "correspondent", "docType", "docDate", "notes")
                defs.single { it.key == FieldKeys.NOTES }.aiEnabled shouldBe false
                val objectsAfterSeed = rig.journalObjectCount()

                // A second device opening the same library must not seed again: the marker, not the
                // presence of definitions, is the guard.
                val (second, db2) = rig.secondSession(backgroundScope)
                try {
                    second.fieldRegistry.fields
                        .first { it.isNotEmpty() }
                        .size shouldBe 5
                    rig.journalObjectCount() shouldBe objectsAfterSeed
                } finally {
                    second.close()
                    db2.close()
                }
            } finally {
                rig.close()
            }
        }

    @Test
    fun deletingEveryFieldStaysDeletedOnAnotherDevice() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.session.fieldRegistry.fields
                    .first { it.isNotEmpty() }
                for (key in FieldKeys.WELL_KNOWN) rig.session.fieldRegistry.remove(key)
                rig.session.fieldRegistry.fields
                    .first { it.isEmpty() }

                val (second, db2) = rig.secondSession(backgroundScope)
                try {
                    second.fieldRegistry.current() shouldBe emptyList()
                } finally {
                    second.close()
                    db2.close()
                }
            } finally {
                rig.close()
            }
        }

    @Test
    fun upsertNormalizesKeysAndRejectsCaseCollisions() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val registry = rig.session.fieldRegistry
                registry.fields.first { it.isNotEmpty() }
                registry.upsert(def("invoice number", type = FieldType.NUMBER))
                val added = registry.fields.first { defs -> defs.any { it.key == "invoice_number" } }
                added.single { it.key == "invoice_number" }.position shouldBe 5

                assertFailsWith<IllegalArgumentException> { registry.upsert(def("Invoice_Number")) }
                // Same key, different casing of the label is an update, not a collision.
                registry.upsert(def("invoice_number", type = FieldType.NUMBER, label = "Invoice #", position = 5))
                registry.fields.first { defs -> defs.any { it.label == "Invoice #" } }
            } finally {
                rig.close()
            }
        }

    @Test
    fun wellKnownKeysKeepTheirSeededType() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val registry = rig.session.fieldRegistry
                registry.fields.first { it.isNotEmpty() }
                registry.upsert(def(FieldKeys.DOC_DATE, type = FieldType.TEXT, label = "When", position = 3))
                val date =
                    registry.fields.first { defs -> defs.any { it.label == "When" } }.single {
                        it.key ==
                            "docDate"
                    }
                date.type shouldBe FieldType.DATE
            } finally {
                rig.close()
            }
        }

    @Test
    fun changingAFieldTypeReprojectsTheNumericShadow() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                val registry = rig.session.fieldRegistry
                registry.fields.first { it.isNotEmpty() }
                registry.upsert(def("amount", type = FieldType.TEXT, position = 5))
                rig.session.meta.setField(id, "amount", "9")
                // A TEXT field carries no numeric shadow.
                rig.db
                    .documentFieldDao()
                    .fieldsForDocs(listOf(id))
                    .single { it.fieldKey == "amount" }
                    .numValue shouldBe null

                registry.upsert(def("amount", type = FieldType.NUMBER, position = 5))
                // The type change reprojects the documents that carry the field, so sort and range
                // filters compare numerically rather than lexicographically.
                rig.db
                    .documentFieldDao()
                    .fieldsForDocs(listOf(id))
                    .single { it.fieldKey == "amount" }
                    .numValue shouldBe 9.0
            } finally {
                rig.close()
            }
        }

    @Test
    fun removeWithClearValuesDropsTheDocumentsRowsInOneTransaction() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                val registry = rig.session.fieldRegistry
                registry.fields.first { it.isNotEmpty() }
                registry.upsert(def("amount", type = FieldType.DECIMAL))
                rig.session.meta.setField(id, "amount", "12.50")
                // Stored in its one canonical form.
                registry.distinctValues("amount").first { it.isNotEmpty() } shouldBe listOf("12.5")
                val before = rig.journalObjectCount()

                registry.remove("amount", clearValues = true)
                rig.journalObjectCount() shouldBe before + 1
                registry.fields.first { defs -> defs.none { it.key == "amount" } }
                registry.distinctValues("amount").first { it.isEmpty() }
                registry.orphanKeys().first() shouldBe emptyList()
            } finally {
                rig.close()
            }
        }

    /**
     * A device with no row for a trashed document (it joined since, or rebuilt its index) removes a
     * field with its values: the clear reaches that document from the journal, so its value does not
     * come back as an orphan once the document is restored.
     */
    @Test
    fun removeWithClearValuesClearsADocumentThisDeviceHasNoRowFor() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                val registry = rig.session.fieldRegistry
                registry.fields.first { it.isNotEmpty() }
                registry.upsert(def("amount", type = FieldType.DECIMAL))
                rig.session.meta.setField(id, "amount", "12.5")
                rig.session.docLifecycle
                    .trashDoc(id)
                    .shouldNotBeNull()

                val (second, secondDb) = rig.secondSession(backgroundScope)
                try {
                    secondDb.documentDao().getByDocId(id) shouldBe null
                    second.fieldRegistry.remove("amount", clearValues = true)
                } finally {
                    second.close()
                    secondDb.close()
                }

                rig.session.rescan()
                rig.session.docLifecycle.restoreDoc(id)
                rig.session.rescan()
                rig.db
                    .documentFieldDao()
                    .fieldsForDocs(listOf(id))
                    .map { it.fieldKey } shouldNotContain "amount"
                registry.orphanKeys().first() shouldBe emptyList()
            } finally {
                rig.close()
            }
        }

    @Test
    fun removeWithoutClearLeavesOrphans() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                val registry = rig.session.fieldRegistry
                registry.fields.first { it.isNotEmpty() }
                rig.session.meta.setField(id, "vendor", "Acme")
                registry
                    .orphanKeys()
                    .first { it.isNotEmpty() }
                    .single()
                    .fieldKey shouldBe "vendor"
                registry.upsert(def("vendor"))
                registry.orphanKeys().first { it.isEmpty() }
                registry.remove("vendor")
                registry
                    .orphanKeys()
                    .first { it.isNotEmpty() }
                    .single()
                    .count shouldBe 1
            } finally {
                rig.close()
            }
        }

    @Test
    fun reorderRewritesPositions() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val registry = rig.session.fieldRegistry
                registry.fields.first { it.isNotEmpty() }
                registry.reorder(listOf("notes", "title"))
                val ordered = registry.fields.first { it.first().key == "notes" }
                ordered.map { it.key } shouldContainExactly
                    listOf("notes", "title", "correspondent", "docType", "docDate")
                ordered.map { it.position } shouldContainExactly (0 until 5).toList()
            } finally {
                rig.close()
            }
        }
}
