package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_action_add_tag
import no_papers_needed.composeapp.generated.resources.common_action_change_ocr_engine
import no_papers_needed.composeapp.generated.resources.common_action_create_tag
import no_papers_needed.composeapp.generated.resources.common_action_delete_document
import no_papers_needed.composeapp.generated.resources.common_action_delete_rule
import no_papers_needed.composeapp.generated.resources.common_action_delete_tag
import no_papers_needed.composeapp.generated.resources.common_action_merge_tags
import no_papers_needed.composeapp.generated.resources.common_action_move_to_trash
import no_papers_needed.composeapp.generated.resources.common_action_relink_document
import no_papers_needed.composeapp.generated.resources.common_action_remove_duplicate
import no_papers_needed.composeapp.generated.resources.common_action_remove_field
import no_papers_needed.composeapp.generated.resources.common_action_remove_tag
import no_papers_needed.composeapp.generated.resources.common_action_rename_file
import no_papers_needed.composeapp.generated.resources.common_action_rename_tag
import no_papers_needed.composeapp.generated.resources.common_action_rerun_ocr
import no_papers_needed.composeapp.generated.resources.common_action_restore_document
import no_papers_needed.composeapp.generated.resources.common_action_save_correspondent
import no_papers_needed.composeapp.generated.resources.common_action_save_date
import no_papers_needed.composeapp.generated.resources.common_action_save_document_type
import no_papers_needed.composeapp.generated.resources.common_action_save_field
import no_papers_needed.composeapp.generated.resources.common_action_save_rule
import no_papers_needed.composeapp.generated.resources.common_action_save_title
import no_papers_needed.composeapp.generated.resources.common_action_suggest_metadata
import no_papers_needed.composeapp.generated.resources.common_action_suggest_tags
import no_papers_needed.composeapp.generated.resources.common_action_tag
import no_papers_needed.composeapp.generated.resources.common_action_trash
import no_papers_needed.composeapp.generated.resources.common_action_update
import no_papers_needed.composeapp.generated.resources.common_action_update_review_state
import no_papers_needed.composeapp.generated.resources.common_action_update_rule
import no_papers_needed.composeapp.generated.resources.common_action_update_tag_style
import no_papers_needed.composeapp.generated.resources.common_ai_assistant_empty_answer
import no_papers_needed.composeapp.generated.resources.common_ai_assistant_unreachable
import no_papers_needed.composeapp.generated.resources.common_could_not_action
import no_papers_needed.composeapp.generated.resources.common_could_not_documents
import no_papers_needed.composeapp.generated.resources.common_could_not_open_library
import no_papers_needed.composeapp.generated.resources.common_could_not_partial_documents
import no_papers_needed.composeapp.generated.resources.common_could_not_suggest_tags_partial
import no_papers_needed.composeapp.generated.resources.common_document_count
import no_papers_needed.composeapp.generated.resources.common_duplicate_count
import no_papers_needed.composeapp.generated.resources.common_failed_count
import no_papers_needed.composeapp.generated.resources.common_imported_count
import no_papers_needed.composeapp.generated.resources.common_join_items
import no_papers_needed.composeapp.generated.resources.common_no_ai_metadata_suggestions
import no_papers_needed.composeapp.generated.resources.common_no_ai_tag_suggestions
import no_papers_needed.composeapp.generated.resources.common_scan_already_in_library
import no_papers_needed.composeapp.generated.resources.common_scan_import_failed
import no_papers_needed.composeapp.generated.resources.common_scan_imported
import no_papers_needed.composeapp.generated.resources.common_suggestion_count
import no_papers_needed.composeapp.generated.resources.common_tagging_apply_summary
import no_papers_needed.composeapp.generated.resources.common_tagging_done_empty
import no_papers_needed.composeapp.generated.resources.common_tagging_done_summary
import no_papers_needed.composeapp.generated.resources.common_tagging_scope_count_batch
import no_papers_needed.composeapp.generated.resources.common_unknown_error
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Resource-ready text passed across the ViewModel/UI boundary. */
sealed interface UiText {
    data class Resource(
        val key: UiTextKey,
        val arguments: List<Any?> = emptyList(),
    ) : UiText

