package dev.npn.app.ui.detail

import dev.npn.app.ui.common.MessageSink
import dev.npn.app.ui.common.UiMessage
import dev.npn.app.ui.common.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/**
 * What a document's screens tell the user that has to outlive them. "Moved to trash" and "Saved
 * changes to …" arrive as the document closes, so the library shell shows them
 * ([WithDraftResumeBanner]) rather than the document's own snackbar, which leaves with it; and their
 * Undo runs in [scope], the app's, since the document's viewmodel is gone by the time it is tapped.
 *
 * One per open library, kept by its [DocumentDrafts]. A viewmodel built without one (a test) hands
 * its own [sink] and scope instead, so the messages are its own.
 */
class DocumentNotices(
    private val scope: CoroutineScope,
    private val sink: MessageSink = MessageSink(),
) {
    /** The messages to show; collected once, by the library shell. */
    val messages: Flow<UiMessage> get() = sink.messages

    fun message(
        text: UiText,
        action: UiMessage.Action? = null,
    ) = sink.message(text, action)

    /**
     * Run [block] in [scope], so leaving the screen that asked for it cannot cancel it; on failure send
     * [failed], a whole sentence with the reason as `%1$s`.
     */
    fun launchReporting(
        failed: StringResource,
        block: suspend () -> Unit,
    ) {
        scope.launch { sink.reporting(failed, block) }
    }
}
