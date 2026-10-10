package dev.npn.app.ui.trash

import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.uiText
import dev.npn.core.index.RelinkCandidate
import dev.npn.core.index.RelinkSignal
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_trash_signal_replaced_in_place
import no_papers_needed.composeapp.generated.resources.common_trash_signal_same_folder
import no_papers_needed.composeapp.generated.resources.common_trash_signal_same_name
import no_papers_needed.composeapp.generated.resources.common_trash_signal_same_size
import no_papers_needed.composeapp.generated.resources.common_trash_signal_similar_name

/** The label of one reason a file may be a missing document's. */
internal fun RelinkSignal.label(): UiText =
    uiText(
        when (this) {
            RelinkSignal.REPLACED_IN_PLACE -> Res.string.common_trash_signal_replaced_in_place
            RelinkSignal.SAME_NAME -> Res.string.common_trash_signal_same_name
            RelinkSignal.SIMILAR_NAME -> Res.string.common_trash_signal_similar_name
            RelinkSignal.SAME_SIZE -> Res.string.common_trash_signal_same_size
            RelinkSignal.SAME_FOLDER -> Res.string.common_trash_signal_same_folder
        },
    )

/** A label for each reason this candidate was proposed, each shown as its own item, in a fixed order. */
internal fun RelinkCandidate.signalLabels(): List<UiText> = signals.sorted().map { it.label() }
