package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_ai_assistant_empty_answer
import no_papers_needed.composeapp.generated.resources.common_ai_assistant_unreachable
import no_papers_needed.composeapp.generated.resources.common_could_not_queue_edit_run
import no_papers_needed.composeapp.generated.resources.common_could_not_suggest_tags_partial
import no_papers_needed.composeapp.generated.resources.common_document_count
import no_papers_needed.composeapp.generated.resources.common_duplicate_count
import no_papers_needed.composeapp.generated.resources.common_failed_count
import no_papers_needed.composeapp.generated.resources.common_imported_count
import no_papers_needed.composeapp.generated.resources.common_join_items
import no_papers_needed.composeapp.generated.resources.common_no_ai_metadata_suggestions
import no_papers_needed.composeapp.generated.resources.common_no_ai_tag_suggestions
import no_papers_needed.composeapp.generated.resources.common_scan_already_in_library
import no_papers_needed.composeapp.generated.resources.common_scan_capture_failed
import no_papers_needed.composeapp.generated.resources.common_scan_import_failed
import no_papers_needed.composeapp.generated.resources.common_scan_imported
import no_papers_needed.composeapp.generated.resources.common_suggestion_count
import no_papers_needed.composeapp.generated.resources.common_tagging_apply_summary
import no_papers_needed.composeapp.generated.resources.common_tagging_done_empty
import no_papers_needed.composeapp.generated.resources.common_tagging_done_summary
import no_papers_needed.composeapp.generated.resources.common_tagging_scope_count_batch
import no_papers_needed.composeapp.generated.resources.common_unknown_error
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Resource-ready text passed across the ViewModel/UI boundary. New text uses [Res] and [Plural]
 * with whole-sentence resources; [Resource] keys are the older indirection.
 */
sealed interface UiText {
    data class Resource(
        val key: UiTextKey,
        val arguments: List<Any?> = emptyList(),
    ) : UiText

    data class Dynamic(
        val value: String,
    ) : UiText

    /** A string resource; [args] may be nested [UiText]s, resolved first. */
    data class Res(
        val res: StringResource,
        val args: List<Any?> = emptyList(),
    ) : UiText

    /** A plural resource picked by [quantity]; [args] may be nested [UiText]s, resolved first. */
    data class Plural(
        val res: PluralStringResource,
        val quantity: Int,
        val args: List<Any?> = emptyList(),
    ) : UiText
}

/**
 * One-shot message presented by the UI, normally in a snackbar, with an optional [action] such as
 * Undo. See [MessagesViewModel] for how messages are sent and [SnackbarMessages] for how they show.
 */
data class UiMessage(
    val text: UiText,
    val action: Action? = null,
) {
    /** The snackbar's action button: [label], and [onPerform] run when the user taps it. */
    class Action(
        val label: UiText,
        val onPerform: () -> Unit,
    )
}

/** Stable identifiers for messages crossing the ViewModel/UI boundary. */
enum class UiTextKey {
    UNKNOWN_ERROR,
    COULD_NOT_SUGGEST_TAGS_PARTIAL,
    COULD_NOT_QUEUE_EDIT_RUN,
    NO_AI_TAG_SUGGESTIONS,
    NO_AI_METADATA_SUGGESTIONS,
    AI_ASSISTANT_UNREACHABLE,
    AI_ASSISTANT_EMPTY_ANSWER,
    IMPORT_SUMMARY,
    SCAN_IMPORTED,
    SCAN_ALREADY_IN_LIBRARY,
    SCAN_IMPORT_FAILED,
    SCAN_CAPTURE_FAILED,
    TAGGING_SCOPE_COUNT,
    TAGGING_SCOPE_COUNT_BATCH,
    TAGGING_APPLY_SUMMARY,
    TAGGING_DONE_SUMMARY,
    TAGGING_DONE_EMPTY,
}

fun uiText(
    key: UiTextKey,
    vararg arguments: Any?,
): UiText = UiText.Resource(key, arguments.toList())

fun uiText(
    res: StringResource,
    vararg arguments: Any?,
): UiText = UiText.Res(res, arguments.toList())

fun uiText(
    res: PluralStringResource,
    quantity: Int,
    vararg arguments: Any?,
): UiText = UiText.Plural(res, quantity, arguments.toList())

/**
 * This failure's reason as message text, for the "%s" of a "Could not …: %s" sentence: its message,
 * else its class name, else a localized "unknown error".
 */
fun Throwable.reasonText(): UiText =
    message?.takeIf { it.isNotBlank() }?.let(UiText::Dynamic)
        ?: this::class.simpleName?.let(UiText::Dynamic)
        ?: uiText(UiTextKey.UNKNOWN_ERROR)

/**
 * The rendering plan for one [UiTextKey]: which resource to read and which arguments to feed it.
 *
 * Splitting the plan from the rendering is what lets the app and the non-Compose unit tests share
 * a single wording table. [spec] is the only place a key is mapped to a resource; the composable
 * and suspending renderers both consume the result, so neither can drift from the other or from
 * `values/common.xml`. Arguments may themselves be [TextSpec]s, which renderers resolve first.
 */
internal sealed interface TextSpec {
    data class Str(
        val res: StringResource,
        val args: List<Any?> = emptyList(),
    ) : TextSpec

    data class Plural(
        val res: PluralStringResource,
        val quantity: Int,
        val args: List<Any?> = emptyList(),
    ) : TextSpec

    /**
     * Parts joined pairwise with `common_join_items`, used by the import summary.
     * [parts] must not be empty — both renderers fold from the first element.
     */
    data class Join(
        val parts: List<TextSpec>,
    ) : TextSpec

