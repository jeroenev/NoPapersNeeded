package dev.npn.core.ai

import dev.npn.core.db.FtsDocumentEntity
import dev.npn.core.db.MetadataEventEntity
import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.db.SourceFingerprint
import dev.npn.core.db.SourceKind
import dev.npn.core.db.SourceStatus
import dev.npn.core.db.TempIndexDb
import dev.npn.core.db.documentEntity
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalCodec
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalOp
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.SystemProperty
import dev.npn.core.ocr.OcrState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The AI tools' library-wide and lifecycle surface: [LibraryAiTools.LibraryStatsTool],
 * [LibraryAiTools.SearchDocumentsTool]'s added/OCR/review/lifecycle filters, and
 * [LibraryAiTools.GetDocumentHistoryTool]'s bounded history.
 */
class LibraryStatsAndLifecycleToolsTest {
    private val temp = TempIndexDb("npn-ai-stats-lifecycle-test")
    private val db = temp.db

    private val live1 = "a".repeat(64)
    private val live2 = "b".repeat(64)
    private val trashed = "c".repeat(64)
    private val missing = "d".repeat(64)
    private val deleted = "e".repeat(64)

    @AfterTest
    fun teardown() = temp.close()

    private fun day(iso: String): Long = LocalDate.parse(iso).toEpochDays() * MILLIS_PER_DAY

    private fun searchTool(reviewMode: ReviewMode = ReviewMode.DEFAULT) =
        LibraryAiTools.SearchDocumentsTool(db.ftsDao(), db.documentDao(), db.tagDao(), reviewMode)

    private fun statsTool(reviewMode: ReviewMode = ReviewMode.DEFAULT) =
        LibraryAiTools.LibraryStatsTool(db.documentDao(), reviewMode)

    private suspend fun seedLifecycle() {
        db.documentDao().upsertAll(
            listOf(
                documentEntity(
                    live1,
                    createdAt = day("2020-01-10"),
                    docDate = "2026-07-15",
                    ocrState = OcrState.NONE.column,
                    reviewed = true,
                ),
                documentEntity(
                    live2,
                    createdAt = null,
                    mtime = day("2026-11-20"),
                    docDate = "2019-03-04",
                    ocrState = OcrState.DONE.column,
                ),
                documentEntity(trashed, relPath = "trash/c.pdf", trashed = true, ocrState = OcrState.PENDING.column),
                documentEntity(missing, relPath = "missing/d.pdf", missing = true),
                documentEntity(deleted, relPath = "gone/e.pdf", deleted = true, ocrState = OcrState.FAILED.column),
            ),
        )
    }

    @Test
    fun libraryStatsReportsLiveTotalsLifecycleTotalsOcrCoverageAndBothRanges() =
        runTest {
            seedLifecycle()

            val out = statsTool().execute(LibraryAiTools.LibraryStatsTool.Args())

            out shouldContain "- live documents: 2"
            out shouldContain "- untagged: 2"
            out shouldContain "- review queue (untagged-only mode): 2"
            out shouldContain "- in trash: 1"
            out shouldContain "- missing files: 1"
            out shouldContain "- permanently deleted: 1"
            out shouldContain "none=1"
            out shouldContain "done=1"
            out shouldContain "pending=0"
            out shouldContain "failed=0"
            out shouldContain "adopted=0"
            // Both dates are ranges of live documents only, and added is not the printed date.
            out shouldContain "- added range: 2020-01-10 to 2026-11-20"
            out shouldContain "- document dates: 2019-03-04 to 2026-07-15"
        }

    @Test
    fun libraryStatsSaysTheLibraryIsEmptyRatherThanPrintingZerosForNothing() =
        runTest {
            statsTool().execute(LibraryAiTools.LibraryStatsTool.Args()) shouldBe "The library has no documents yet."
        }

