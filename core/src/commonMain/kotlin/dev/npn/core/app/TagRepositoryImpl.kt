package dev.npn.core.app

import dev.npn.core.db.TagDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Straight projection off [TagDao.observeAllWithCounts]. */
class TagRepositoryImpl(private val tagDao: TagDao) : TagRepository {

    override fun observeTags(): Flow<List<TagInfo>> =
        tagDao.observeAllWithCounts().map { rows ->
            rows.map { TagInfo(it.name, it.color, it.icon, it.count) }
        }

    override suspend fun allTagNames(): List<String> =
        tagDao.observeAllWithCounts().first().map { it.name }
}
