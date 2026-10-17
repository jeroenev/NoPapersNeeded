package dev.npn.core.app

import dev.npn.core.fs.NpnPaths
import dev.npn.core.model.FieldKeys
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Recovery is checked through a reopened session, rather than trusting the failed operation's cache. */
class LifecycleRecoveryTest {
    @Test
    fun trashAndRestoreRecoverBeforeAndAfterJournalPublication() =
        runTest {
            for (point in listOf(FileSystemFaults.Point.BEFORE_PUBLISH, FileSystemFaults.Point.AFTER_PUBLISH)) {
                LibraryDirs().use { (library, data) ->
                    val faults = FileSystemFaults()
                    val bytes = PdfFixtures.embeddedTextPdf("a paid bill")
                    var id = ""
                    openSession(backgroundScope, library, data, files = faults::over).use { session, _ ->
                        session.rescan()
                        val imported =
                            session
                                .importPdfBytes(
                                    bytes,
                                    "scan.pdf",
                                ).shouldBeInstanceOf<ImportOutcome.Imported>()
                        id = imported.docId
                        session.meta.setField(id, FieldKeys.TITLE, "Water bill")
                        faults.failNext(point)
                        if (point == FileSystemFaults.Point.BEFORE_PUBLISH) {
                            assertFailsWith<IOException> { session.docLifecycle.trashDoc(id) }
                            session.documents
                                .liveDocuments(listOf(id))
                                .single()
                                .doc.title shouldBe "Water bill"
                            Files.readAllBytes(library.resolve(imported.relPath)).toList() shouldBe bytes.toList()
                            session.docLifecycle.trashDoc(id).shouldNotBeNull()
                        } else {
                            session.docLifecycle.trashDoc(id).shouldNotBeNull()
                        }
                    }
                    openSession(backgroundScope, library, data, files = faults::over).use { session, _ ->
                        session.rescan()
                        session.documents
                            .observeTrashed()
                            .first()
                            .single()
                            .doc.docId shouldBe id
                        faults.failNext(point)
                        if (point == FileSystemFaults.Point.BEFORE_PUBLISH) {
                            assertFailsWith<IOException> { session.docLifecycle.restoreDoc(id) }
                            session.documents.liveDocuments(listOf(id)).shouldBeEmpty()
                            session.documents
                                .observeTrashed()
                                .first()
                                .single()
                                .doc.docId shouldBe id
                        } else {
                            session.docLifecycle.restoreDoc(id)
                        }
                    }
                    openSession(backgroundScope, library, data).use { session, deps ->
                        session.rescan()
                        if (point == FileSystemFaults.Point.BEFORE_PUBLISH) {
                            session.documents
                                .observeTrashed()
                                .first()
                                .single()
                                .doc.docId shouldBe id
                            session.docLifecycle.restoreDoc(id)
                        }
                        val restored = session.documents.liveDocuments(listOf(id)).single()
                        restored.doc.title shouldBe "Water bill"
                        deps.fs.readBytes(restored.doc.relPath).toList() shouldBe bytes.toList()
                        session.documents
                            .observeTrashed()
                            .first()
                            .shouldBeEmpty()
                    }
                }
            }
        }

    @Test
    fun aDeleteThatLeavesBytesStaysDeletedAfterRestartAndAnExplicitImportBringsItBack() =
        runTest {
            LibraryDirs().use { (library, data) ->
                val faults = FileSystemFaults()
                val bytes = PdfFixtures.embeddedTextPdf("delete recovery")
                var id = ""
                openSession(backgroundScope, library, data, files = faults::over).use { session, deps ->
                    session.rescan()
                    id = session.importPdfBytes(bytes, "scan.pdf").shouldBeInstanceOf<ImportOutcome.Imported>().docId
                    session.meta.setField(id, FieldKeys.TITLE, "Keep my metadata")
                    session.docLifecycle.trashDoc(id).shouldNotBeNull()
                    val trash =
                        deps.fs
                            .listAllUnder(NpnPaths.TRASH_DIR)
                            .single { !it.isDirectory }
                            .relPath
                    faults.failNext(FileSystemFaults.Point.DELETE, trash)
                    session.docLifecycle.deleteDocPermanently(id) shouldBe PermanentDeleteOutcome.FILE_LEFT
                    deps.fs.readBytes(trash).toList() shouldBe bytes.toList()
                }
                openSession(backgroundScope, library, data).use { session, _ ->
                    session.rescan()
                    session.documents.liveDocuments(listOf(id)).shouldBeEmpty()
                    session.documents
                        .observeTrashed()
                        .first()
                        .shouldBeEmpty()
                    session
                        .importPdfBytes(
                            bytes,
                            "scan.pdf",
                        ).shouldBeInstanceOf<ImportOutcome.Imported>()
                        .docId shouldBe
                        id
                    session.rescan()
                    session.documents
                        .liveDocuments(listOf(id))
                        .single()
                        .doc.title shouldBe "Keep my metadata"
                    session.documents
                        .observeDuplicates()
                        .first()
                        .shouldBeEmpty()
                }
            }
        }

    @Test
    fun aBakeWhoseSwapFailsBeforeOrAfterLandingRestoresTheOriginalAcrossRestart() =
        runTest {
            for (point in listOf(FileSystemFaults.Point.BEFORE_MOVE, FileSystemFaults.Point.AFTER_MOVE)) {
                LibraryDirs().use { (library, data) ->
                    val faults = FileSystemFaults()
                    val bytes = PdfFixtures.embeddedTextPdf("bake recovery")
                    var id = ""
                    openSession(backgroundScope, library, data, files = faults::over).use { session, _ ->
                        session.rescan()
                        val imported =
                            session
                                .importPdfBytes(
                                    bytes,
                                    "scan.pdf",
                                ).shouldBeInstanceOf<ImportOutcome.Imported>()
                        id = imported.docId
                        session.meta.setField(id, FieldKeys.TITLE, "Original")
                        faults.failNext(point, imported.relPath)
                        session.content.bakeInPlace(id).shouldBeInstanceOf<BakeOutcome.Failed>()
                        Files.readAllBytes(library.resolve(imported.relPath)).toList() shouldBe bytes.toList()
                    }
                    openSession(backgroundScope, library, data).use { session, deps ->
                        session.rescan()
                        val document =
                            session.documents
                                .liveDocuments(listOf(id))
                                .single()
                                .doc
                        document.title shouldBe "Original"
                        deps.fs.sha256(document.relPath) shouldBe id
                        session.content.bakeInPlace(id) shouldBe BakeOutcome.Baked
                    }
                }
            }
        }
}
