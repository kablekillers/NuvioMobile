package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.backup.KableKillersBackup
import com.nuvio.app.core.backup.KableKillersRestoreSummary
import com.nuvio.app.core.time.EpisodeReleaseDatePlatform
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioSurfaceCard
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.kk_backup_done
import nuvio.composeapp.generated.resources.kk_backup_now_title
import nuvio.composeapp.generated.resources.kk_backup_pin_action
import nuvio.composeapp.generated.resources.kk_backup_pin_email_hint
import nuvio.composeapp.generated.resources.kk_backup_pin_hint
import nuvio.composeapp.generated.resources.kk_backup_pin_title
import nuvio.composeapp.generated.resources.kk_backup_restore_confirm_action
import nuvio.composeapp.generated.resources.kk_backup_restore_confirm_subtitle
import nuvio.composeapp.generated.resources.kk_backup_restore_confirm_title
import nuvio.composeapp.generated.resources.kk_backup_restore_title
import nuvio.composeapp.generated.resources.kk_backup_restored
import nuvio.composeapp.generated.resources.kk_backup_status_connected
import nuvio.composeapp.generated.resources.kk_backup_status_signed_in
import nuvio.composeapp.generated.resources.kk_backup_status_signed_in_none
import nuvio.composeapp.generated.resources.kk_backup_status_signed_out
import nuvio.composeapp.generated.resources.kk_backup_title
import nuvio.composeapp.generated.resources.kk_backup_working
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Kable Killers backup controls for the Account page. Hidden when the backup isn't set up. */
@Composable
internal fun KableKillersBackupCard(authState: AuthState) {
    if (!KableKillersBackup.enabled) return
    LaunchedEffect(Unit) { KableKillersBackup.refreshStatus() }
    val status by KableKillersBackup.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val signedIn = authState is AuthState.Authenticated && !authState.isAnonymous

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var showPinForm by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }

    fun run(action: suspend () -> String) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            val result = runCatching { action() }
            busy = false
            isError = result.isFailure
            message = result.getOrElse { it.message ?: "Something went wrong" }
        }
    }

    suspend fun describe(summary: KableKillersRestoreSummary): String = getString(
        Res.string.kk_backup_restored,
        summary.addons, summary.plugins, summary.homeRows, summary.collections,
    )

    NuvioSurfaceCard {
        Text(
            text = stringResource(Res.string.kk_backup_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = when {
                signedIn && status.lastBackupAtMs > 0 ->
                    stringResource(Res.string.kk_backup_status_signed_in, relativeTime(status.lastBackupAtMs))
                signedIn -> stringResource(Res.string.kk_backup_status_signed_in_none)
                status.hasDeviceKey -> stringResource(Res.string.kk_backup_status_connected, status.accountEmail.orEmpty())
                else -> stringResource(Res.string.kk_backup_status_signed_out)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val note = if (busy) stringResource(Res.string.kk_backup_working) else message
        if (note != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = note,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError && !busy) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (signedIn) {
                val done = stringResource(Res.string.kk_backup_done)
                OutlinedButton(
                    onClick = { run { KableKillersBackup.backupNow(force = true).getOrThrow(); done } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(Res.string.kk_backup_now_title)) }
            }
            if (status.hasDeviceKey) {
                OutlinedButton(
                    onClick = { confirmRestore = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(Res.string.kk_backup_restore_title)) }
            } else if (!signedIn) {
                if (!showPinForm) {
                    OutlinedButton(
                        onClick = { showPinForm = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(Res.string.kk_backup_pin_title)) }
                } else {
                    NuvioInputField(
                        value = email,
                        onValueChange = { email = it.trim() },
                        placeholder = stringResource(Res.string.kk_backup_pin_email_hint),
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { value -> pin = value.filter(Char::isDigit).take(12) },
                        placeholder = { Text(stringResource(Res.string.kk_backup_pin_hint)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NuvioPrimaryButton(
                        text = stringResource(Res.string.kk_backup_pin_action),
                        enabled = !busy && email.contains('@') && pin.length >= 6,
                        onClick = {
                            run {
                                KableKillersBackup.signInWithRestorePin(email, pin).getOrThrow()
                                pin = ""
                                showPinForm = false
                                describe(KableKillersBackup.restoreNow().getOrThrow())
                            }
                        },
                    )
                }
            }
        }
    }

    NuvioStatusModal(
        title = stringResource(Res.string.kk_backup_restore_confirm_title),
        message = stringResource(Res.string.kk_backup_restore_confirm_subtitle),
        isVisible = confirmRestore,
        confirmText = stringResource(Res.string.kk_backup_restore_confirm_action),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = {
            confirmRestore = false
            run { describe(KableKillersBackup.restoreNow().getOrThrow()) }
        },
        onDismiss = { confirmRestore = false },
    )
}

private fun relativeTime(epochMs: Long): String {
    val minutes = ((EpisodeReleaseDatePlatform.nowEpochMs() - epochMs) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}
