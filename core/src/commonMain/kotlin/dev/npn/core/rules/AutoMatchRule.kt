package dev.npn.core.rules

import kotlinx.serialization.Serializable

/**
 * User-defined rule that assigns tags, a correspondent and/or a document type by matching
 * document text against a pattern.
 *
 * Rules are local to the device (stored in Room, not synced through the journal). Their
 * actions are applied as ordinary journal ops via [JournalCoordinator].
 */
@Serializable
data class AutoMatchRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val field: RuleField = RuleField.CONTENT,
    val algorithm: MatchAlgorithm = MatchAlgorithm.ANY_WORD,
    val pattern: String,
    val tags: List<String> = emptyList(),
    val correspondent: String? = null,
    val docType: String? = null,
    val position: Int = 0,
)

/** Which part of the document a rule matches against. */
@Serializable
enum class RuleField {
    CONTENT,
    TITLE,
    FILE_NAME,
}

/** Matching algorithm for [AutoMatchRule.pattern]. */
@Serializable
enum class MatchAlgorithm {
    EXACT,
    ANY_WORD,
    ALL_WORDS,
    REGEX,
    FUZZY,
}
