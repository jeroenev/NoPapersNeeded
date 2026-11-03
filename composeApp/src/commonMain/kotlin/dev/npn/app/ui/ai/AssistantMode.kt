package dev.npn.app.ui.ai

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_mode_bulk_edit
import no_papers_needed.composeapp.generated.resources.ai_mode_chat
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The assistant's two modes, one shell item between them. */
enum class AssistantMode(
    val label: StringResource,
) {
    /** Ask questions about the library. */
    CHAT(Res.string.ai_mode_chat),

    /** Suggest tags and details for many documents, reviewed before anything is written. */
    BULK_EDIT(Res.string.ai_mode_bulk_edit),
}

/** Slack on top of the measured segment, for rounding and the segments' overlapping borders. */
private val SegmentSlack = 4.dp

/** The smallest the labels shrink to on a screen too narrow for them at their own size. */
private val MinLabelSize = 10.sp

/**
 * The Chat | Bulk edit control at the top of both assistant screens. [onSelect] gets the other mode
 * only; the one shown is not reopened.
 *
 * The segments are sized here rather than by the row, which sizes them from the labels' intrinsic
 * widths without the check mark the selected one gains: a label would wrap, or run past its border
 * once selected. Each segment is as wide as the widest label with its check mark, in whatever
 * language the app shows, so selecting either moves nothing. Where the screen is narrower than
 * that, the row takes the width there is, the check mark gives its room to the labels (the selected
 * segment's fill still tells it apart), and the labels shrink to fit on one line.
 */
@Composable
internal fun AssistantModeSwitch(
    selected: AssistantMode,
    onSelect: (AssistantMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = AssistantMode.entries.map { stringResource(it.label) }
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val segment: Dp =
        remember(labels, style, density, direction) {
            val widest = labels.maxOf { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }
            val padding = SegmentedButtonDefaults.ContentPadding
            with(density) { widest.toDp() } +
                padding.calculateStartPadding(direction) +
                padding.calculateEndPadding(direction) +
                SegmentedButtonDefaults.IconSize +
                ButtonDefaults.IconSpacing +
                SegmentedButtonDefaults.BorderWidth * 2 +
                SegmentSlack
        }
    BoxWithConstraints(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val squeezed = segment * labels.size > maxWidth
        SingleChoiceSegmentedButtonRow(Modifier.width(if (squeezed) maxWidth else segment * labels.size)) {
            AssistantMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = mode == selected,
                    onClick = { if (mode != selected) onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, AssistantMode.entries.size),
                    icon = { if (!squeezed) SegmentedButtonDefaults.Icon(active = mode == selected) },
                ) {
                    Text(
                        labels[index],
                        maxLines = 1,
                        softWrap = false,
                        autoSize = TextAutoSize.StepBased(minFontSize = MinLabelSize, maxFontSize = style.fontSize),
                    )
                }
            }
        }
    }
}
