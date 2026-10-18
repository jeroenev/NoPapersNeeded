package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Puts [banner] above [content] while there is one. The banner takes the status-bar inset while it
 * is visible ([TopBanner] pads itself below it) and consumes it for [content], so the screens' own
 * top bars are never padded twice; with no banner, they keep drawing behind the status bar exactly
 * as they do without this wrapper.
 */
@Composable
fun WithTopBanner(
    banner: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        if (banner != null) banner()
        Box(
            Modifier
                .weight(1f)
                .then(if (banner == null) Modifier else Modifier.consumeWindowInsets(WindowInsets.statusBars)),
        ) { content() }
    }
}

/**
 * A banner for [WithTopBanner]: [message] and its [actions] on a surface of its own, or it would
 * read as part of the top bar of whatever screen is below. The surface fills behind the status bar;
 * the text and buttons sit below it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TopBanner(
    message: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    actions: @Composable FlowRowScope.() -> Unit,
) {
    Surface(modifier, color = containerColor, contentColor = contentColor) {
        FlowRow(
            Modifier.statusBarsPadding().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(message, modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}
