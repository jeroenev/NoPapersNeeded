package dev.npn.core.testing

import dev.npn.core.app.TagInfo
import dev.npn.core.app.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** The library's tags: [tags] for observers (push new emissions), [tagNames] as the vocabulary. */
class FakeTagRepository(
    var tagNames: List<String> = emptyList(),
) : TagRepository {
    val tags = MutableStateFlow<List<TagInfo>>(emptyList())

    override fun observeTags(): Flow<List<TagInfo>> = tags

    override suspend fun allTagNames(): List<String> = tagNames
}