    data class Dynamic(
        val value: String,
    ) : UiText
}

/** One-shot message presented by the UI, normally in a snackbar. */
data class UiMessage(
    val text: UiText,
)

/** Stable identifiers for messages crossing the ViewModel/UI boundary. */
enum class UiTextKey {
    COULD_NOT_OPEN_LIBRARY,
    UNKNOWN_ERROR,
    COULD_NOT_ACTION,
    COULD_NOT_DOCUMENTS,
    COULD_NOT_PARTIAL_DOCUMENTS,
    COULD_NOT_SUGGEST_TAGS_PARTIAL,
    NO_AI_TAG_SUGGESTIONS,
    NO_AI_METADATA_SUGGESTIONS,
    AI_ASSISTANT_UNREACHABLE,
    AI_ASSISTANT_EMPTY_ANSWER,
    IMPORT_SUMMARY,
    SCAN_IMPORTED,
    SCAN_ALREADY_IN_LIBRARY,
    SCAN_IMPORT_FAILED,
    TAGGING_SCOPE_COUNT,
    TAGGING_SCOPE_COUNT_BATCH,
    TAGGING_APPLY_SUMMARY,
    TAGGING_DONE_SUMMARY,
    TAGGING_DONE_EMPTY,
}

/** Typed action used by the generic write-failure message. */
enum class UiAction {
    SAVE_TITLE,
    SAVE_CORRESPONDENT,
    SAVE_DOCUMENT_TYPE,
    SAVE_DATE,
    ADD_TAG,
    REMOVE_TAG,
    SAVE_FIELD,
    REMOVE_FIELD,
    UPDATE_REVIEW_STATE,
    MOVE_TO_TRASH,
    RENAME_FILE,
    RERUN_OCR,
    CHANGE_OCR_ENGINE,
    CREATE_TAG,
    RENAME_TAG,
    MERGE_TAGS,
    DELETE_TAG,
    UPDATE_TAG_STYLE,
    RESTORE_DOCUMENT,
    DELETE_DOCUMENT,
    REMOVE_DUPLICATE,
    RELINK_DOCUMENT,
    SAVE_RULE,
    DELETE_RULE,
    UPDATE_RULE,
    UPDATE,
    TRASH,
    SUGGEST_TAGS,
    SUGGEST_METADATA,
    TAG,
}

fun uiText(
    key: UiTextKey,
    vararg arguments: Any?,
): UiText = UiText.Resource(key, arguments.toList())

/** Resolve text with the active Compose resource locale at the UI boundary. */
@Composable
fun UiText.resolve(): String =
    when (this) {
        is UiText.Dynamic -> value
        is UiText.Resource -> key.resolve(arguments)
    }

@Composable
fun UiMessage.resolve(): String = text.resolve()

