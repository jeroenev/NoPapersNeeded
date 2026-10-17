@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.npn.core.app.aiedit

import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.app.AppSessionRig
import dev.npn.core.app.DocumentRepositoryImpl
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SavedView
import dev.npn.core.app.SortField
import dev.npn.core.app.appSessionTest
import dev.npn.core.model.FieldKeys
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.testing.FakeAiSuggest
import dev.npn.core.testing.FakeDocContentFacade
import dev.npn.core.testing.FakeDocumentRepository
import dev.npn.core.testing.FakeFieldRegistry
import dev.npn.core.testing.FakeMetadataWriter
import dev.npn.core.testing.FakeTagRepository
import dev.npn.core.testing.doc
import dev.npn.core.testing.edit
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** The selection scope over the real library query, which a fake repository cannot vouch for. */
class AiEditRunSelectionQueryTest {
    @Test
    fun aSelectionPastSqlitesBindLimitResolvesToItsLiveDocuments() =
        appSessionTest { rig ->
            listOf("a", "b", "c", "d").forEach { rig.writePdf("$it.pdf", PdfFixtures.embeddedTextPdf(it)) }
            rig.session.rescan()
            val (a, b, c) = listOf("a.pdf", "b.pdf", "c.pdf").map { rig.docId(it) }
            rig.session.docLifecycle.trashDoc(b)
            // d stays live but unselected; the rest of the selection names nothing in the library.
            val unknown = (1..SQLITE_MAX_VARIABLE_NUMBER).map { it.toString(16).padStart(64, '0') }
            val selection = listOf(a, b, c) + unknown
            val run =
                AiEditRunImpl(
                    DocumentRepositoryImpl(rig.db),
                    FakeMetadataWriter(),
                    FakeDocContentFacade(),
                    FakeAiSuggest(),
                    FakeFieldRegistry(),
                    FakeTagRepository(),
                    selection,
                )
            val key = key(scope = EditScope.SELECTION)

            run.docsFor(key).first().map { it.doc.docId } shouldContainExactlyInAnyOrder listOf(a, c)
            run.remainingDocs(key).map { it.doc.docId } shouldContainExactlyInAnyOrder listOf(a, c)
            run.moreRemain(key) shouldBe true
        }

    private companion object {
        /** The bundled SQLite's bind-variable limit on desktop and Android, which BIND_CHUNK stays under. */
        const val SQLITE_MAX_VARIABLE_NUMBER = 32_766
    }
}
