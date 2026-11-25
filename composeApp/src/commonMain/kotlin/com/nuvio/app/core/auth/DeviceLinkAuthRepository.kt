package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.backup.KableKillersBackup
import com.nuvio.app.core.backup.KableKillersBackupQr
import com.nuvio.app.core.backup.KableKillersBackupQrStatus
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.statement.bodyAsText
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface DeviceLinkAuthState {
    data object Idle : DeviceLinkAuthState
    data object Starting : DeviceLinkAuthState
    data class Waiting(
        val code: String,
        val verificationUrl: String,
        val isCompleting: Boolean = false,
        /** The QR comes from the Kable Killers backup because Nuvio was unreachable. */
        val isBackup: Boolean = false,
    ) : DeviceLinkAuthState
    data class Failed(val reason: DeviceLinkAuthFailure) : DeviceLinkAuthState
}

enum class DeviceLinkAuthFailure {
    Start,
    Expired,
    Complete,
    Connection,
    /** The owner chose not to let this phone in through the backup QR. */
    Denied,
}

object DeviceLinkAuthRepository {
    private const val maxConsecutivePollFailures = 8
    private const val maxPollAttempts = 120
    private const val officialLinkUrl = "https://nuvio.tv/link"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("DeviceLinkAuthRepository")
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<DeviceLinkAuthState>(DeviceLinkAuthState.Idle)
    val state: StateFlow<DeviceLinkAuthState> = _state.asStateFlow()
    private var activeJob: Job? = null

    @OptIn(ExperimentalUuidApi::class)
    fun start() {
        if (_state.value is DeviceLinkAuthState.Starting || _state.value is DeviceLinkAuthState.Waiting) return

        activeJob?.cancel()
        activeJob = scope.launch {
            _state.value = DeviceLinkAuthState.Starting
            val configuration = ServerConfigurationRepository.active.value
            val nonce = Uuid.random().toString()
            try {
                val session = try {
                    startSession(
                        configuration = configuration,
                        nonce = nonce,
                        deviceName = currentDeviceClientMetadata().deviceName,
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    // Nuvio can't start a QR login (it may be down): use the Kable Killers backup QR.
                    if (!KableKillersBackup.enabled) throw error
                    log.w(error) { "Nuvio QR unavailable; trying the Kable Killers backup QR" }
                    val backupQr = KableKillersBackup.startBackupQr().getOrElse { backupError ->
                        log.w(backupError) { "Backup QR unavailable too" }
                        throw error
                    }
                    runBackupQr(backupQr)
                    return@launch
                }
                _state.value = DeviceLinkAuthState.Waiting(
                    code = formatDeviceLinkCode(session.userCode),
                    verificationUrl = session.verificationUriComplete,
                )
                pollAndComplete(session, nonce)
            } catch (error: CancellationException) {
                throw error
            } catch (error: DeviceLinkAuthException) {
                log.w(error) { "Device link sign-in stopped" }
                _state.value = DeviceLinkAuthState.Failed(error.reason)
            } catch (error: Throwable) {
                log.w(error) { "Device link sign-in failed" }
                _state.value = DeviceLinkAuthState.Failed(DeviceLinkAuthFailure.Start)
            }
        }
    }

    private suspend fun runBackupQr(qr: KableKillersBackupQr) {
        val displayCode = qr.code.chunked(4).joinToString("-")
        _state.value = DeviceLinkAuthState.Waiting(code = displayCode, verificationUrl = qr.url, isBackup = true)
        val intervalMillis = qr.pollIntervalSeconds.coerceIn(2, 10) * 1_000L
        val maxAttempts = (qr.expiresInSeconds * 1_000L / intervalMillis).toInt().coerceAtLeast(1)
        var consecutiveFailures = 0
        repeat(maxAttempts) {
            delay(intervalMillis)
            val status = KableKillersBackup.pollBackupQr(qr).getOrElse { error ->
                consecutiveFailures += 1
                if (consecutiveFailures >= maxConsecutivePollFailures * 4) {
                    throw DeviceLinkAuthException(DeviceLinkAuthFailure.Connection, error)
                }
                return@repeat
            }
            consecutiveFailures = 0
            when (status) {
                KableKillersBackupQrStatus.Waiting -> Unit
                KableKillersBackupQrStatus.Denied -> throw DeviceLinkAuthException(DeviceLinkAuthFailure.Denied)
                KableKillersBackupQrStatus.Expired -> throw DeviceLinkAuthException(DeviceLinkAuthFailure.Expired)
                is KableKillersBackupQrStatus.Approved -> {
                    _state.value = DeviceLinkAuthState.Waiting(
                        code = displayCode, verificationUrl = qr.url, isCompleting = true, isBackup = true,
                    )
                    // Same as "continue without an account", then fill Profile 1 from the backup.
                    AuthRepository.signInAnonymously()
                    scope.launch { restoreAfterBackupSignIn() }
                    _state.value = DeviceLinkAuthState.Idle
                    return
                }
            }
        }
        throw DeviceLinkAuthException(DeviceLinkAuthFailure.Expired)
    }

    /** Profiles load right after sign-in; give them a moment before restoring Profile 1. */
    private suspend fun restoreAfterBackupSignIn() {
        repeat(6) { attempt ->
            delay(if (attempt == 0) 500L else 1_500L)
            val applied = runCatching { KableKillersBackup.applyLatest() }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; log.w { "Restore after backup QR failed: ${it.message}" } }
            if (applied.isSuccess) return
        }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        _state.value = DeviceLinkAuthState.Idle
    }