@Composable
private fun UiTextKey.resolve(args: List<Any?>): String =
    when (this) {
        UiTextKey.COULD_NOT_OPEN_LIBRARY -> {
            stringResource(Res.string.common_could_not_open_library)
        }

        UiTextKey.UNKNOWN_ERROR -> {
            stringResource(Res.string.common_unknown_error)
        }

        UiTextKey.COULD_NOT_ACTION,
        UiTextKey.COULD_NOT_DOCUMENTS,
        UiTextKey.COULD_NOT_PARTIAL_DOCUMENTS,
        -> {
            writeFailure(args)
        }

        UiTextKey.COULD_NOT_SUGGEST_TAGS_PARTIAL -> {
            pluralStringResource(
                Res.plurals.common_could_not_suggest_tags_partial,
                args.intAt(1),
                args.intAt(0),
                args.intAt(1),
            )
        }

        UiTextKey.NO_AI_TAG_SUGGESTIONS -> {
            stringResource(Res.string.common_no_ai_tag_suggestions)
        }

        UiTextKey.NO_AI_METADATA_SUGGESTIONS -> {
            stringResource(Res.string.common_no_ai_metadata_suggestions)
        }

        UiTextKey.AI_ASSISTANT_UNREACHABLE -> {
            stringResource(Res.string.common_ai_assistant_unreachable, args[0].displayArgument())
        }

        UiTextKey.AI_ASSISTANT_EMPTY_ANSWER -> {
            stringResource(Res.string.common_ai_assistant_empty_answer)
        }

        UiTextKey.IMPORT_SUMMARY -> {
            importSummary(args)
        }

        UiTextKey.SCAN_IMPORTED -> {
            stringResource(Res.string.common_scan_imported)
        }

        UiTextKey.SCAN_ALREADY_IN_LIBRARY -> {
            stringResource(Res.string.common_scan_already_in_library)
        }

        UiTextKey.SCAN_IMPORT_FAILED -> {
            stringResource(Res.string.common_scan_import_failed, args[0].displayArgument())
        }

        UiTextKey.TAGGING_SCOPE_COUNT -> {
            documentCount(args.intAt(0))
        }

        UiTextKey.TAGGING_SCOPE_COUNT_BATCH -> {
            pluralStringResource(
                Res.plurals.common_tagging_scope_count_batch,
                args.intAt(0),
                args.intAt(0),
                args.intAt(1),
            )
        }

        UiTextKey.TAGGING_APPLY_SUMMARY -> {
            stringResource(
                Res.string.common_tagging_apply_summary,
                suggestionCount(args.intAt(0)),
                documentCount(args.intAt(1)),
            )
        }

        UiTextKey.TAGGING_DONE_SUMMARY -> {
            stringResource(
                Res.string.common_tagging_done_summary,
                suggestionCount(args.intAt(0)),
                documentCount(args.intAt(1)),
            )
        }

        UiTextKey.TAGGING_DONE_EMPTY -> {
            stringResource(Res.string.common_tagging_done_empty)
        }
    }

@Composable
private fun UiTextKey.writeFailure(args: List<Any?>): String {
    val action = actionTextResource(args[0] as UiAction)
    return when (this) {
        UiTextKey.COULD_NOT_ACTION -> {
            stringResource(Res.string.common_could_not_action, action, args[1].displayArgument())
        }

        UiTextKey.COULD_NOT_DOCUMENTS -> {
            pluralStringResource(
                Res.plurals.common_could_not_documents,
                args.intAt(1),
                action,
                args.intAt(1),
            )
        }

        UiTextKey.COULD_NOT_PARTIAL_DOCUMENTS -> {
            pluralStringResource(
                Res.plurals.common_could_not_partial_documents,
                args.intAt(2),
                action,
                args.intAt(1),
                args.intAt(2),
            )
        }

        else -> {
            error("Not a write-failure text key: $this")
        }
    }
}

@Composable
private fun importSummary(args: List<Any?>): String {
    val parts =
        buildList {
            val imported = args.intAt(0)
            add(pluralStringResource(Res.plurals.common_imported_count, imported, imported))
            val duplicates = args.intAt(1)
            if (duplicates > 0) add(pluralStringResource(Res.plurals.common_duplicate_count, duplicates, duplicates))
            val failed = args.intAt(2)
            if (failed > 0) add(pluralStringResource(Res.plurals.common_failed_count, failed, failed))
        }
    return parts.reduce { summary, part -> stringResource(Res.string.common_join_items, summary, part) }
}

@Composable
private fun documentCount(count: Int): String = pluralStringResource(Res.plurals.common_document_count, count, count)

@Composable
private fun suggestionCount(count: Int): String =
    pluralStringResource(Res.plurals.common_suggestion_count, count, count)

@Composable
private fun actionTextResource(action: UiAction): String = stringResource(action.stringResource)

