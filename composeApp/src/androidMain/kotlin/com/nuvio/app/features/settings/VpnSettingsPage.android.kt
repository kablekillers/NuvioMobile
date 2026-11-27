package com.nuvio.app.features.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.vpn.KableKillersVpn
import com.nuvio.app.features.vpn.KkVpnState
import com.nuvio.app.features.vpn.KkVpnStatus

internal actual val isVpnSupported: Boolean = true

@Composable
internal actual fun VpnSettingsBody(isTablet: Boolean) {
    val context = LocalContext.current
    val state by KableKillersVpn.state.collectAsStateWithLifecycle()
    var showPasteDialog by rememberSaveable { mutableStateOf(false) }
    var showRemoveDialog by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        KableKillersVpn.refresh()
        onPauseOrDispose { }
    }

    val importConfig: (String?) -> Unit = { text ->
        if (text.isNullOrBlank()) {
            NuvioToastController.show("Nothing was imported.")
        } else {
            KableKillersVpn.saveConfig(text)
                .onSuccess { NuvioToastController.show("VPN config saved on this device.") }
                .onFailure { NuvioToastController.show(it.message ?: "Couldn't import the config.", durationMillis = 4000L) }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            KableKillersVpn.setEnabled(true)
        } else {
            NuvioToastController.show("The VPN needs permission to connect.", durationMillis = 4000L)
        }
    }
    val turnOn: () -> Unit = {
        val intent = KableKillersVpn.permissionIntent()
        if (intent != null) permissionLauncher.launch(intent) else KableKillersVpn.setEnabled(true)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    // A WireGuard config is tiny; refuse anything that clearly isn't one.
                    String(stream.readBytes().take(64 * 1024).toByteArray())
                }
            }.getOrNull()
            importConfig(text)
        }
    }

    val scanner = remember(context) {
        GmsBarcodeScanning.getClient(
            context,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build(),
        )
    }
    val scanQr: () -> Unit = {
        scanner.startScan()
            .addOnSuccessListener { barcode -> importConfig(barcode.rawValue) }
            .addOnFailureListener {
                NuvioToastController.show("Couldn't open the QR scanner. Try importing the file instead.", durationMillis = 4000L)
            }
    }

    Column {
        SettingsSection(title = "IPVanish VPN", isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "VPN",
                    description = statusText(state),
                    checked = state.enabled,
                    enabled = state.hasConfig,
                    isTablet = isTablet,
                    onCheckedChange = { on -> if (on) turnOn() else KableKillersVpn.setEnabled(false) },
                )
                if (!state.hasConfig) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = "Get VPN setup",
                        description = when {
                            state.checkingPool -> "Checking with Kable Killers…"
                            state.poolEmpty -> "No VPN spots are free right now. Message Kable Killers, then tap to try again."
                            else -> "Sets up the VPN for this device automatically."
                        },
                        icon = Icons.Rounded.Refresh,
                        enabled = !state.checkingPool,
                        isTablet = isTablet,
                        onClick = KableKillersVpn::syncFromPool,
                    )
                }
                if (state.enabled && state.status == KkVpnStatus.NeedsPermission) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = "Allow VPN connection",
                        description = "Android needs your OK before the VPN can reconnect.",
                        icon = Icons.Rounded.Warning,
                        isTablet = isTablet,
                        onClick = turnOn,
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = "Protect the whole phone",
                    description = if (state.wholeDevice) {
                        "All apps on this phone use the VPN."
                    } else {
                        "Only Kable Killers uses the VPN. Other apps keep their normal connection."
                    },
                    checked = state.wholeDevice,
                    enabled = state.hasConfig,
                    isTablet = isTablet,
                    onCheckedChange = KableKillersVpn::setWholeDevice,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        SettingsSection(
            title = "Use your own IPVanish config (optional)",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "Scan QR code",
                    description = "Scan the WireGuard QR from the IPVanish account page.",
                    icon = Icons.Rounded.QrCodeScanner,
                    isTablet = isTablet,
                    onClick = scanQr,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Import .conf file",
                    description = "Pick a WireGuard config downloaded from IPVanish.",
                    icon = Icons.Rounded.FileOpen,
                    isTablet = isTablet,
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Paste config",
                    description = "Paste the text of a WireGuard config.",
                    icon = Icons.Rounded.ContentPaste,
                    isTablet = isTablet,
                    onClick = { showPasteDialog = true },
                )
                if (state.hasConfig && !state.fromPool) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = "Remove config",
                        description = "Turns the VPN off and deletes the config from this device.",
                        icon = Icons.Rounded.Delete,
                        isTablet = isTablet,
                        onClick = { showRemoveDialog = true },
                    )
                }
            }
        }

        Text(
            text = if (state.fromPool) {
                "This device's VPN was set up automatically by Kable Killers. It stays on this device only."
            } else {
                "Kable Killers sets up the VPN automatically after you sign in. Only use the options above if you " +
                    "have your own IPVanish account: generate a WireGuard config for this device and import it. " +
                    "The config stays on this device only."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }

    if (showPasteDialog) {
        PasteConfigDialog(
            onSave = { text ->
                showPasteDialog = false
                importConfig(text)
            },
            onDismiss = { showPasteDialog = false },
        )
    }

    if (showRemoveDialog) {
        DialogSurface(
            onDismissRequest = { showRemoveDialog = false },
            title = "Remove VPN config?",
            message = "The VPN will turn off. You'll need to add a config again to use it.",
        ) {
            DialogButtons {
                DialogButton(text = "Cancel", onClick = { showRemoveDialog = false })
                DialogButton(
                    text = "Remove",
                    style = DialogButtonStyle.Primary,
                    onClick = {
                        showRemoveDialog = false
                        KableKillersVpn.removeConfig()
                    },
                )
            }
        }
    }
}

private fun statusText(state: KkVpnState): String {
    if (!state.hasConfig) return if (state.checkingPool) "Setting up…" else "Not set up on this device yet"
    val server = state.serverLabel?.let { " · $it" }.orEmpty()
    return when (state.status) {
        KkVpnStatus.Connected -> "Connected$server"
        KkVpnStatus.Connecting -> "Connecting…"
        KkVpnStatus.NeedsPermission -> "Waiting for permission"
        KkVpnStatus.Error -> state.errorMessage ?: "Couldn't connect"
        KkVpnStatus.Off -> if (state.enabled) "Reconnecting…" else "Off$server"
    }
}

@Composable
private fun PasteConfigDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    DialogSurface(
        onDismissRequest = onDismiss,
        title = "Paste config",
        message = "Paste the full WireGuard config, starting with [Interface].",
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp, max = 260.dp),
            placeholder = { Text("[Interface]\nPrivateKey = …") },
        )
        DialogButtons {
            DialogButton(text = "Cancel", onClick = onDismiss)
            DialogButton(
                text = "Save",
                style = DialogButtonStyle.Primary,
                enabled = value.isNotBlank(),
                onClick = { onSave(value) },
            )
        }
    }
}
