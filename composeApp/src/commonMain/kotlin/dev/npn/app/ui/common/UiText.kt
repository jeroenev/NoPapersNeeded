package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_unknown_error
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Text a viewmodel hands the UI, which resolves it in the app's language. [Res] and [Plural] name
 * a whole-sentence resource and the arguments for its placeholders, in order; an argument may be a
 * [UiText] itself, resolved first. [Raw] is text that is never translated: the user's own, such as
 * a file name, or a failure's message.
 */
sealed interface UiText {
    data class Res(
        val res: StringResource,
        val args: List<Any> = emptyList(),
    ) : UiText

    /** A plural resource, its form picked by [quantity]. */
    data class Plural(
        val res: PluralStringResource,
        val quantity: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Raw(
        val value: String,
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

fun uiText(
    res: StringResource,
    vararg args: Any,
): UiText = UiText.Res(res, args.toList())

fun uiText(
    res: PluralStringResource,
    quantity: Int,
    vararg args: Any,
): UiText = UiText.Plural(res, quantity, args.toList())

/**
 * This failure's reason as message text, for the "%s" of a "Could not …: %s" sentence: its message,
 * else its class name, else a localized "unknown error".
 */
fun Throwable.reasonText(): UiText =
    message?.takeIf { it.isNotBlank() }?.let(UiText::Raw)
        ?: this::class.simpleName?.let(UiText::Raw)
        ?: uiText(Res.string.common_unknown_error)

/** This text in [environment]'s language, outside a composition (see [SnackbarMessages]). */
suspend fun UiText.resolve(environment: ResourceEnvironment): String =
    when (this) {
        is UiText.Res -> getString(environment, res, *args.resolved { it.resolve(environment) })
        is UiText.Plural -> getPluralString(environment, res, quantity, *args.resolved { it.resolve(environment) })
        is UiText.Raw -> value
    }

/** This text in the composition's language. */
@Composable
fun UiText.resolve(): String =
    when (this) {
        is UiText.Res -> stringResource(res, *args.resolved { it.resolve() })
        is UiText.Plural -> pluralStringResource(res, quantity, *args.resolved { it.resolve() })
        is UiText.Raw -> value
    }

private inline fun List<Any>.resolved(text: (UiText) -> String): Array<Any> =
    map { if (it is UiText) text(it) else it }.toTypedArray()
