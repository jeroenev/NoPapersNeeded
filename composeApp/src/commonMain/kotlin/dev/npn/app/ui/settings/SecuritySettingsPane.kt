package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.npn.app.platform.LocalAppLockAuthenticator
import dev.npn.app.platform.appLockRecoveryFile
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.Dropdown
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.common.SwitchRow
import dev.npn.core.security.LockTimeout
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_save
import no_papers_needed.composeapp.generated.resources.security_app_lock
import no_papers_needed.composeapp.generated.resources.security_app_lock_help
import no_papers_needed.composeapp.generated.resources.security_change_pin
import no_papers_needed.composeapp.generated.resources.security_device_help
import no_papers_needed.composeapp.generated.resources.security_lock_after
import no_papers_needed.composeapp.generated.resources.security_lock_fifteen_minutes
import no_papers_needed.composeapp.generated.resources.security_lock_five_minutes
import no_papers_needed.composeapp.generated.resources.security_lock_immediately
import no_papers_needed.composeapp.generated.resources.security_lock_one_minute
import no_papers_needed.composeapp.generated.resources.security_not_encrypted
import no_papers_needed.composeapp.generated.resources.security_pin
import no_papers_needed.composeapp.generated.resources.security_pin_confirm
import no_papers_needed.composeapp.generated.resources.security_pin_mismatch
import no_papers_needed.composeapp.generated.resources.security_pin_too_short
import no_papers_needed.composeapp.generated.resources.security_recovery_android
import no_papers_needed.composeapp.generated.resources.security_recovery_desktop
import no_papers_needed.composeapp.generated.resources.security_recovery_title
import no_papers_needed.composeapp.generated.resources.security_set_pin
import no_papers_needed.composeapp.generated.resources.security_title
import org.jetbrains.compose.resources.stringResource

/** The shortest unlock secret accepted; long enough to stop a shoulder-surfed guess from working. */
internal const val MIN_APP_LOCK_SECRET_LENGTH = 4

/**
 * The app lock's settings. It is access control, not encryption: the copy says so in as many words,
 * so nobody mistakes it for protecting the library folder itself.
 */
@Composable
internal fun SecuritySettingsPane(
    state: SettingsPaneState,
    actions: SettingsPaneActions,
) {
    val lock = state.settings.snapshot.appLock
    val deviceAuth = LocalAppLockAuthenticator.current
    val deviceAuthAvailable = remember(deviceAuth) { deviceAuth?.isAvailable() == true }
    val recoveryFile = appLockRecoveryFile()

    SettingsSection(title = stringResource(Res.string.security_title)) {
        SwitchRow(
            label = stringResource(Res.string.security_app_lock),
            help = stringResource(Res.string.security_app_lock_help),
            checked = lock.enabled,
            onCheckedChange = { enabled ->
                if (enabled) {
                    actions.openSecurityDialog(SecurityDialog.SetPin)
                } else {
                    actions.setAppLockEnabled(false)
                }
            },
        )
        if (lock.enabled) {
            SettingsActionRow(
                label = stringResource(Res.string.security_change_pin),
                onClick = { actions.openSecurityDialog(SecurityDialog.ChangePin) },
            )
            Dropdown(
                selected = lockTimeoutLabel(lock.timeout),
                options = LockTimeout.entries,
                optionLabel = { lockTimeoutLabel(it) },
                label = stringResource(Res.string.security_lock_after),
                onSelect = actions::setAppLockTimeout,
            )
            if (deviceAuthAvailable) HelpText(stringResource(Res.string.security_device_help))
        }
        HelpText(stringResource(Res.string.security_not_encrypted))
    }

    SettingsSection(title = stringResource(Res.string.security_recovery_title)) {
        HelpText(
            if (recoveryFile != null) {
                stringResource(Res.string.security_recovery_desktop, recoveryFile)
            } else {
                stringResource(Res.string.security_recovery_android)
            },
        )
    }

    state.securityDialog?.let { dialog ->
        SecurityDialogContent(
            dialog = dialog,
            onDismiss = { actions.openSecurityDialog(null) },
            onSave = actions::setAppLockPin,
        )
    }
}

@Composable
private fun lockTimeoutLabel(timeout: LockTimeout): String =
    when (timeout) {
        LockTimeout.IMMEDIATELY -> stringResource(Res.string.security_lock_immediately)
        LockTimeout.ONE_MINUTE -> stringResource(Res.string.security_lock_one_minute)
        LockTimeout.FIVE_MINUTES -> stringResource(Res.string.security_lock_five_minutes)
        LockTimeout.FIFTEEN_MINUTES -> stringResource(Res.string.security_lock_fifteen_minutes)
    }

/** The set-or-change-PIN dialog. Its buffer is plain [remember]: a secret never goes in saved state. */
@Composable
private fun SecurityDialogContent(
    dialog: SecurityDialog,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var pin by remember(dialog) { mutableStateOf("") }
    var confirmation by remember(dialog) { mutableStateOf("") }
    var showErrors by rememberSaveable(dialog) { mutableStateOf(false) }

    val tooShort = showErrors && pin.length < MIN_APP_LOCK_SECRET_LENGTH
    val mismatch = showErrors && pin.length >= MIN_APP_LOCK_SECRET_LENGTH && pin != confirmation

    ConfirmDialog(
        title =
            stringResource(
                if (dialog == SecurityDialog.ChangePin) Res.string.security_change_pin else Res.string.security_set_pin,
            ),
        onConfirm = {
            if (pin.length < MIN_APP_LOCK_SECRET_LENGTH || pin != confirmation) {
                showErrors = true
            } else {
                onSave(pin)
            }
        },
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.common_save),
        confirmEnabled = pin.isNotEmpty() && confirmation.isNotEmpty(),
    ) {
        OutlinedTextField(
            value = pin,
            onValueChange = {
                pin = it
                showErrors = false
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.security_pin)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = tooShort,
        )
        if (tooShort) {
            Text(
                stringResource(Res.string.security_pin_too_short),
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
            )
        }
        OutlinedTextField(
            value = confirmation,
            onValueChange = {
                confirmation = it
                showErrors = false
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(Res.string.security_pin_confirm)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError = mismatch,
        )
        if (mismatch) {
            Text(
                stringResource(Res.string.security_pin_mismatch),
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
            )
        }
    }
}
