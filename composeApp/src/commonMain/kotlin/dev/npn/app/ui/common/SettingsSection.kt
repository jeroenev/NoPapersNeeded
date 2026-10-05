package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** Vertical gap between the controls inside one [SettingsSection]. */
private val SectionSpacing = 8.dp

/**
 * One titled cluster of related controls. A settings pane is a run of these, and so is the AI-edit
 * setup form, which sets a smaller [headingStyle] because its sections sit closer together.
 */
@Composable
fun SettingsSection(
    title: String?,
    modifier: Modifier = Modifier,
    headingStyle: TextStyle = MaterialTheme.typography.titleMedium,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SectionSpacing)) {
        if (title != null) SectionHeading(title, style = headingStyle)
        content()
    }
}

/**
 * A section title. Marked as a heading so a screen reader can jump section to section down a long
 * form instead of walking every control.
 */
@Composable
fun SectionHeading(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    Text(
        text = text,
        modifier = modifier.semantics { heading() },
        style = style,
        color = MaterialTheme.colorScheme.primary,
    )
}
