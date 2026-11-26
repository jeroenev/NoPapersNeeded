package dev.npn.app.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.npn.app.platform.LocalAppLockAuthenticator
import dev.npn.app.platform.appLockRecoveryFile
import dev.npn.app.platform.focusesSecretFieldOnEntry
import dev.npn.app.ui.common.FocusOnEntry
import dev.npn.core.security.UnlockResult
import kotlinx.coroutines.delay
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.security_device_prompt_subtitle
import no_papers_needed.composeapp.generated.resources.security_locked_help
import no_papers_needed.composeapp.generated.resources.security_locked_title
import no_papers_needed.composeapp.generated.resources.security_not_encrypted
import no_papers_needed.composeapp.generated.resources.security_pin
import no_papers_needed.composeapp.generated.resources.security_recovery_android
import no_papers_needed.composeapp.generated.resources.security_recovery_desktop
import no_papers_needed.composeapp.generated.resources.security_too_many
import no_papers_needed.composeapp.generated.resources.security_unlock
import no_papers_needed.composeapp.generated.resources.security_unlock_device
import no_papers_needed.composeapp.generated.resources.security_wrong_pin
import org.jetbrains.compose.resources.stringResource

/** How often the lock screen refreshes a running retry countdown. */
private const val RETRY_TICK_MILLIS = 250L

private val FormWidth = 360.dp

/**
 * The app lock's whole screen: the unlock secret, the platform's system unlock where there is one,
 * and how to get back in if the secret is forgotten. It covers every screen of the app, because
 * the shell shows it instead of the app's content while [LockUiState.lock] says locked.
 *
 * The typed secret lives here, in an unsaved [remember]: it is never part of the viewmodel state,
 * never written to a saved-state parcel, and gone as soon as the screen leaves. A process death
 * starts locked again, as a cold start does.
 */
@Suppress("UnnecessaryLaunchedEffect") // The prompt must run once per key, not after every recomposition.
@Composable
fun LockScreen(
    state: LockUiState,
    actions: LockActions,
    modifier: Modifier = Modifier,
) {
    var secret by remember { mutableStateOf("") }
    val secretFocus = remember { FocusRequester() }
    // Where there is a keyboard to type with straight away; on a phone the device prompt comes
    // first, and a field taking focus would raise the on-screen keyboard behind it.
    if (focusesSecretFieldOnEntry()) FocusOnEntry(secretFocus)
    val deviceAuth = LocalAppLockAuthenticator.current
    val deviceAuthAvailable = remember(deviceAuth) { deviceAuth?.isAvailable() == true }
    val retryRemaining by
        produceState(initialValue = actions.retryRemainingMillis(), state.lock.retryAtMillis) {
            value = actions.retryRemainingMillis()
            while (value > 0) {
                delay(RETRY_TICK_MILLIS)
                value = actions.retryRemainingMillis()
            }
        }
    val retrySeconds = ((retryRemaining + 999) / 1000).toInt()

    val title = stringResource(Res.string.security_locked_title)
    val subtitle = stringResource(Res.string.security_device_prompt_subtitle)
    // The one way the platform's proof unlocks the app: both the auto-offer and the button go
    // through the prompt, never straight to unlockSystem (which would be no check at all).
    val promptForDevice: () -> Unit = {
        deviceAuth?.authenticate(title, subtitle) { unlocked -> if (unlocked) actions.unlockWithDevice() }
    }
    LaunchedEffect(deviceAuth, deviceAuthAvailable) {
        // Offer the device prompt as soon as the lock shows, once; cancelling leaves the PIN field.
        if (deviceAuth != null && deviceAuthAvailable) promptForDevice()
    }

    val error =
        when {
            retrySeconds > 0 -> stringResource(Res.string.security_too_many, retrySeconds)
            state.outcome is UnlockResult.WrongSecret -> stringResource(Res.string.security_wrong_pin)
            else -> null
        }
    val recoveryFile = appLockRecoveryFile()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null)
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(Res.string.security_locked_help),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                modifier = Modifier.fillMaxWidth().widthIn(max = FormWidth).focusRequester(secretFocus),
                label = { Text(stringResource(Res.string.security_pin)) },
                singleLine = true,
                // Read-only, not disabled, while the hash runs: a disabled field drops the keyboard
                // focus, and the next try after a wrong one would be typed into nothing.
                readOnly = state.checking,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = secretKeyboard(state.lock.numeric, ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { actions.unlock(secret) }),
                isError = error != null,
            )
            if (error != null) ErrorText(error)
            Button(
                onClick = { actions.unlock(secret) },
                enabled = secret.isNotEmpty() && !state.checking && retryRemaining == 0L,
            ) {
                if (state.checking) {
                    CircularProgressIndicator(Modifier.padding(end = 8.dp))
                }
                Text(stringResource(Res.string.security_unlock))
            }
            if (deviceAuthAvailable) {
                OutlinedButton(onClick = promptForDevice, enabled = !state.checking) {
                    Text(stringResource(Res.string.security_unlock_device))
                }
            }
            Text(
                if (recoveryFile != null) {
                    stringResource(Res.string.security_recovery_desktop, recoveryFile)
                } else {
                    stringResource(Res.string.security_recovery_android)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(Res.string.security_not_encrypted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The keyboard for typing the unlock secret: a number pad when the secret is digits only. */
internal fun secretKeyboard(
    numeric: Boolean,
    imeAction: ImeAction = ImeAction.Default,
): KeyboardOptions =
    KeyboardOptions(
        keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Password,
        imeAction = imeAction,
    )

/** Why the secret was not accepted, read out as soon as it shows (a live region). */
@Composable
internal fun ErrorText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
}
