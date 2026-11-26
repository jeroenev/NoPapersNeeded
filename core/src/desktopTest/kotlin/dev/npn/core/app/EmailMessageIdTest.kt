package dev.npn.core.app

import dev.npn.core.db.searchDocIds
import dev.npn.core.mail.MailImportResult
import dev.npn.core.model.FieldKeys
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlin.test.Test

/**
 * The email PDF records its email's Message-ID, in a seeded field the app keeps for itself: an email
 * read again is known by it, whatever bytes its PDF is made of this time (another build, another
 * renderer), and the field shows up in no list, no document, no search and no model's view.
 */
class EmailMessageIdTest {
    private val messageId = "invoice-4711@mail.example.com"

    @Test
    fun anEmailPdfIsKnownByItsMessageIdUntilItIsRemoved() =
        appSessionTest { rig ->
            rig.session.mailSink.emailPdfOf(messageId) shouldBe null

            val imported =
                rig.session.mailSink
                    .importEmailPdf(PdfFixtures.embeddedTextPdf("alpha"), "email-1 Invoice.pdf", messageId)
                    .shouldBeInstanceOf<MailImportResult.Imported>()

            // Other bytes, the same email: what a changed renderer makes of it.
            rig.session.mailSink.emailPdfOf(messageId) shouldBe MailImportResult.Duplicate
            rig.session.mailSink.emailPdfOf("another@mail.example.com") shouldBe null

            rig.session.docLifecycle.trashDoc(imported.docId)
            rig.session.mailSink.emailPdfOf(messageId) shouldBe MailImportResult.Removed
        }

    @Test
    fun theMessageIdIsListedShownAndSearchedNowhere() =
        appSessionTest { rig ->
            val imported =
                rig.session.mailSink
                    .importEmailPdf(PdfFixtures.embeddedTextPdf("alpha"), "email-1 Invoice.pdf", messageId)
                    .shouldBeInstanceOf<MailImportResult.Imported>()
            rig.db
                .documentFieldDao()
                .valuesOf(imported.docId)[FieldKeys.EMAIL_MESSAGE_ID] shouldBe messageId

            rig.session.fieldRegistry
                .current()
                .map { it.key } shouldNotContain FieldKeys.EMAIL_MESSAGE_ID
            rig.session.fieldRegistry.fields
                .first()
                .map { it.key } shouldNotContain FieldKeys.EMAIL_MESSAGE_ID
            rig.session.fieldRegistry
                .orphanKeys()
                .first() shouldNotContain FieldKeys.EMAIL_MESSAGE_ID
            rig.session.fieldRegistry
                .usageCounts()
                .first() shouldNotContainKey FieldKeys.EMAIL_MESSAGE_ID
            val doc =
                rig.session.documents
                    .observeDoc(imported.docId)
                    .first()
                    .shouldNotBeNull()
                    .doc
            doc.fields shouldNotContainKey FieldKeys.EMAIL_MESSAGE_ID
            rig.db
                .ftsDao()
                .searchDocIds("4711")
                .shouldBeEmpty()
        }
}