private val UiAction.stringResource: StringResource
    get() =
        when (this) {
            UiAction.SAVE_TITLE -> Res.string.common_action_save_title
            UiAction.SAVE_CORRESPONDENT -> Res.string.common_action_save_correspondent
            UiAction.SAVE_DOCUMENT_TYPE -> Res.string.common_action_save_document_type
            UiAction.SAVE_DATE -> Res.string.common_action_save_date
            UiAction.ADD_TAG -> Res.string.common_action_add_tag
            UiAction.REMOVE_TAG -> Res.string.common_action_remove_tag
            UiAction.SAVE_FIELD -> Res.string.common_action_save_field
            UiAction.REMOVE_FIELD -> Res.string.common_action_remove_field
            UiAction.UPDATE_REVIEW_STATE -> Res.string.common_action_update_review_state
            UiAction.MOVE_TO_TRASH -> Res.string.common_action_move_to_trash
            UiAction.RENAME_FILE -> Res.string.common_action_rename_file
            UiAction.RERUN_OCR -> Res.string.common_action_rerun_ocr
            UiAction.CHANGE_OCR_ENGINE -> Res.string.common_action_change_ocr_engine
            UiAction.CREATE_TAG -> Res.string.common_action_create_tag
            UiAction.RENAME_TAG -> Res.string.common_action_rename_tag
            UiAction.MERGE_TAGS -> Res.string.common_action_merge_tags
            UiAction.DELETE_TAG -> Res.string.common_action_delete_tag
            UiAction.UPDATE_TAG_STYLE -> Res.string.common_action_update_tag_style
            UiAction.RESTORE_DOCUMENT -> Res.string.common_action_restore_document
            UiAction.DELETE_DOCUMENT -> Res.string.common_action_delete_document
            UiAction.REMOVE_DUPLICATE -> Res.string.common_action_remove_duplicate
            UiAction.RELINK_DOCUMENT -> Res.string.common_action_relink_document
            UiAction.SAVE_RULE -> Res.string.common_action_save_rule
            UiAction.DELETE_RULE -> Res.string.common_action_delete_rule
            UiAction.UPDATE_RULE -> Res.string.common_action_update_rule
            UiAction.UPDATE -> Res.string.common_action_update
            UiAction.TRASH -> Res.string.common_action_trash
            UiAction.SUGGEST_TAGS -> Res.string.common_action_suggest_tags
            UiAction.SUGGEST_METADATA -> Res.string.common_action_suggest_metadata
            UiAction.TAG -> Res.string.common_action_tag
        }

private fun List<Any?>.intAt(index: Int): Int = this[index] as Int

@Composable
private fun Any?.displayArgument(): String =
    when (this) {
        is UiText.Dynamic -> value
        is UiText.Resource -> resolve()
        null -> ""
        else -> toString()
    }

/**
 * Deterministic renderer retained for non-Compose unit tests.
 *
 * Test-only: it hardcodes the English wording of [UiTextKey]s so assertions stay deterministic
 * without a resource environment. Keep these strings in sync with `values/common.xml`, which is
 * what the app actually renders (the two can drift without failing any automated check).
 */
fun UiText.resolveEnglish(): String =
    when (this) {
        is UiText.Dynamic -> value
        is UiText.Resource -> key.resolveEnglish(arguments)
    }

fun UiMessage.resolveEnglish(): String = text.resolveEnglish()

