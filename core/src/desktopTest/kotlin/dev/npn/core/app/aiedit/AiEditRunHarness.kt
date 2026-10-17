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

/** An [AiEditRunImpl] over the shared AI-edit fakes, with [selection] as the documents the user selected. */
internal class AiEditRunHarness(
    selection: List<String> = emptyList(),
) {
    val documents = FakeDocumentRepository()
    val meta = FakeMetadataWriter(batchedFieldsOnly = true)
    val content = FakeDocContentFacade()
    val ai = FakeAiSuggest()
    val fieldRegistry = FakeFieldRegistry()

    /** The library's vocabulary: every tag these tests suggest as one it already has. */
    val tags = FakeTagRepository(listOf("x", "y", "z", "t", "t1", "t2", "invoice", "kept", "bad"))
    val run = AiEditRunImpl(documents, meta, content, ai, fieldRegistry, tags, selection)
}

internal val REQUEST = AiEditRequest()

internal fun key(
    scope: EditScope = EditScope.ALL,
    savedView: SavedView? = null,
    missing: List<String> = emptyList(),
    batchSize: Int = 50,
): ScopeKey = ScopeKey(scope, savedView, missing, batchSize)

internal suspend fun AiEditRun.suggest(
    key: ScopeKey,
    onProgress: (EditProgress) -> Unit = {},
): BatchResult = suggestNextBatch(key, REQUEST, onProgress)
