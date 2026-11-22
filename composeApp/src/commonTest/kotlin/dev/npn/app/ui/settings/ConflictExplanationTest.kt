package dev.npn.app.ui.settings

import dev.npn.core.sync.ConflictKind
import dev.npn.core.sync.SyncConflict
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** How a conflict is explained: every kind, its label, and the copy's second path. */
class ConflictExplanationTest {
    private val document = ConflictDocument(title = "Electricity bill", relPath = "Bills/electricity.pdf")

    private fun explain(
        kind: ConflictKind,
        otherPath: String? = null,
        docId: String? = "doc",
    ) = explainConflict(
        SyncConflict(docId = docId, remotePath = "Bills/electricity (1).pdf", kind = kind, otherPath = otherPath),
        document.takeIf { docId != null },
    )

    @Test
    fun everyKindIsExplainedInPlainLanguage() {
        explain(ConflictKind.TARGET_OCCUPIED) shouldBe
            ConflictExplanation.TargetOccupied("Electricity bill")
        explain(ConflictKind.UNIDENTIFIED) shouldBe
            ConflictExplanation.Unidentified("Electricity bill")
        explain(ConflictKind.REMOTE_CHANGED) shouldBe
            ConflictExplanation.RemoteChanged("Electricity bill")
        explain(ConflictKind.LOCAL_CHANGED) shouldBe
            ConflictExplanation.LocalChanged("Electricity bill")
        explain(ConflictKind.KEPT_ON_THIS_DEVICE) shouldBe
            ConflictExplanation.KeptOnThisDevice("Electricity bill")
    }

    @Test
    fun aCopysExplanationCarriesWhereItWent() {
        explain(ConflictKind.UPLOADED_AS_COPY, otherPath = "Bills/electricity (2).pdf") shouldBe
            ConflictExplanation.UploadedAsCopy("Electricity bill", "Bills/electricity (2).pdf")
        explain(ConflictKind.INSTALLED_AS_COPY, otherPath = "Bills/electricity (2).pdf") shouldBe
            ConflictExplanation.InstalledAsCopy("Electricity bill", "Bills/electricity (2).pdf")
    }

    @Test
    fun aConflictWhoseDocumentIsGoneNamesTheRemotePath() {
        explain(ConflictKind.TARGET_OCCUPIED, docId = null) shouldBe
            ConflictExplanation.TargetOccupied("Bills/electricity (1).pdf")
    }

    @Test
    fun aDocumentWithABlankTitleFallsBackToThePath() {
        explainConflict(
            SyncConflict("doc", "Bills/electricity (1).pdf", ConflictKind.TARGET_OCCUPIED),
            document.copy(title = ""),
        ) shouldBe ConflictExplanation.TargetOccupied("Bills/electricity (1).pdf")
    }
}