    @Test
    fun reviewQueueFollowsTheLibraryReviewMode() =
        runTest {
            seedLifecycle()
            db.tagDao().setForDocuments(mapOf(live1 to listOf("invoice")))

            // untagged-only: the stored reviewed marker is not authoritative; only live2 has no tag.
            statsTool(ReviewMode.UNTAGGED_ONLY)
                .execute(LibraryAiTools.LibraryStatsTool.Args()) shouldContain "- review queue (untagged-only mode): 1"

            // inbox-workflow: live1 is reviewed and tagged, live2 is unreviewed and untagged.
            statsTool(ReviewMode.INBOX_WORKFLOW)
                .execute(LibraryAiTools.LibraryStatsTool.Args()) shouldContain "- review queue (inbox-workflow mode): 1"
            db.documentDao().upsert(documentEntity(live2, relPath = "old.pdf", reviewed = true))
            db.tagDao().setForDocuments(mapOf(live2 to listOf("invoice")))
            statsTool(ReviewMode.INBOX_WORKFLOW)
                .execute(LibraryAiTools.LibraryStatsTool.Args()) shouldContain "- review queue (inbox-workflow mode): 0"
        }

    @Test
    fun searchFiltersByAddedRangeNotByTheDatePrintedOnTheDocument() =
        runTest {
            seedLifecycle()

            val out =
                searchTool().execute(
                    LibraryAiTools.SearchDocumentsTool.Args(
                        addedAtFrom = "2026-11-01",
                        addedAtTo = "2026-11-30",
                    ),
                )

            out shouldContain "[doc:$live2]"
            out shouldContain "added=2026-11-20"
            out shouldNotContain live1
        }

    @Test
    fun searchFiltersByOcrState() =
        runTest {
            seedLifecycle()

            val out = searchTool().execute(LibraryAiTools.SearchDocumentsTool.Args(ocrState = "DONE"))

            out shouldContain "[doc:$live2]"
            out shouldNotContain live1
        }

    @Test
    fun searchReviewedFilterUsesTheModeTheScreensUse() =
        runTest {
            seedLifecycle()
            db.tagDao().setForDocuments(mapOf(live1 to listOf("invoice")))

            searchTool().execute(LibraryAiTools.SearchDocumentsTool.Args(reviewed = true)) shouldContain "[doc:$live1]"
            val untagged = searchTool().execute(LibraryAiTools.SearchDocumentsTool.Args(reviewed = false))
            untagged shouldContain "[doc:$live2]"
            untagged shouldNotContain live1

            // In inbox mode an unreviewed document remains in the queue.
            searchTool(ReviewMode.INBOX_WORKFLOW)
                .execute(LibraryAiTools.SearchDocumentsTool.Args(reviewed = false))
                .shouldContain("[doc:$live2]")
        }

    @Test
    fun inboxReviewedAndQueueAreComplementsForBothSearchPaths() =
        runTest {
            seedLifecycle()
            db.tagDao().setForDocuments(mapOf(live2 to listOf("invoice")))
            for (id in listOf(live1, live2)) {
                db.ftsDao().upsertContent(FtsDocumentEntity(docId = id, content = "needle"))
            }
            for (query in listOf("", "needle")) {
                val tool = searchTool(ReviewMode.INBOX_WORKFLOW)
                val reviewed = tool.execute(LibraryAiTools.SearchDocumentsTool.Args(query = query, reviewed = true))
                val queue = tool.execute(LibraryAiTools.SearchDocumentsTool.Args(query = query, reviewed = false))
                reviewed shouldNotContain live1 // reviewed but untagged
                reviewed shouldNotContain live2 // tagged but unreviewed
                queue shouldContain live1
                queue shouldContain live2
            }
            db.tagDao().setForDocuments(mapOf(live1 to listOf("invoice"), live2 to listOf("invoice")))
            for (query in listOf("", "needle")) {
                val tool = searchTool(ReviewMode.INBOX_WORKFLOW)
                val reviewed = tool.execute(LibraryAiTools.SearchDocumentsTool.Args(query = query, reviewed = true))
                val queue = tool.execute(LibraryAiTools.SearchDocumentsTool.Args(query = query, reviewed = false))
                reviewed shouldContain live1
                queue shouldNotContain live1
            }
        }

