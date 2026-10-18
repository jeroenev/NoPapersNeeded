package dev.npn.app.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource

/**
 * What a bulk action tells the user when some of its items fail: why, when one failed ([one], a whole
 * sentence with the reason as `%1$s`), and how many of how many, when several did ([some], counted
 * in the total, with the failed count as `%1$d` and the total as `%2$d`).
 */
class BulkFailure(
    val one: StringResource,
    val some: PluralStringResource,
)

/**
 * Base for view models that surface one-shot user-facing messages, shown by [SnackbarMessages].
 *
 * Messages go through one unbounded channel: each is delivered exactly once, in the order sent, to
 * the one collector the screen runs. A message sent while no screen collects (between lifecycles,
 * during navigation) waits for the next collector instead of being dropped, however many pile up,
 * and two sent together both show, one after the other.
 *
 * Write operations are journal appends and file moves, so they can genuinely fail (SAF I/O errors,
 * full disk, permissions). Fire-and-forget writes must go through [launchReporting] so a failure
 * is reported to the user instead of silently swallowed, or crashing the app.
 */
abstract class MessagesViewModel : ViewModel() {
    private val channel = Channel<UiMessage>(Channel.UNLIMITED)

    /** Where a bulk action's failures are logged, beside what the user is told; the session's log. */
    protected open val log: NpnLog get() = NpnLog.None

    /** The messages to show; collect it from one place only, normally [SnackbarMessages]. */
    val messages: Flow<UiMessage> = channel.receiveAsFlow()

    /** Send a one-shot user-facing message, optionally with an [action] such as Undo. */
    protected fun message(
        text: UiText,
        action: UiMessage.Action? = null,
    ) {
        channel.trySend(UiMessage(text, action))
    }

    /**
     * Run a fire-and-forget write in [viewModelScope]; on failure send [failed], a whole sentence
     * with the failure's reason as `%1$s`, such as "Could not split the document: %1$s".
     * Cancellation is rethrown so it is not misreported as a failure.
     */
    protected fun launchReporting(
        failed: StringResource,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch { reporting(failed, block) }
    }

    /**
     * [launchReporting]'s failure handling, for a write inside a coroutine the caller launched.
     * Returns whether [block] completed, for a caller that keeps what it wrote until it has landed.
     */
    protected suspend fun reporting(
        failed: StringResource,
        block: suspend () -> Unit,
    ): Boolean =
        runCatchingCancellable { block() }
            .onFailure { message(uiText(failed, it.reasonText())) }
            .isSuccess

    /**
     * Run [op] for each of [items] in turn, carrying on past the ones that fail, and report the
     * failures as [failed] says: the reason when one failed, the count when several did. Each
     * failure's cause goes to [log]. Returns the items that failed, for a caller that keeps them
     * (selected, say) for a retry.
     */
    protected suspend fun <T> forEachReporting(
        items: Collection<T>,
        failed: BulkFailure,
        op: suspend (T) -> Unit,
    ): Set<T> {
        val failures = LinkedHashMap<T, Throwable>()
        for (item in items) {
            runCatchingCancellable { op(item) }.onFailure { failures[item] = it }
        }
        failures.forEach { (item, cause) -> log.warn("A bulk action failed for $item", cause) }
        when (failures.size) {
            0 -> Unit
            1 -> message(uiText(failed.one, failures.values.single().reasonText()))
            else -> message(uiText(failed.some, items.size, failures.size, items.size))
        }
        return failures.keys
    }

    /**
     * [forEachReporting] for a core bulk call that runs [total] items itself and returns how many
     * failed, each in the session log: reports that count with [someFailed], a throw counting every
     * item as failed. Returns the count.
     */
    protected suspend fun countReporting(
        total: Int,
        someFailed: PluralStringResource,
        op: suspend () -> Int,
    ): Int {
        val failed =
            runCatchingCancellable { op() }.getOrElse {
                log.warn("A bulk action failed", it)
                total
            }
        if (failed > 0) message(uiText(someFailed, total, failed, total))
        return failed
    }
}
