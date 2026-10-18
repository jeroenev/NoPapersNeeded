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