private fun UiTextKey.resolveEnglish(args: List<Any?>): String =
    when (this) {
        UiTextKey.COULD_NOT_OPEN_LIBRARY -> {
            "Could not open the library."
        }

        UiTextKey.UNKNOWN_ERROR -> {
            "unknown error"
        }

        UiTextKey.COULD_NOT_ACTION -> {
            "Could not ${actionText(args[0])}: ${args[1].englishArgument()}"
        }

        UiTextKey.COULD_NOT_DOCUMENTS -> {
            "Could not ${actionText(args[0])} ${args[1]} document(s)"
        }

        UiTextKey.COULD_NOT_PARTIAL_DOCUMENTS -> {
            "Could not ${actionText(args[0])} ${args[1]} of ${args[2]} document(s)"
        }

        UiTextKey.COULD_NOT_SUGGEST_TAGS_PARTIAL -> {
            "Could not get suggestions for ${args[0]} of ${args[1]} document(s)"
        }

        UiTextKey.NO_AI_TAG_SUGGESTIONS -> {
            "No suggestions - document may not be OCRed yet"
        }

        UiTextKey.NO_AI_METADATA_SUGGESTIONS -> {
            "No suggestions - document may not be OCRed yet"
        }

        UiTextKey.AI_ASSISTANT_UNREACHABLE -> {
            "Could not reach the AI assistant: ${args[0].englishArgument()}"
        }

        UiTextKey.AI_ASSISTANT_EMPTY_ANSWER -> {
            "The assistant returned an empty answer."
        }

        UiTextKey.IMPORT_SUMMARY -> {
            importSummaryEnglish(args)
        }

        UiTextKey.SCAN_IMPORTED -> {
            "Scan imported"
        }

        UiTextKey.SCAN_ALREADY_IN_LIBRARY -> {
            "Scan already in library"
        }

        UiTextKey.SCAN_IMPORT_FAILED -> {
            "Scan import failed: ${args[0].englishArgument()}"
        }

        UiTextKey.TAGGING_SCOPE_COUNT -> {
            "${args[0]} ${plural(args[0] as Int, "document")}"
        }

        UiTextKey.TAGGING_SCOPE_COUNT_BATCH -> {
            "${args[0]} ${plural(args[0] as Int, "document")} - the first ${args[1]} run in this batch"
        }

        UiTextKey.TAGGING_APPLY_SUMMARY -> {
            "Apply ${args[0]} ${plural(args[0] as Int, "suggestion")} across " +
                "${args[1]} ${plural(args[1] as Int, "document")}"
        }

        UiTextKey.TAGGING_DONE_SUMMARY -> {
            "Applied ${args[0]} ${plural(args[0] as Int, "suggestion")} across " +
                "${args[1]} ${plural(args[1] as Int, "document")}"
        }

        UiTextKey.TAGGING_DONE_EMPTY -> {
            "No documents to tag"
        }
    }

private fun importSummaryEnglish(args: List<Any?>): String =
    buildString {
        append("${args[0]} imported")
        if (args[1] as Int > 0) append(", ${args[1]} duplicate")
        if (args[2] as Int > 0) append(", ${args[2]} failed")
    }

private fun Any?.englishArgument(): String =
    when (this) {
        is UiText -> resolveEnglish()
        null -> ""
        else -> toString()
    }

private fun actionText(value: Any?): String =
    when (value as UiAction) {
        UiAction.SAVE_TITLE -> "save title"
        UiAction.SAVE_CORRESPONDENT -> "save correspondent"
        UiAction.SAVE_DOCUMENT_TYPE -> "save document type"
        UiAction.SAVE_DATE -> "save date"
        UiAction.ADD_TAG -> "add tag"
        UiAction.REMOVE_TAG -> "remove tag"
        UiAction.SAVE_FIELD -> "save field"
        UiAction.REMOVE_FIELD -> "remove field"
        UiAction.UPDATE_REVIEW_STATE -> "update review state"
        UiAction.MOVE_TO_TRASH -> "move to trash"
        UiAction.RENAME_FILE -> "rename file"
        UiAction.RERUN_OCR -> "re-run OCR"
        UiAction.CHANGE_OCR_ENGINE -> "change OCR engine"
        UiAction.CREATE_TAG -> "create tag"
        UiAction.RENAME_TAG -> "rename tag"
        UiAction.MERGE_TAGS -> "merge tags"
        UiAction.DELETE_TAG -> "delete tag"
        UiAction.UPDATE_TAG_STYLE -> "update tag style"
        UiAction.RESTORE_DOCUMENT -> "restore document"
        UiAction.DELETE_DOCUMENT -> "delete document"
        UiAction.REMOVE_DUPLICATE -> "remove duplicate"
        UiAction.RELINK_DOCUMENT -> "relink document"
        UiAction.SAVE_RULE -> "save rule"
        UiAction.DELETE_RULE -> "delete rule"
        UiAction.UPDATE_RULE -> "update rule"
        UiAction.UPDATE -> "update"
        UiAction.TRASH -> "trash"
        UiAction.SUGGEST_TAGS -> "suggest tags"
        UiAction.SUGGEST_METADATA -> "suggest details"
        UiAction.TAG -> "tag"
    }
