package dev.npn.core.app.aiedit

import dev.npn.core.ai.AiEditRequest
import dev.npn.core.app.SavedView
import dev.npn.core.testing.FakeAiSuggest
import dev.npn.core.testing.FakeDocContentFacade
import dev.npn.core.testing.FakeDocumentRepository
import dev.npn.core.testing.FakeFieldRegistry
import dev.npn.core.testing.FakeMetadataWriter
import dev.npn.core.testing.FakeTagRepository
import dev.npn.core.testing.edit

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
