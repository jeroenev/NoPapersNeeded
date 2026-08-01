@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.datetime.LocalDate
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_choose_date
import no_papers_needed.composeapp.generated.resources.common_clear_date
import no_papers_needed.composeapp.generated.resources.common_confirm
import no_papers_needed.composeapp.generated.resources.detail_iso_date_template
import org.jetbrains.compose.resources.stringResource

private const val MILLIS_PER_DAY = 86_400_000L

/** Convert an ISO `yyyy-MM-dd` [iso] to UTC-midnight epoch millis (Material DatePicker convention). */
internal fun isoDateToMillis(iso: String?): Long? =
    iso?.let { runCatching { LocalDate.parse(it).toEpochDays() * MILLIS_PER_DAY }.getOrNull() }

/** Convert UTC-midnight epoch [millis] back to an ISO `yyyy-MM-dd` string. */
internal fun millisToIsoDate(millis: Long): String = LocalDate.fromEpochDays(millis / MILLIS_PER_DAY).toString()

/**
 * Date picker dialog bound to an ISO `yyyy-MM-dd` string (null = unset). On confirm it reports the
 * picked date (or null when the user cleared the selection); [onDismiss] fires on cancel/outside.
 */
@Composable
fun IsoDatePickerDialog(
    initial: String?,
    title: String,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis = isoDateToMillis(initial),
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSelect(state.selectedDateMillis?.let(::millisToIsoDate)) }) {
                Text(stringResource(Res.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    ) {
        DatePicker(state = state, title = { Text(title) })
    }
}

/**
 * Read-only date field with a calendar action that opens an [IsoDatePickerDialog]. Picking a date
 * commits it via [onCommit]; a clear action removes it when [value] is set. Typed input is not
 * supported here — callers that also want manual ISO entry keep their editable field and call
 * [IsoDatePickerDialog] from a trailing calendar icon instead.
 */
@Composable
fun DatePickerField(
    value: String?,
    label: String,
    onCommit: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = value.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text(stringResource(Res.string.detail_iso_date_template)) },
            singleLine = true,
            trailingIcon = {
                Row {
                    if (!value.isNullOrBlank()) {
                        IconButton(onClick = { onCommit(null) }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(Res.string.common_clear_date))
                        }
                    }
                    IconButton(onClick = { showPicker = true }) {
                        Icon(
                            Icons.Filled.CalendarToday,
                            contentDescription = stringResource(Res.string.common_choose_date),
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (showPicker) {
        IsoDatePickerDialog(
            initial = value,
            title = label,
            onSelect = {
                showPicker = false
                onCommit(it)
            },
            onDismiss = { showPicker = false },
        )
    }
}
