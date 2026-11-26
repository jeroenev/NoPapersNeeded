package dev.npn.app.ui.reminders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.npn.app.platform.canShowReminderNotifications
import dev.npn.app.platform.rememberNotificationSettingsAction
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.reminders_notifications_allow
import no_papers_needed.composeapp.generated.resources.reminders_notifications_blocked
import org.jetbrains.compose.resources.stringResource

/**
 * A warning, wherever reminders show or are switched on, while this device would notify but the
 * platform blocks it (Android 13+'s permission, or the app's notifications turned off), with the way
 * to let them through. Read again each time the screen resumes, so it goes once the user comes back
 * from the system settings; nothing polls for it. [notifies] is whether reminders are on here at all.
 */
@Composable
internal fun ReminderPermissionWarning(
    notifies: Boolean,
    modifier: Modifier = Modifier,
) {
    val allow = rememberNotificationSettingsAction() ?: return
    var blocked by remember { mutableStateOf(!canShowReminderNotifications()) }
    LifecycleResumeEffect(Unit) {
        blocked = !canShowReminderNotifications()
        onPauseOrDispose {}
    }
    if (!notifies || !blocked) return
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.NotificationsOff, contentDescription = null)
            Text(
                stringResource(Res.string.reminders_notifications_blocked),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = allow) { Text(stringResource(Res.string.reminders_notifications_allow)) }
        }
    }
}
