package dev.npn.core.app

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.isLive
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.TrashOps
import dev.npn.core.fs.VerifiedPublicationFailedException
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.fs.trashEntries
import dev.npn.core.index.IndexRig
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.index.MetadataAccumulator
import dev.npn.core.index.MutableClock
import dev.npn.core.index.WatchedFileSystem
import dev.npn.core.index.indexTest
import dev.npn.core.index.requireByDocId
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.SystemProperty
import dev.npn.core.pathrules.StoragePathRuleApplicator
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.util.NpnLog
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.Collections
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** An explicit rename or move keeps the document's identity and refuses a name it cannot take. */
class DocumentRenameTest {
    @Test
    fun renameFileUpdatesPathSameDocId() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.fs.sha256("a.pdf")

            rig.session.docLifecycle.renameFile(id, "renamed.pdf") shouldBe RelocationOutcome.MOVED
            rig.exists("renamed.pdf").shouldBeTrue()
            rig.exists("a.pdf").shouldBeFalse()
            rig.db
                .documentDao()
                .requireByDocId(id)
                .relPath shouldBe "renamed.pdf"

            // Survives rescan with the same docId.
            rig.session.rescan()
            rig.db
                .documentDao()
                .requireByDocId(id)
                .relPath shouldBe "renamed.pdf"
        }

    /**
     * A name the user types is an answer, never an exception: a taken name says so, a name without
     * its `.pdf` gets one, and a path the library could not index is refused, with nothing written.
     */
    @Test
    fun renameOntoATakenOrUnusableNameIsATypedOutcome() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("beta"))
            rig.session.rescan()
            val id = rig.fs.sha256("a.pdf")
            val lifecycle = rig.session.docLifecycle

            lifecycle.renameFile(id, "b.pdf") shouldBe RelocationOutcome.TAKEN
            lifecycle.renameFile(id, "a (conflicted copy)") shouldBe RelocationOutcome.INVALID_NAME
            lifecycle.moveFile(id, "../outside") shouldBe RelocationOutcome.INVALID_NAME
            rig.db
                .documentDao()
                .requireByDocId(id)
                .relPath shouldBe "a.pdf"
            rig.fs.sha256("b.pdf") shouldNotBe id

            lifecycle.renameFile(id, "c") shouldBe RelocationOutcome.MOVED
            rig.exists("c.pdf").shouldBeTrue()
            rig.db
                .documentDao()
                .requireByDocId(id)
                .relPath shouldBe "c.pdf"
        }

    @Test
    fun moveFileUpdatesPathSameDocId() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.fs.sha256("a.pdf")

            rig.session.docLifecycle.moveFile(id, "archive") shouldBe RelocationOutcome.MOVED
            rig.exists("archive/a.pdf").shouldBeTrue()
            rig.db
                .documentDao()
                .requireByDocId(id)
                .relPath shouldBe "archive/a.pdf"

            rig.session.rescan()
            rig.db
                .documentDao()
                .requireByDocId(id)
                .relPath shouldBe "archive/a.pdf"
        }
}
