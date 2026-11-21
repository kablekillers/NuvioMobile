package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.installapps.InstallAppsMessage
import com.nuvio.app.features.installapps.InstallAppsRepository
import com.nuvio.app.features.installapps.InstallAppsUiState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.install_apps_cancel
import nuvio.composeapp.generated.resources.install_apps_download_failed
import nuvio.composeapp.generated.resources.install_apps_downloading
import nuvio.composeapp.generated.resources.install_apps_downloading_percent
import nuvio.composeapp.generated.resources.install_apps_empty
import nuvio.composeapp.generated.resources.install_apps_install
import nuvio.composeapp.generated.resources.install_apps_install_failed
import nuvio.composeapp.generated.resources.install_apps_invalid_url
import nuvio.composeapp.generated.resources.install_apps_link_dialog_subtitle
import nuvio.composeapp.generated.resources.install_apps_link_placeholder
import nuvio.composeapp.generated.resources.install_apps_link_section
import nuvio.composeapp.generated.resources.install_apps_link_subtitle
import nuvio.composeapp.generated.resources.install_apps_link_title
import nuvio.composeapp.generated.resources.install_apps_load_failed
import nuvio.composeapp.generated.resources.install_apps_loading
import nuvio.composeapp.generated.resources.install_apps_open_settings
import nuvio.composeapp.generated.resources.install_apps_permission_body
import nuvio.composeapp.generated.resources.install_apps_permission_title
import nuvio.composeapp.generated.resources.install_apps_retry
import nuvio.composeapp.generated.resources.install_apps_store_section
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.installAppsSettingsContent(isTablet: Boolean) {
    item(key = "install_apps_effects") {
        InstallAppsEffects()
    }
    item(key = "install_apps_link") {
        val state by InstallAppsRepository.uiState.collectAsStateWithLifecycle()
        var showUrlDialog by rememberSaveable { mutableStateOf(false) }
        SettingsSection(
            title = stringResource(Res.string.install_apps_link_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.install_apps_link_title),
                    description = if (state.activeDownloadId == InstallAppsRepository.URL_INSTALL_ID) {
                        progressLabel(state)
                    } else {
                        stringResource(Res.string.install_apps_link_subtitle)
                    },
                    icon = Icons.Rounded.Link,
                    enabled = state.activeDownloadId == null,
                    isTablet = isTablet,
                    onClick = { showUrlDialog = true },
                )
            }
        }
        if (showUrlDialog) {
            InstallFromUrlDialog(
                onInstall = { url ->
                    showUrlDialog = false
                    InstallAppsRepository.installFromUrl(url)
                },
                onDismiss = { showUrlDialog = false },
            )
        }
    }
    item(key = "install_apps_store") {
        val state by InstallAppsRepository.uiState.collectAsStateWithLifecycle()
        SettingsSection(
            title = stringResource(Res.string.install_apps_store_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                when {
                    state.apps.isEmpty() && (state.isLoading || !state.hasLoaded) -> InstallAppsNote(
                        stringResource(Res.string.install_apps_loading),
                    )
                    state.apps.isEmpty() && state.loadFailed -> SettingsNavigationRow(
                        title = stringResource(Res.string.install_apps_load_failed),
                        description = stringResource(Res.string.install_apps_retry),
                        icon = Icons.Rounded.Refresh,
                        isTablet = isTablet,
                        onClick = InstallAppsRepository::refresh,
                    )
                    state.apps.isEmpty() -> InstallAppsNote(stringResource(Res.string.install_apps_empty))
                    else -> state.apps.forEachIndexed { index, app ->
                        if (index > 0) SettingsGroupDivider(isTablet = isTablet)
                        SettingsNavigationRow(
                            title = app.name,
                            description = if (state.activeDownloadId == app.id) {
                                progressLabel(state)
                            } else {
                                app.description.ifEmpty { null }
                            },
                            icon = Icons.Rounded.Download,
                            enabled = state.activeDownloadId == null,
                            isTablet = isTablet,
                            trailingContent = {
                                Text(
                                    text = stringResource(Res.string.install_apps_install),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            },
                            onClick = { InstallAppsRepository.install(app) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallAppsEffects() {
    val state by InstallAppsRepository.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { InstallAppsRepository.ensureLoaded() }
    // Coming back from Android's "install unknown apps" screen finishes the pending install.
    LifecycleResumeEffect(Unit) {
        InstallAppsRepository.retryPendingInstall()
        onPauseOrDispose { }
    }

    val invalidUrl = stringResource(Res.string.install_apps_invalid_url)
    val downloadFailed = stringResource(Res.string.install_apps_download_failed)
    val installFailed = stringResource(Res.string.install_apps_install_failed)
    LaunchedEffect(state.message) {
        val text = when (state.message) {
            InstallAppsMessage.InvalidUrl -> invalidUrl
            InstallAppsMessage.DownloadFailed -> downloadFailed
            InstallAppsMessage.InstallFailed -> installFailed
            null -> null
        }
        if (text != null) {
            NuvioToastController.show(text, durationMillis = 4000L)
            InstallAppsRepository.consumeMessage()
        }
    }

    if (state.showUnknownSourcesDialog) {
        DialogSurface(
            onDismissRequest = InstallAppsRepository::dismissUnknownSourcesDialog,
            title = stringResource(Res.string.install_apps_permission_title),
            message = stringResource(Res.string.install_apps_permission_body),
        ) {
            DialogButtons {
                DialogButton(
                    text = stringResource(Res.string.install_apps_cancel),
                    onClick = InstallAppsRepository::dismissUnknownSourcesDialog,
                )
                DialogButton(
                    text = stringResource(Res.string.install_apps_open_settings),
                    style = DialogButtonStyle.Primary,
                    onClick = InstallAppsRepository::openUnknownSourcesSettings,
                )
            }
        }
    }
}

@Composable
private fun progressLabel(state: InstallAppsUiState): String {
    val progress = state.downloadProgress
    return if (progress != null) {
        stringResource(Res.string.install_apps_downloading_percent, (progress * 100).toInt())
    } else {
        stringResource(Res.string.install_apps_downloading)
    }
}

@Composable
private fun InstallAppsNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
private fun InstallFromUrlDialog(
    onInstall: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf("") }
    val submit = { if (value.isNotBlank()) onInstall(value) }
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.install_apps_link_title),
        message = stringResource(Res.string.install_apps_link_dialog_subtitle),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
            placeholder = { Text(stringResource(Res.string.install_apps_link_placeholder)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.install_apps_cancel),
                onClick = onDismiss,
            )
            DialogButton(
                text = stringResource(Res.string.install_apps_install),
                style = DialogButtonStyle.Primary,
                enabled = value.isNotBlank(),
                onClick = submit,
            )
        }
    }
}
