package com.nuvio.app.features.installapps

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.updater.AppUpdaterPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** An app listed in the Kable Killers store catalog (apps.json) that can be installed from inside the app. */
@Serializable
data class InstallableApp(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val apkUrl: String = "",
    val packageName: String? = null,
    val platforms: List<String>? = null,
)

@Serializable
internal data class InstallAppsCatalogDto(
    val apps: List<InstallableApp> = emptyList(),
)

enum class InstallAppsMessage { InvalidUrl, DownloadFailed, InstallFailed }

data class InstallAppsUiState(
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val apps: List<InstallableApp> = emptyList(),
    val loadFailed: Boolean = false,
    /** Id of the app currently downloading, or [InstallAppsRepository.URL_INSTALL_ID] for a link install. */
    val activeDownloadId: String? = null,
    val downloadProgress: Float? = null,
    val showUnknownSourcesDialog: Boolean = false,
    val message: InstallAppsMessage? = null,
)

object InstallAppsRepository {
    const val URL_INSTALL_ID = "__url__"
    const val CATALOG_URL =
        "https://raw.githubusercontent.com/kablekillers/Kable-Killers-Store/main/apps.json"
    private const val PLATFORM = "mobile"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(InstallAppsUiState())
    val uiState: StateFlow<InstallAppsUiState> = _uiState.asStateFlow()

    /** APK that finished downloading but is waiting for the "install unknown apps" permission. */
    private var pendingApkPath: String? = null

    val isSupported: Boolean get() = AppUpdaterPlatform.isSupported

    fun ensureLoaded() {
        if (!_uiState.value.hasLoaded && !_uiState.value.isLoading) refresh()
    }

    fun refresh() {
        scope.launch {
            _uiState.update { it.copy(isLoading = true, loadFailed = false) }
            val result = runCatching {
                val response = httpRequestRaw(
                    method = "GET",
                    url = CATALOG_URL,
                    headers = mapOf("Cache-Control" to "no-cache"),
                    body = "",
                )
                if (response.status !in 200..299) error("HTTP ${response.status}")
                parse(response.body)
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    hasLoaded = true,
                    apps = result.getOrDefault(it.apps),
                    loadFailed = result.isFailure,
                )
            }
        }
    }

    internal fun parse(body: String): List<InstallableApp> =
        json.decodeFromString<InstallAppsCatalogDto>(body).apps.mapNotNull { app ->
            val platforms = app.platforms?.map { it.lowercase() }
            if (platforms != null && PLATFORM !in platforms) return@mapNotNull null
            val name = app.name.trim()
            if (name.isEmpty() || normalizeApkUrl(app.apkUrl) == null) return@mapNotNull null
            app.copy(
                id = app.id.trim().ifEmpty { name },
                name = name,
                description = app.description.trim(),
                apkUrl = app.apkUrl.trim(),
            )
        }

    /** Returns an http(s) URL, adding https:// when no scheme was typed, or null if it isn't a usable link. */
    fun normalizeApkUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val lower = withScheme.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null
        val host = withScheme.substringAfter("://").substringBefore('/').substringBefore('?')
        if (host.isEmpty() || !host.contains('.')) return null
        return withScheme
    }

    fun install(app: InstallableApp) = downloadAndInstall(app.id, app.apkUrl)

    fun installFromUrl(rawUrl: String) {
        val url = normalizeApkUrl(rawUrl)
        if (url == null) {
            _uiState.update { it.copy(message = InstallAppsMessage.InvalidUrl) }
            return
        }
        downloadAndInstall(URL_INSTALL_ID, url)
    }

    private fun downloadAndInstall(id: String, url: String) {
        if (_uiState.value.activeDownloadId != null) return
        _uiState.update { it.copy(activeDownloadId = id, downloadProgress = 0f, message = null) }
        scope.launch {
            pendingApkPath?.let(AppUpdaterPlatform::deleteDownloadedApk)
            pendingApkPath = null
            val result = AppUpdaterPlatform.downloadApk(
                assetUrl = url,
                assetName = "kk-install-app.apk",
            ) { downloaded, total ->
                val progress = if (total != null && total > 0) {
                    (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                }
                _uiState.update { it.copy(downloadProgress = progress) }
            }
            _uiState.update { it.copy(activeDownloadId = null, downloadProgress = null) }
            result
                .onSuccess { path -> installOrRequestPermission(path) }
                .onFailure { _uiState.update { it.copy(message = InstallAppsMessage.DownloadFailed) } }
        }
    }

    private fun installOrRequestPermission(path: String) {
        if (!AppUpdaterPlatform.canRequestPackageInstalls()) {
            pendingApkPath = path
            _uiState.update { it.copy(showUnknownSourcesDialog = true) }
            return
        }
        pendingApkPath = null
        _uiState.update { it.copy(showUnknownSourcesDialog = false) }
        AppUpdaterPlatform.installDownloadedApk(path)
            .onFailure { _uiState.update { it.copy(message = InstallAppsMessage.InstallFailed) } }
    }

    /** Call when returning to the app after the permission screen. */
    fun retryPendingInstall() {
        val path = pendingApkPath ?: return
        if (AppUpdaterPlatform.canRequestPackageInstalls()) installOrRequestPermission(path)
    }

    fun openUnknownSourcesSettings() {
        _uiState.update { it.copy(showUnknownSourcesDialog = false) }
        AppUpdaterPlatform.openUnknownSourcesSettings()
    }

    fun dismissUnknownSourcesDialog() {
        _uiState.update { it.copy(showUnknownSourcesDialog = false) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