    data class Literal(
        val value: String,
    ) : TextSpec
}

internal fun UiText.spec(): TextSpec =
    when (this) {
        is UiText.Dynamic -> TextSpec.Literal(value)
        is UiText.Resource -> key.spec(arguments)
        is UiText.Res -> TextSpec.Str(res, args.map { it.argumentSpec() })
        is UiText.Plural -> TextSpec.Plural(res, quantity, args.map { it.argumentSpec() })
    }

private fun UiTextKey.spec(args: List<Any?>): TextSpec =
    when (this) {
        UiTextKey.UNKNOWN_ERROR -> {
            TextSpec.Str(Res.string.common_unknown_error)
        }

        UiTextKey.COULD_NOT_SUGGEST_TAGS_PARTIAL -> {
            TextSpec.Plural(
                Res.plurals.common_could_not_suggest_tags_partial,
                args.intAt(1),
                listOf(args.intAt(0), args.intAt(1)),
            )
        }

        UiTextKey.COULD_NOT_QUEUE_EDIT_RUN -> {
            TextSpec.Str(Res.string.common_could_not_queue_edit_run, listOf(args[0].argumentSpec()))
        }

        UiTextKey.NO_AI_TAG_SUGGESTIONS -> {
            TextSpec.Str(Res.string.common_no_ai_tag_suggestions)
        }

        UiTextKey.NO_AI_METADATA_SUGGESTIONS -> {
            TextSpec.Str(Res.string.common_no_ai_metadata_suggestions)
        }

        UiTextKey.AI_ASSISTANT_UNREACHABLE -> {
            TextSpec.Str(Res.string.common_ai_assistant_unreachable, listOf(args[0].argumentSpec()))
        }

        UiTextKey.AI_ASSISTANT_EMPTY_ANSWER -> {
            TextSpec.Str(Res.string.common_ai_assistant_empty_answer)
        }

        UiTextKey.IMPORT_SUMMARY -> {
            importSummarySpec(args)
        }

        UiTextKey.SCAN_IMPORTED -> {
            TextSpec.Str(Res.string.common_scan_imported)
        }

        UiTextKey.SCAN_ALREADY_IN_LIBRARY -> {
            TextSpec.Str(Res.string.common_scan_already_in_library)
        }

        UiTextKey.SCAN_IMPORT_FAILED -> {
            TextSpec.Str(Res.string.common_scan_import_failed, listOf(args[0].argumentSpec()))
        }

        UiTextKey.SCAN_CAPTURE_FAILED -> {
            TextSpec.Str(Res.string.common_scan_capture_failed)
        }

        UiTextKey.TAGGING_SCOPE_COUNT -> {
            documentCountSpec(args.intAt(0))
        }

        UiTextKey.TAGGING_SCOPE_COUNT_BATCH -> {
            TextSpec.Plural(
                Res.plurals.common_tagging_scope_count_batch,
                args.intAt(0),
                listOf(args.intAt(0), args.intAt(1)),
            )
        }

        UiTextKey.TAGGING_APPLY_SUMMARY -> {
            TextSpec.Str(
                Res.string.common_tagging_apply_summary,
                listOf(suggestionCountSpec(args.intAt(0)), documentCountSpec(args.intAt(1))),
            )
        }

        UiTextKey.TAGGING_DONE_SUMMARY -> {
            TextSpec.Str(
                Res.string.common_tagging_done_summary,
                listOf(suggestionCountSpec(args.intAt(0)), documentCountSpec(args.intAt(1))),
            )
        }

        UiTextKey.TAGGING_DONE_EMPTY -> {
            TextSpec.Str(Res.string.common_tagging_done_empty)
        }
    }

private fun importSummarySpec(args: List<Any?>): TextSpec =
    TextSpec.Join(
        buildList {
            val imported = args.intAt(0)
            add(TextSpec.Plural(Res.plurals.common_imported_count, imported, listOf(imported)))
            val duplicates = args.intAt(1)
            if (duplicates > 0) {
                add(TextSpec.Plural(Res.plurals.common_duplicate_count, duplicates, listOf(duplicates)))
            }
            val failed = args.intAt(2)
            if (failed > 0) {
                add(TextSpec.Plural(Res.plurals.common_failed_count, failed, listOf(failed)))
            }
        },
    )

private fun documentCountSpec(count: Int): TextSpec =
    TextSpec.Plural(Res.plurals.common_document_count, count, listOf(count))

private fun suggestionCountSpec(count: Int): TextSpec =
    TextSpec.Plural(Res.plurals.common_suggestion_count, count, listOf(count))

private fun List<Any?>.intAt(index: Int): Int = this[index] as Int

/** Message arguments may be nested [UiText]s; anything else is passed to the formatter as-is. */
private fun Any?.argumentSpec(): Any? =
    when (this) {
        is UiText -> spec()
        null -> ""
        else -> this
    }

/** Resolve text with the active Compose resource locale at the UI boundary. */
@Composable
fun UiText.resolve(): String = spec().render()

@Composable
fun UiMessage.resolve(): String = text.resolve()

@Composable
private fun TextSpec.render(): String =
    when (this) {
        is TextSpec.Literal -> {
            value
        }

        is TextSpec.Str -> {
            stringResource(res, *args.renderAll())
        }

        is TextSpec.Plural -> {
            pluralStringResource(res, quantity, *args.renderAll())
        }

        is TextSpec.Join -> {
            parts.map { it.render() }.reduce { summary, part ->
                stringResource(Res.string.common_join_items, summary, part)
            }
        }
    }

@Composable
private fun List<Any?>.renderAll(): Array<Any> = map { if (it is TextSpec) it.render() else it ?: "" }.toTypedArray()
