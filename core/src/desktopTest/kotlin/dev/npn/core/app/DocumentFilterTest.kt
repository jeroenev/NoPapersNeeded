package dev.npn.core.app

import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class DocumentFilterTest {
    private fun ids(items: List<DocListItem>) = items.map { it.doc.docId }

    @Test
    fun tagFilters() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("bravo"))
                rig.writePdf("c.pdf", PdfFixtures.embeddedTextPdf("charlie"))
                rig.session.rescan()
                val a = rig.fs.sha256("a.pdf")
                val b = rig.fs.sha256("b.pdf")
                val c = rig.fs.sha256("c.pdf")

                rig.session.meta.addTag(listOf(a, b), "x")
                rig.session.meta.addTag(listOf(b, c), "y")

                val repo = DocumentRepositoryImpl(rig.db)
                // tagsAll [x] -> a, b
                ids(repo.observeDocuments(LibraryFilter(tagsAll = listOf("x")), LibrarySort()).first())
                    .shouldContainExactlyInAnyOrder(listOf(a, b))
                // tagsAll [x, y] -> b
                ids(repo.observeDocuments(LibraryFilter(tagsAll = listOf("x", "y")), LibrarySort()).first())
                    .shouldContainExactlyInAnyOrder(listOf(b))
                // tagsAny [x, y] -> a, b, c
                ids(repo.observeDocuments(LibraryFilter(tagsAny = listOf("x", "y")), LibrarySort()).first())
                    .shouldContainExactlyInAnyOrder(listOf(a, b, c))
                // tagsNone [x] -> c
                ids(repo.observeDocuments(LibraryFilter(tagsNone = listOf("x")), LibrarySort()).first())
                    .shouldContainExactlyInAnyOrder(listOf(c))
            } finally {
                rig.close()
            }
        }

    @Test
    fun liveDocumentsReadsTheLiveOnesAmongTheIdsWithTheirTags() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("bravo"))
                rig.writePdf("c.pdf", PdfFixtures.embeddedTextPdf("charlie"))
                rig.session.rescan()
                val a = rig.fs.sha256("a.pdf")
                val b = rig.fs.sha256("b.pdf")
                val c = rig.fs.sha256("c.pdf")
                rig.session.meta.addTag(listOf(a), "x")
                rig.session.docLifecycle.trashDoc(b)

                val live = DocumentRepositoryImpl(rig.db).liveDocuments(listOf(a, b, a, "f".repeat(64)))

                ids(live) shouldContainExactly listOf(a)
                live.single().tags shouldContainExactly listOf("x")
                ids(DocumentRepositoryImpl(rig.db).liveDocuments(listOf(c))) shouldContainExactly listOf(c)
            } finally {
                rig.close()
            }
        }

    @Test
    fun reviewedAndUntaggedAndFolder() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("inbox/a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.writePdf("filed/b.pdf", PdfFixtures.embeddedTextPdf("bravo"))
                rig.session.rescan()
                val a = rig.fs.sha256("inbox/a.pdf")
                val b = rig.fs.sha256("filed/b.pdf")

                rig.session.meta.setReviewed(listOf(b), true)
                rig.session.meta.addTag(listOf(b), "t")

                val repo = DocumentRepositoryImpl(rig.db)
                ids(repo.observeDocuments(LibraryFilter(reviewed = true), LibrarySort()).first())
                    .shouldContainExactly(listOf(b))
                ids(repo.observeDocuments(LibraryFilter(reviewed = false), LibrarySort()).first())
                    .shouldContainExactly(listOf(a))
                ids(repo.observeDocuments(LibraryFilter(untaggedOnly = true), LibrarySort()).first())
                    .shouldContainExactly(listOf(a))
                ids(repo.observeDocuments(LibraryFilter(folder = "inbox"), LibrarySort()).first())
                    .shouldContainExactly(listOf(a))
                // UNTAGGED_ONLY review count = untagged docs (only a).
                repo.observeUntaggedCount().first() shouldBe 1
            } finally {
                rig.close()
            }
        }

    @Test
    fun unreviewedOrUntaggedCoversInboxWorkflowReview() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                // a: reviewed + untagged  -> review (untagged)
                // b: unreviewed + tagged  -> review (unreviewed)
                // c: reviewed + tagged    -> not review
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("bravo"))
                rig.writePdf("c.pdf", PdfFixtures.embeddedTextPdf("charlie"))
                rig.session.rescan()
                val a = rig.fs.sha256("a.pdf")
                val b = rig.fs.sha256("b.pdf")
                val c = rig.fs.sha256("c.pdf")

                rig.session.meta.setReviewed(listOf(a, c), true)
                rig.session.meta.addTag(listOf(b, c), "t")

                val repo = DocumentRepositoryImpl(rig.db)
                ids(repo.observeDocuments(LibraryFilter(unreviewedOrUntagged = true), LibrarySort()).first())
                    .shouldContainExactlyInAnyOrder(listOf(a, b))
                repo.observeUnreviewedOrUntaggedCount().first() shouldBe 2
            } finally {
                rig.close()
            }
        }

    @Test
    fun docDateRangeFilter() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("bravo"))
                rig.session.rescan()
                val a = rig.fs.sha256("a.pdf")
                val b = rig.fs.sha256("b.pdf")

                rig.session.meta.setField(a, FieldKeys.DOC_DATE, "2026-01-15")
                rig.session.meta.setField(b, FieldKeys.DOC_DATE, "2026-06-20")

                val repo = DocumentRepositoryImpl(rig.db)
                ids(
                    repo
                        .observeDocuments(
                            LibraryFilter(
                                fieldRanges =
                                    mapOf(FieldKeys.DOC_DATE to FieldRange("2026-03-01", "2026-12-31")),
                            ),
                            LibrarySort(),
                        ).first(),
                ).shouldContainExactly(listOf(b))
            } finally {
                rig.close()
            }
        }

    @Test
    fun missingAnySelectsDocumentsLackingAnyOfTheGivenFields() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("bravo"))
                rig.writePdf("c.pdf", PdfFixtures.embeddedTextPdf("charlie"))
                rig.session.rescan()
                val a = rig.fs.sha256("a.pdf")
                val b = rig.fs.sha256("b.pdf")
                val c = rig.fs.sha256("c.pdf")

                // a: complete. b: has a date but no correspondent. c: has neither.
                rig.session.meta.setField(listOf(a), FieldKeys.CORRESPONDENT, "Acme")
                rig.session.meta.setField(a, FieldKeys.DOC_DATE, "2026-01-15")
                rig.session.meta.setField(b, FieldKeys.DOC_DATE, "2026-02-20")

                val repo = DocumentRepositoryImpl(rig.db)
                ids(
                    repo
                        .observeDocuments(
                            LibraryFilter(missingFields = listOf(FieldKeys.CORRESPONDENT)),
                            LibrarySort(),
                        ).first(),
                ).shouldContainExactlyInAnyOrder(listOf(b, c))
                ids(
                    repo
                        .observeDocuments(
                            LibraryFilter(missingFields = listOf(FieldKeys.DOC_DATE)),
                            LibrarySort(),
                        ).first(),
                ).shouldContainExactly(listOf(c))
                // OR-ed, not AND-ed: b misses only the correspondent, c misses both.
                ids(
                    repo
                        .observeDocuments(
                            LibraryFilter(missingFields = listOf(FieldKeys.CORRESPONDENT, FieldKeys.DOC_DATE)),
                            LibrarySort(),
                        ).first(),
                ).shouldContainExactlyInAnyOrder(listOf(b, c))
                // Combines (AND) with the rest of the filter.
                repo
                    .observeDocuments(
                        LibraryFilter(missingFields = listOf(FieldKeys.CORRESPONDENT), untaggedOnly = true),
                        LibrarySort(),
                    ).first()
                    .size shouldBe 2
            } finally {
                rig.close()
            }
        }

    @Test
    fun fieldEqualsMatchesACustomFieldKey() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("bravo"))
                rig.session.rescan()
                val a = rig.fs.sha256("a.pdf")
                val b = rig.fs.sha256("b.pdf")

                rig.session.meta.setField(a, "vendor", "Acme")
                rig.session.meta.setField(b, "vendor", "Globex")

                val repo = DocumentRepositoryImpl(rig.db)
                ids(
                    repo
                        .observeDocuments(
                            LibraryFilter(fieldEquals = mapOf("vendor" to "Acme")),
                            LibrarySort(),
                        ).first(),
                ).shouldContainExactly(listOf(a))
            } finally {
                rig.close()
            }
        }

    @Test
    fun fieldRangeOnANumberFieldComparesNumericallyNotLexicographically() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("bravo"))
                rig.session.rescan()
                val a = rig.fs.sha256("a.pdf")
                val b = rig.fs.sha256("b.pdf")

                // Register "amount" as a NUMBER so its values carry a numeric shadow (numValue).
                rig.session.fieldRegistry.fields
                    .first { it.isNotEmpty() }
                rig.session.fieldRegistry.upsert(
                    FieldDef(
                        key = "amount",
                        label = "Amount",
                        type = FieldType.NUMBER,
                        showByDefault = false,
                        aiEnabled = false,
                        position = -1,
                    ),
                )
                rig.session.meta.setField(a, "amount", "9")
                rig.session.meta.setField(b, "amount", "10")

                val repo = DocumentRepositoryImpl(rig.db)
                // A range from "10" upward must exclude "9". Lexicographically "9" > "10", so a
                // string comparison would wrongly keep a; numeric comparison keeps only b.
                ids(
                    repo
                        .observeDocuments(
                            LibraryFilter(fieldRanges = mapOf("amount" to FieldRange(from = "10"))),
                            LibrarySort(),
                        ).first(),
                ).shouldContainExactly(listOf(b))
            } finally {
                rig.close()
            }
        }

    @Test
    fun sortByNumberFieldOrdersNumericallyWithMissingLast() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("bravo"))
                rig.writePdf("c.pdf", PdfFixtures.embeddedTextPdf("charlie"))
                rig.session.rescan()
                val a = rig.fs.sha256("a.pdf")
                val b = rig.fs.sha256("b.pdf")
                val c = rig.fs.sha256("c.pdf")

                rig.session.fieldRegistry.fields
                    .first { it.isNotEmpty() }
                rig.session.fieldRegistry.upsert(
                    FieldDef(
                        key = "amount",
                        label = "Amount",
                        type = FieldType.NUMBER,
                        showByDefault = false,
                        aiEnabled = false,
                        position = -1,
                    ),
                )
                // a=9, b=10, c has no amount. Numeric asc order is 9, 10; c (missing) sorts last.
                rig.session.meta.setField(a, "amount", "9")
                rig.session.meta.setField(b, "amount", "10")

                val repo = DocumentRepositoryImpl(rig.db)
                ids(
                    repo
                        .observeDocuments(
                            LibraryFilter(),
                            LibrarySort(SortField.Field("amount"), ascending = true),
                        ).first(),
                ).shouldContainExactly(listOf(a, b, c))
            } finally {
                rig.close()
            }
        }

    @Test
    fun missingFieldsSelectsDocumentsLackingACustomKey() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("bravo"))
                rig.session.rescan()
                val a = rig.fs.sha256("a.pdf")
                val b = rig.fs.sha256("b.pdf")

                // Only a carries the custom key; b lacks it.
                rig.session.meta.setField(a, "vendor", "Acme")

                val repo = DocumentRepositoryImpl(rig.db)
                ids(
                    repo
                        .observeDocuments(
                            LibraryFilter(missingFields = listOf("vendor")),
                            LibrarySort(),
                        ).first(),
                ).shouldContainExactly(listOf(b))
            } finally {
                rig.close()
            }
        }

    @Test
    fun sortByNameAscAndAddedDesc() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("charlie.pdf", PdfFixtures.embeddedTextPdf("c"))
                rig.session.rescan()
                rig.writePdf("alpha.pdf", PdfFixtures.embeddedTextPdf("a"))
                rig.session.rescan()
                rig.writePdf("bravo.pdf", PdfFixtures.embeddedTextPdf("b"))
                rig.session.rescan()

                val repo = DocumentRepositoryImpl(rig.db)
                val byName =
                    repo
                        .observeDocuments(
                            LibraryFilter(),
                            LibrarySort(SortField.Name, ascending = true),
                        ).first()
                        .map { it.doc.fileName }
                byName shouldContainExactly listOf("alpha.pdf", "bravo.pdf", "charlie.pdf")

                val byAddedDesc =
                    repo
                        .observeDocuments(
                            LibraryFilter(),
                            LibrarySort(SortField.Added, ascending = false),
                        ).first()
                        .map { it.doc.fileName }
                // charlie added first, bravo last -> desc = bravo, alpha, charlie
                byAddedDesc.first() shouldBe "bravo.pdf"
                byAddedDesc.last() shouldBe "charlie.pdf"
            } finally {
                rig.close()
            }
        }

    @Test
    fun queryReturnsSnippet() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("inv.pdf", PdfFixtures.embeddedTextPdf("invoice acme corporation total"))
                rig.writePdf("other.pdf", PdfFixtures.embeddedTextPdf("unrelated grocery list"))
                rig.session.rescan()
                val inv = rig.fs.sha256("inv.pdf")

                // Run the real OCR pipeline (embedded text) to populate FTS content.
                rig.session.rescan() // ensures docs queued
                drainOcr(rig)

                val results =
                    rig.session.documents
                        .observeDocuments(
                            LibraryFilter(query = "invoice"),
                            LibrarySort(),
                        ).first()
                ids(results) shouldContainExactly listOf(inv)
                results.first().snippet.shouldNotBeNull()
            } finally {
                rig.close()
            }
        }

    private suspend fun drainOcr(rig: AppSessionRig) {
        // Drive the pipeline synchronously through each pending queue entry.
        val pipeline = buildPipeline(rig)
        @Suppress("ControlFlowWithEmptyBody")
        while (pipeline.processNext()) {}
    }

    private fun buildPipeline(rig: AppSessionRig): dev.npn.core.ocr.OcrPipeline =
        dev.npn.core.ocr.OcrPipeline(
            fs = rig.fs,
            sidecars =
                dev.npn.core.fs
                    .OcrSidecarFiles(rig.fs),
            pdfEngine =
                dev.npn.core.pdf
                    .PdfBoxEngine(),
            engines =
                dev.npn.core.ocr
                    .OcrEngineSelector(local = NoopOcrEngine()),
            documentDao = rig.db.documentDao(),
            ftsDao = rig.db.ftsDao(),
            ocrQueueDao = rig.deviceDb.ocrQueueDao(),
            tagDao = rig.db.tagDao(),
            fieldDao = rig.db.documentFieldDao(),
            deviceId = rig.session.deviceId,
            language = { "eng" },
        )
}