    private suspend fun startSession(
        configuration: ServerConfiguration,
        nonce: String,
        deviceName: String,
    ): DeviceLinkStartResponse {
        val params = buildJsonObject {
            put("p_device_nonce", nonce)
            put("p_redirect_base_url", configuration.deviceLinkUrl())
            put("p_device_name", deviceName)
            put("p_device_type", "mobile")
        }
        return SupabaseProvider.client.postgrest
            .rpc("start_device_login_session", params)
            .decodeList<DeviceLinkStartResponse>()
            .firstOrNull()
            ?.takeIf {
                it.deviceCode.isNotBlank() &&
                    it.userCode.isNotBlank() &&
                    it.verificationUriComplete.isNotBlank()
            }
            ?: throw DeviceLinkAuthException(DeviceLinkAuthFailure.Start)
    }

    private suspend fun pollAndComplete(session: DeviceLinkStartResponse, nonce: String) {
        var pollAttempts = 0
        var consecutiveFailures = 0
        val intervalMillis = session.pollIntervalSeconds.coerceIn(2, 10) * 1_000L

        while (currentCoroutineContext().isActive && pollAttempts < maxPollAttempts) {
            delay(intervalMillis)
            pollAttempts += 1
            val poll = try {
                val params = buildJsonObject {
                    put("p_code", session.deviceCode)
                    put("p_device_nonce", nonce)
                }
                SupabaseProvider.client.postgrest
                    .rpc("poll_tv_login_session", params)
                    .decodeList<DeviceLinkPollResponse>()
                    .firstOrNull()
                    ?: throw DeviceLinkAuthException(DeviceLinkAuthFailure.Complete)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                consecutiveFailures += 1
                if (consecutiveFailures >= maxConsecutivePollFailures) {
                    throw DeviceLinkAuthException(DeviceLinkAuthFailure.Connection, error)
                }
                continue
            }
            consecutiveFailures = 0

            when (poll.status.trim().lowercase()) {
                "pending" -> Unit
                "approved" -> {
                    _state.value = DeviceLinkAuthState.Waiting(
                        code = formatDeviceLinkCode(session.userCode),
                        verificationUrl = session.verificationUriComplete,
                        isCompleting = true,
                    )
                    completeSession(session.deviceCode, nonce)
                    return
                }
                "expired", "denied", "cancelled", "canceled" -> throw DeviceLinkAuthException(DeviceLinkAuthFailure.Expired)
                else -> {
                    log.w { "Unexpected device login polling status: ${poll.status}" }
                    throw DeviceLinkAuthException(DeviceLinkAuthFailure.Complete)
                }
            }
        }

        throw DeviceLinkAuthException(DeviceLinkAuthFailure.Expired)
    }

    private suspend fun completeSession(deviceCode: String, nonce: String) {
        try {
            val payload = buildJsonObject {
                put("code", deviceCode)
                put("device_nonce", nonce)
            }
            val response = SupabaseProvider.client.functions.invoke("tv-logins-exchange", payload)
            val result = json.decodeFromString<DeviceLinkExchangeResponse>(response.bodyAsText())
            // The exchange already returns credentials. Avoid a second network request
            // before importing them; user details can be fetched after sign-in.
            val user = result.user
            val expiresIn = requireNotNull(result.expiresIn?.takeIf { it > 0L })
            SupabaseProvider.client.auth.importSession(
                UserSession(
                    accessToken = result.accessToken,
                    refreshToken = result.refreshToken,
                    expiresIn = expiresIn,
                    tokenType = result.tokenType?.takeIf { it.isNotBlank() } ?: "bearer",
                    user = user,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // The exchange can consume a one-time code; do not replay it blindly.
            log.w(error) { "Device link session exchange or import failed" }
            throw DeviceLinkAuthException(DeviceLinkAuthFailure.Complete, error)
        }
    }

    private fun ServerConfiguration.deviceLinkUrl(): String =
        if (isCustom) "${backendUrl.trimEnd('/')}/link" else officialLinkUrl
}

internal fun formatDeviceLinkCode(value: String): String {
    val normalized = value.uppercase().filter { it.isLetterOrDigit() }.take(6)
    return if (normalized.length <= 3) normalized else "${normalized.take(3)}-${normalized.drop(3)}"
}

@Serializable
private data class DeviceLinkStartResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int = 3,
)

@Serializable
private data class DeviceLinkPollResponse(
    val status: String,
)

@Serializable
private data class DeviceLinkExchangeResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val user: UserInfo? = null,
)

private class DeviceLinkAuthException(
    val reason: DeviceLinkAuthFailure,
    cause: Throwable? = null,
) : Exception(reason.name, cause)