    @Test
    fun filterOnlyQueryLimitsRowsAfterFilteringAndCountsAllMatches() =
        runTest {
            val ids = (0 until 40).map { it.toString().padStart(64, '0') }
            db.documentDao().upsertAll(
                ids.mapIndexed { index, id ->
                    documentEntity(
                        id,
                        relPath = "doc-$index.pdf",
                        createdAt = index.toLong(),
                        ocrState = if (index % 2 == 0) OcrState.FAILED.column else OcrState.DONE.column,
                    )
                },
            )
            val rows = db.documentDao().aiSearchRows("live", null, null, "failed", null, false, 15)
            rows.size shouldBe 15
            rows.first().docId shouldBe ids[38]
            db.documentDao().aiSearchCount("live", null, null, "failed", null, false) shouldBe 20
            val out = searchTool().execute(LibraryAiTools.SearchDocumentsTool.Args(ocrState = "failed"))
            out shouldContain "… and 5 more matches"
            out shouldNotContain "[doc:${ids[39]}]"
        }

    @Test
    fun searchDiscoversTrashedMissingAndDeletedDocumentsOnRequest() =
        runTest {
            seedLifecycle()

            searchTool().execute(LibraryAiTools.SearchDocumentsTool.Args(lifecycle = ChatTools.LIFECYCLE_TRASHED)).let {
                it shouldContain "[doc:$trashed]"
                it shouldContain "status=trashed"
                it shouldNotContain live1
            }
            searchTool()
                .execute(LibraryAiTools.SearchDocumentsTool.Args(lifecycle = ChatTools.LIFECYCLE_MISSING))
                .shouldContain("[doc:$missing]")
            searchTool().execute(LibraryAiTools.SearchDocumentsTool.Args(lifecycle = ChatTools.LIFECYCLE_DELETED)).let {
                it shouldContain "[doc:$deleted]"
                it shouldContain "status=deleted"
            }
            // The default lifecycle stays live-only.
            val live = searchTool().execute(LibraryAiTools.SearchDocumentsTool.Args())
            live shouldContain "[doc:$live1]"
            live shouldNotContain trashed
            live shouldNotContain missing
            live shouldNotContain deleted
        }

    @Test
    fun searchWithoutQueryListsNewestAddedFirstAndBoundsTheOutput() =
        runTest {
            val ids = (0 until 20).map { it.toString().padStart(64, '0') }
            db.documentDao().upsertAll(
                ids.mapIndexed { index, id ->
                    documentEntity(
                        id,
                        relPath = "doc-$index.pdf",
                        createdAt =
                            day("2026-01-01") + index * MILLIS_PER_DAY,
                    )
                },
            )

            val out = searchTool().execute(LibraryAiTools.SearchDocumentsTool.Args())

            out.lines().count { it.startsWith("- [doc:") } shouldBe LibraryAiTools.MAX_SEARCH_RESULTS
            out shouldContain "[doc:${ids.last()}]"
            out shouldNotContain "[doc:${ids.first()}]"
            out shouldContain "… and 5 more matches (showing ${LibraryAiTools.MAX_SEARCH_RESULTS})."
        }

    @Test
    fun searchRejectsFilterValuesItCannotHonour() =
        runTest {
            searchTool().execute(LibraryAiTools.SearchDocumentsTool.Args(ocrState = "half-done")) shouldStartWith
                "Error:"
            searchTool().execute(LibraryAiTools.SearchDocumentsTool.Args(addedAtFrom = "last week")) shouldStartWith
                "Error:"
            searchTool().execute(LibraryAiTools.SearchDocumentsTool.Args(addedAtTo = "2026-13-01")) shouldStartWith
                "Error:"
            searchTool()
                .execute(
                    LibraryAiTools.SearchDocumentsTool.Args(addedAtFrom = "2026-02-01", addedAtTo = "2026-01-01"),
                ).shouldStartWith("Error:")
            searchTool().execute(LibraryAiTools.SearchDocumentsTool.Args(lifecycle = "archived")) shouldStartWith
                "Error:"
        }

