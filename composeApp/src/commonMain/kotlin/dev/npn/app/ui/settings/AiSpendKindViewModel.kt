package dev.npn.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.core.ai.AiUsageKind
import dev.npn.core.ai.AiUsageTotals
import dev.npn.core.app.LibrarySession
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One spend window of one kind of work: a line per provider and model, and their total. */
internal data class SpendWindowText(
    val lines: List<UiText> = emptyList(),
    /** Null when the window recorded nothing of this kind. */
    val total: UiText? = null,
)

/** What the spend page of one kind of work shows: its name, then the last 30 days and all time. */
internal data class AiSpendKindState(
    val title: UiText,
    val last30Days: SpendWindowText = SpendWindowText(),
    val allTime: SpendWindowText = SpendWindowText(),
)

/**
 * The spend page of one kind of work ([AiSpendKinds]): this library's ledger on this device,
 * narrowed to that kind, as the same lines and totals the AI settings pane shows for everything.
 */
internal class AiSpendKindViewModel(
    session: LibrarySession,
    kindKey: String,
) : ViewModel() {
    private val kind: AiUsageKind? = AiSpendKinds.parse(kindKey)

    val state: StateFlow<AiSpendKindState> =
        session.aiSpend.summary
            .map { summary ->
                AiSpendKindState(
                    title = SpendText.kindLabel(kind),
                    last30Days = window(summary.last30Days),
                    allTime = window(summary.allTime),
                )
            }.stateIn(viewModelScope, WhileSubscribedTimeout, AiSpendKindState(SpendText.kindLabel(kind)))

    private fun window(lines: List<AiUsageTotals>): SpendWindowText {
        val ofKind = AiSpendKinds.linesOf(lines, kind)
        return if (ofKind.isEmpty()) {
            SpendWindowText()
        } else {
            SpendWindowText(ofKind.map(SpendText::line), SpendText.total(ofKind))
        }
    }
}
