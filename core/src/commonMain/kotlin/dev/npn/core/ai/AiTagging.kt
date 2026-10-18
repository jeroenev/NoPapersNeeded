package dev.npn.core.ai

/** Bounds for the user-adjustable "max suggested tags per document" setting. */
object AiTagging {
    const val MIN_TAGS_PER_DOC = 1
    const val MAX_TAGS_PER_DOC = 15
    const val DEFAULT_TAGS_PER_DOC = 8
    val RANGE = MIN_TAGS_PER_DOC..MAX_TAGS_PER_DOC

    const val MIN_NEW_TAGS_PER_DOC = 0
    const val MAX_NEW_TAGS_PER_DOC = 10
    const val DEFAULT_NEW_TAGS_PER_DOC = 5

    /** Allowed range for the new-tag cap; 0 means never propose brand-new tags. */
    val NEW_TAGS_RANGE = MIN_NEW_TAGS_PER_DOC..MAX_NEW_TAGS_PER_DOC
}

/**
 * How many tags one reply may add ([max]), and how many of those may be new ([maxNew]): a run's own
 * caps, or the configured ones where it set none, held to their ranges.
 */
internal data class TagCaps(
    val max: Int,
    val maxNew: Int,
) {
    companion object {
        fun of(
            request: AiEditRequest,
            config: AiConfig,
        ) = TagCaps(
            max = (request.maxTags ?: config.maxTagsPerDoc).coerceIn(AiTagging.RANGE),
            maxNew = (request.maxNewTags ?: config.maxNewTagsPerDoc).coerceIn(AiTagging.NEW_TAGS_RANGE),
        )
    }
}
