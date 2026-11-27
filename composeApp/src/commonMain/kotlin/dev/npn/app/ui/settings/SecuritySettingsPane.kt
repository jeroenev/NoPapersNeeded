package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.npn.app.platform.LocalAppLockAuthenticator
import dev.npn.app.platform.appLockRecoveryFile
import dev.npn.app.platform.usesOnScreenKeyboard
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.Dropdown
import dev.npn.app.ui.common.FocusOnEntry
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.common.SwitchRow
import dev.npn.app.ui.lock.ErrorText
import dev.npn.app.ui.lock.secretKeyboard
import dev.npn.core.security.LockTimeout
import dev.npn.core.security.UnlockResult
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_confirm
import no_papers_needed.composeapp.generated.resources.common_save
import no_papers_needed.composeapp.generated.resources.security_app_lock
import no_papers_needed.composeapp.generated.resources.security_app_lock_help
import no_papers_needed.composeapp.generated.resources.security_change_pin
import no_papers_needed.composeapp.generated.resources.security_confirm_current_help
import no_papers_needed.composeapp.generated.resources.security_confirm_current_title
import no_papers_needed.composeapp.generated.resources.security_confirm_device
import no_papers_needed.composeapp.generated.resources.security_device_help
import no_papers_needed.composeapp.generated.resources.security_device_prompt_subtitle
import no_papers_needed.composeapp.generated.resources.security_lock_after
import no_papers_needed.composeapp.generated.resources.security_lock_fifteen_minutes
import no_papers_needed.composeapp.generated.resources.security_lock_five_minutes
import no_papers_needed.composeapp.generated.resources.security_lock_immediately
import no_papers_needed.composeapp.generated.resources.security_lock_now
import no_papers_needed.composeapp.generated.resources.security_lock_one_minute
import no_papers_needed.composeapp.generated.resources.security_not_encrypted
import no_papers_needed.composeapp.generated.resources.security_pin
import no_papers_needed.composeapp.generated.resources.security_pin_confirm
import no_papers_needed.composeapp.generated.resources.security_pin_mismatch
import no_papers_needed.composeapp.generated.resources.security_pin_too_short
import no_papers_needed.composeapp.generated.resources.security_pin_use_letters
import no_papers_needed.composeapp.generated.resources.security_pin_use_number_pad
import no_papers_needed.composeapp.generated.resources.security_recovery_android
import no_papers_needed.composeapp.generated.resources.security_recovery_desktop
import no_papers_needed.composeapp.generated.resources.security_recovery_title
import no_papers_needed.composeapp.generated.resources.security_set_pin
import no_papers_needed.composeapp.generated.resources.security_title
import no_papers_needed.composeapp.generated.resources.security_too_many
import no_papers_needed.composeapp.generated.resources.security_wrong_pin
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
    val confirmTitle = stringResource(Res.string.security_confirm_current_title)
    val deviceSubtitle = stringResource(Res.string.security_device_prompt_subtitle)

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
                label = stringResource(Res.string.security_lock_now),
                onClick = actions::lockAppNow,
            )
            SettingsActionRow(
                label = stringResource(Res.string.security_change_pin),
                onClick = actions::changeAppLockPin,
            )
            Dropdown(
                selected = lockTimeoutLabel(lock.timeout),
                options = LockTimeout.choices,
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

    when (val dialog = state.securityDialog) {
        null -> {}

        is SecurityDialog.ConfirmCurrent -> {
            CurrentSecretDialog(
                numeric = lock.numeric,
                check = state.securityCheck,
                deviceAuthAvailable = deviceAuthAvailable,
                onConfirm = actions::confirmAppLockChange,
                onDevice = {
                    deviceAuth?.authenticate(title = confirmTitle, subtitle = deviceSubtitle) { proved ->
                        if (proved) actions.confirmAppLockChangeWithDevice()
                    }
                },
                onDismiss = { actions.openSecurityDialog(null) },
            )
        }

        SecurityDialog.SetPin, SecurityDialog.ChangePin -> {
            SecurityDialogContent(
                dialog = dialog,
                onDismiss = { actions.openSecurityDialog(null) },
                onSave = actions::setAppLockPin,
            )
        }
    }
}

/**
 * Asks for the current secret before the lock is changed, or for the device's own unlock where it
 * has one. Its buffer is plain [remember]: a secret never goes in saved state.
 */
@Composable
internal fun CurrentSecretDialog(
    numeric: Boolean,
    check: SecurityCheck,
    deviceAuthAvailable: Boolean,
    onConfirm: (String) -> Unit,
    onDevice: () -> Unit,
    onDismiss: () -> Unit,
) {
    var secret by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    FocusOnEntry(focus)
    // Each try starts from an empty field, as on the lock screen.
    val submit = {
        if (secret.isNotEmpty() && !check.checking) {
            onConfirm(secret)
            secret = ""
        }
    }
    val error =
        when (val outcome = check.outcome) {
            is UnlockResult.TooManyAttempts -> {
                stringResource(Res.string.security_too_many, ((outcome.remainingMillis + 999) / 1000).toInt())
            }

            UnlockResult.WrongSecret -> {
                stringResource(Res.string.security_wrong_pin)
            }

            else -> {
                null
            }
        }
    ConfirmDialog(
        title = stringResource(Res.string.security_confirm_current_title),
        onConfirm = submit,
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.common_confirm),
        confirmEnabled = secret.isNotEmpty() && !check.checking,
    ) {
        Column {
            HelpText(stringResource(Res.string.security_confirm_current_help))
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                label = { Text(stringResource(Res.string.security_pin)) },
                singleLine = true,
                // Read-only rather than disabled while checking, so it keeps the keyboard focus.
                readOnly = check.checking,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = secretKeyboard(numeric, ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                isError = error != null,
            )
            if (error != null) ErrorText(error)
            if (deviceAuthAvailable) {
                OutlinedButton(onClick = onDevice, enabled = !check.checking) {
                    Text(stringResource(Res.string.security_confirm_device))
                }
            }
        }
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
internal fun SecurityDialogContent(
    dialog: SecurityDialog,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    /** Offer the switch between the number pad and a keyboard with letters; only an on-screen keyboard has one. */
    offerKeyboardChoice: Boolean = usesOnScreenKeyboard(),
) {
    // A PIN is digits by default, so the number pad comes up; letters stay allowed for a longer
    // secret, one tap away. Which keyboard is shown is no secret, so it is saved.
    var letters by rememberSaveable(dialog) { mutableStateOf(false) }
    val keyboard = secretKeyboard(numeric = !letters)
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
        Column {
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
                keyboardOptions = keyboard,
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
                keyboardOptions = keyboard,
                isError = mismatch,
            )
            if (mismatch) {
                Text(
                    stringResource(Res.string.security_pin_mismatch),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                )
            }
            if (offerKeyboardChoice) {
                val switchTo =
                    if (letters) Res.string.security_pin_use_number_pad else Res.string.security_pin_use_letters
                TextButton(onClick = { letters = !letters }) { Text(stringResource(switchTo)) }
            }
        }
    }
}
