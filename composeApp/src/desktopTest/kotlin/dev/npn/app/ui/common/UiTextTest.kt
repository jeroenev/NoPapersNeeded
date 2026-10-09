package dev.npn.app.ui.common

import dev.npn.app.english
import dev.npn.app.resourcesIn
import dev.npn.app.ui.library.asUiText
import dev.npn.core.app.ImportTally
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_add_tag_failed
import no_papers_needed.composeapp.generated.resources.library_moved_to_trash
import no_papers_needed.composeapp.generated.resources.library_trash_failed
import java.util.Locale
import kotlin.test.Test

/** [UiText] resolved against the real resources, as the snackbar shows it. */
class UiTextTest {
    @Test
    fun aSentenceIsFilledWithItsArgumentsResolvedFirst() =
        runTest {
            uiText(Res.string.detail_add_tag_failed, UiText.Raw("disk full")).english() shouldBe
                "Could not add the tag: disk full"
            RuntimeException().reasonText().english() shouldBe "RuntimeException"
        }

    @Test
    fun aPluralPicksItsFormByQuantity() =
        runTest {
            uiText(Res.plurals.library_moved_to_trash, 1, 1).english() shouldBe "Moved 1 document to trash"
            uiText(Res.plurals.library_trash_failed, 3, 1, 3).english() shouldBe
                "Could not move 1 of 3 documents to the trash"
        }

    @Test
    fun anImportTallyListsOnlyWhatHappened() =
        runTest {
            ImportTally(3, 0, 0).asUiText().english() shouldBe "3 imported"
            ImportTally(2, 1, 0).asUiText().english() shouldBe "2 imported, 1 duplicate"
            ImportTally(2, 1, 3).asUiText().english() shouldBe "2 imported, 1 duplicate, 3 failed"
            ImportTally(0, 1, 0).asUiText().english() shouldBe "1 duplicate"
            ImportTally(0, 0, 1).asUiText().english() shouldBe "1 failed"
            ImportTally(0, 2, 1).asUiText().english() shouldBe "2 duplicates, 1 failed"
            // Nothing happened at all: the tally still says so rather than reading empty.
            ImportTally().asUiText().english() shouldBe "0 imported"
        }

    /** A failure is one sentence per language, so French can elide where it needs to. */
    @Test
    fun aFailureReadsAsOneSentenceInFrench() =
        runTest {
            val french = resourcesIn(Locale.FRANCE)
            uiText(Res.string.detail_add_tag_failed, UiText.Raw("disque plein")).resolve(french) shouldBe
                "Impossible d’ajouter l’étiquette : disque plein"
        }
}
