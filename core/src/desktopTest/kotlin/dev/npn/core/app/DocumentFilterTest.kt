package dev.npn.core.app

import dev.npn.core.db.BIND_CHUNK
import dev.npn.core.db.StatementLog
import dev.npn.core.db.queryPlan
import dev.npn.core.index.IndexRig
import dev.npn.core.index.rowAtPath
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.ocr.OcrEngineSelector
import dev.npn.core.ocr.OcrPipeline
import dev.npn.core.ocr.OcrSidecarFiles
import dev.npn.core.pdf.PdfBoxEngine
import dev.npn.core.pdf.PdfFixtures
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

    /** An id list too long to bind in one statement narrows the result like a short one does. */
    @Test
    fun aLongIdListNarrowsAlongsideTheRestOfTheFilter() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                listOf("alpha", "bravo", "charlie").forEach {
                    rig.writePdf("$it.pdf", PdfFixtures.embeddedTextPdf("invoice $it"))
                }
                rig.session.rescan()
                drainOcr(rig)
                val (a, b, c) = listOf("alpha.pdf", "bravo.pdf", "charlie.pdf").map { rig.fs.sha256(it) }
                rig.session.meta.addTag(listOf(a, b), "x")
                val repo = DocumentRepositoryImpl(rig.db)

                suspend fun matching(filter: LibraryFilter) = ids(repo.observeDocuments(filter, LibrarySort()).first())

                val selected = listOf(a, c)
                val long = selected + (1..BIND_CHUNK).map { it.toString(16).padStart(64, '0') }
                for (docIds in listOf(selected, long)) {
                    matching(LibraryFilter(docIds = docIds)) shouldContainExactlyInAnyOrder listOf(a, c)
                    matching(LibraryFilter(docIds = docIds, tagsAll = listOf("x"))) shouldContainExactly listOf(a)
                    matching(LibraryFilter(docIds = docIds, query = "invoice")) shouldContainExactlyInAnyOrder
                        listOf(a, c)
                }
            } finally {
                rig.close()
            }
        }

    /**
     * An id filter of any length stays in the SQL as one bind on the primary key, so a selection
     * reads its own rows and no others, even past SQLite's bind-variable limit, under every sort.
     */
    @Test
    fun anIdFilterOfAnyLengthIsOneBindLookedUpByPrimaryKey() =
        runTest {
            val statements = StatementLog()
            val rig = IndexRig.create(statements)
            try {
                listOf("a", "b", "c", "d").forEach { rig.writePdf("$it.pdf", it) }
                rig.rescan()
                val dao = rig.db.documentDao()
                val (a, b, c) = listOf("a.pdf", "b.pdf", "c.pdf").map { checkNotNull(dao.rowAtPath(it)).docId }
                dao.upsert(checkNotNull(dao.getByDocId(b)).copy(trashed = true))
                val repo = DocumentRepositoryImpl(rig.db)
                val sorts = listOf(LibrarySort()) + listOf(SortField.Name, SortField.Size).map { LibrarySort(it, true) }

                for (unknown in listOf(BIND_CHUNK + 100, SQLITE_MAX_VARIABLE_NUMBER + 7_000)) {
                    val docIds = listOf(a, b, c) + (1..unknown).map { it.toString(16).padStart(64, '0') }
                    for (sort in sorts) {
                        statements.clear()
                        val found = repo.observeDocuments(LibraryFilter(docIds = docIds), sort).first()
                        val sql = statements.statements.single { it.startsWith("SELECT d.* FROM ") }

                        withClue("${docIds.size} ids, $sort: $sql") {
                            ids(found) shouldContainExactlyInAnyOrder listOf(a, c)
                            sql shouldContain "d.docId = ids.value"
                            sql.count { it == '?' } shouldBe 1
                            // The documents are read once per id, through the primary key, and no other way.
                            rig.db.queryPlan(sql).filter { readsDocuments.containsMatchIn(it) } shouldBe
                                listOf("SEARCH d USING INDEX sqlite_autoindex_documents_1 (docId=?)")
                        }
                    }
                }
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

    private fun buildPipeline(rig: AppSessionRig): OcrPipeline =
        OcrPipeline(
            fs = rig.fs,
            sidecars = OcrSidecarFiles(rig.fs),
            pdfEngine = PdfBoxEngine(),
            engines = OcrEngineSelector(local = NoopOcrEngine()),
            documentDao = rig.db.documentDao(),
            ftsDao = rig.db.ftsDao(),
            ocrQueueDao = rig.deviceDb.ocrQueueDao(),
            tagDao = rig.db.tagDao(),
            fieldDao = rig.db.documentFieldDao(),
            deviceId = rig.session.deviceId,
            language = { "eng" },
        )

    private companion object {
        /** The bundled SQLite's bind-variable limit on desktop and Android. */
        const val SQLITE_MAX_VARIABLE_NUMBER = 32_766

        /** A query plan line that reads the library query's `documents d`. */
        val readsDocuments = Regex("""^(SCAN|SEARCH) d\b""")
    }
}