    @Test
    fun historyShowsOnlyTheNewestBoundedEventsAndTruncatesLongValues() =
        runTest {
            val other = "f".repeat(64)
            val longNote = "x".repeat(LibraryAiTools.MAX_HISTORY_VALUE_CHARS + 50)
            val events =
                (1..25).map { seq ->
                    event(
                        live1,
                        seq.toLong(),
                        Hlc(1_000L + seq, 0, "dev-a"),
                        JournalOp.FieldSet("notes", if (seq == 25) longNote else "note $seq"),
                    )
                } + event(other, 26, Hlc(2_000, 0, "dev-a"), JournalOp.TagAdd("other-doc-secret"))
            db.metadataProjectionDao().ingest(
                MetadataSourceEntity("src", SourceKind.SNAPSHOT, SourceFingerprint("fp"), null, SourceStatus.APPLIED),
                events,
            )

            val out =
                LibraryAiTools
                    .GetDocumentHistoryTool(
                        db,
                    ).execute(LibraryAiTools.GetDocumentHistoryTool.Args(live1))

            out.lines().count { it.startsWith("- [doc:$live1]") } shouldBe LibraryAiTools.MAX_HISTORY_EVENTS
            out shouldContain
                "25 recorded events, newest first, showing the latest ${LibraryAiTools.MAX_HISTORY_EVENTS}:"
            out shouldContain "x".repeat(LibraryAiTools.MAX_HISTORY_VALUE_CHARS) + "…"
            out shouldNotContain "note 5\""
            out shouldNotContain "other-doc-secret"
        }

    @Test
    fun historyWithNoEventsSaysSoAndStillNamesTheDocument() =
        runTest {
            seedLifecycle()

            val out =
                LibraryAiTools
                    .GetDocumentHistoryTool(
                        db,
                    ).execute(LibraryAiTools.GetDocumentHistoryTool.Args(live1))

            out shouldContain "[doc:$live1]"
            out shouldContain "No recorded history"
        }

    @Test
    fun historyDescribesTheJournalOpsItCanRead() =
        runTest {
            db.metadataProjectionDao().ingest(
                MetadataSourceEntity("src", SourceKind.SNAPSHOT, SourceFingerprint("fp"), null, SourceStatus.APPLIED),
                listOf(
                    event(live1, 1, Hlc(1_000, 0, "dev-a"), JournalOp.TagAdd("invoice")),
                    event(live1, 2, Hlc(1_001, 0, "dev-a"), JournalOp.PropSet(SystemProperty.REVIEWED, "true")),
                ),
            )

            val out =
                LibraryAiTools
                    .GetDocumentHistoryTool(
                        db,
                    ).execute(LibraryAiTools.GetDocumentHistoryTool.Args(live1))

            out shouldContain "tag added \"invoice\""
            out shouldContain "marked reviewed"
            out shouldContain "device=dev-a"
        }

    @Test
    fun getDocumentReportsTheAddedDate() =
        runTest {
            seedLifecycle()
            val out =
                LibraryAiTools
                    .GetDocumentTool(db.documentDao(), db.tagDao(), db.documentFieldDao(), emptyList())
                    .execute(LibraryAiTools.GetDocumentTool.Args(live2))

            out shouldContain "added: 2026-11-20"
        }

    private fun event(
        docId: String,
        seq: Long,
        hlc: Hlc,
        op: JournalOp,
    ): MetadataEventEntity {
        val event = JournalEvent(1, seq, hlc, hlc.deviceId, docId, op)
        return MetadataEventEntity(
            deviceId = hlc.deviceId,
            sequence = seq,
            canonicalJson = JournalCodec.encode(event),
            hlcPhysical = hlc.physicalMillis,
            hlcCounter = hlc.counter,
            hlcDevice = hlc.deviceId,
            documentId = docId,
            operation = op.wire,
        )
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
