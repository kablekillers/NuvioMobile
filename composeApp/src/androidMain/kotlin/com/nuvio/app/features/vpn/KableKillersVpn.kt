package com.nuvio.app.features.vpn

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.StringReader

enum class KkVpnStatus { Off, Connecting, Connected, NeedsPermission, Error }

data class KkVpnState(
    val hasConfig: Boolean = false,
    val enabled: Boolean = false,
    /** false = only Kable Killers goes through the VPN; true = the whole phone does. */
    val wholeDevice: Boolean = false,
    val status: KkVpnStatus = KkVpnStatus.Off,
    /** IPVanish server the config points at, e.g. "atl-a01.wg.ipvanish.com". */
    val serverLabel: String? = null,
    val errorMessage: String? = null,
)

/**
 * Built-in Kable Killers VPN. Runs a WireGuard tunnel from a config generated in the IPVanish
 * account portal (one config per device, so every device has its own key).
 *
 * The config is kept in encrypted storage on this device only. It is never synced to the cloud,
 * never part of profiles, the Cloudflare backup or the APK.
 */
object KableKillersVpn {
    private const val TAG = "KableKillersVpn"
    private const val PREFS_NAME = "kk_vpn_secure"
    private const val KEY_CONFIG = "wg_config"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WHOLE_DEVICE = "whole_device"
    private const val TUNNEL_NAME = "kablekillers"

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var backend: GoBackend? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _state = MutableStateFlow(KkVpnState())
    val state: StateFlow<KkVpnState> = _state.asStateFlow()

    private val tunnel = object : Tunnel {
        override fun getName(): String = TUNNEL_NAME

        override fun onStateChange(newState: Tunnel.State) {
            _state.update {
                when (newState) {
                    Tunnel.State.UP -> it.copy(status = KkVpnStatus.Connected, errorMessage = null)
                    else -> if (it.status == KkVpnStatus.Connecting) it else it.copy(status = KkVpnStatus.Off)
                }
            }
        }
    }

    fun initialize(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val store = openPrefs(ctx)
        prefs = store
        val config = store.getString(KEY_CONFIG, null)
        _state.value = KkVpnState(
            hasConfig = config != null,
            enabled = store.getBoolean(KEY_ENABLED, false),
            wholeDevice = store.getBoolean(KEY_WHOLE_DEVICE, false),
            serverLabel = config?.let(::serverLabelOf),
        )
    }

    /** Called when the app opens: reconnects if the VPN was left on. */
    fun autoConnectIfEnabled() {
        val current = _state.value
        if (!current.enabled || !current.hasConfig) return
        if (current.status == KkVpnStatus.Connected || current.status == KkVpnStatus.Connecting) return
        if (permissionIntent() != null) {
            _state.update { it.copy(status = KkVpnStatus.NeedsPermission) }
            return
        }
        connect()
    }

    /** Re-reads the real tunnel state, e.g. after another VPN app took over. */
    fun refresh() {
        val ctx = appContext ?: return
        scope.launch {
            lock.withLock {
                val up = runCatching { backend(ctx).getState(tunnel) == Tunnel.State.UP }.getOrDefault(false)
                _state.update { s ->
                    when {
                        up -> s.copy(status = KkVpnStatus.Connected)
                        s.status == KkVpnStatus.Connecting || s.status == KkVpnStatus.Error -> s
                        s.enabled && s.hasConfig && permissionIntent() != null -> s.copy(status = KkVpnStatus.NeedsPermission)
                        else -> s.copy(status = KkVpnStatus.Off)
                    }
                }
            }
        }
    }

    /** Android's "allow VPN connection" screen, or null when already allowed. */
    fun permissionIntent(): Intent? = appContext?.let { VpnService.prepare(it) }

    /** Checks and saves an IPVanish WireGuard config. Returns an error message if it isn't a valid config. */
    fun saveConfig(rawText: String): Result<Unit> {
        val text = rawText.trim().replace("\r\n", "\n")
        val parsed = runCatching { parse(text) }
        if (parsed.isFailure) {
            return Result.failure(IllegalArgumentException("That isn't a valid WireGuard config."))
        }
        prefs?.edit()?.putString(KEY_CONFIG, text)?.apply()
        _state.update { it.copy(hasConfig = true, serverLabel = serverLabelOf(text), errorMessage = null) }
        if (_state.value.enabled) reconnect()
        return Result.success(Unit)
    }

    fun removeConfig() {
        prefs?.edit()?.remove(KEY_CONFIG)?.putBoolean(KEY_ENABLED, false)?.apply()
        _state.update { it.copy(hasConfig = false, enabled = false, serverLabel = null, errorMessage = null) }
        disconnect()
    }

    /** Turn on only after [permissionIntent] has been granted. */
    fun setEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
        _state.update { it.copy(enabled = enabled, errorMessage = null) }
        if (enabled) connect() else disconnect()
    }

    fun setWholeDevice(wholeDevice: Boolean) {
        prefs?.edit()?.putBoolean(KEY_WHOLE_DEVICE, wholeDevice)?.apply()
        _state.update { it.copy(wholeDevice = wholeDevice) }
        if (_state.value.enabled) reconnect()
    }

    private fun reconnect() {
        connect()
    }

    private fun connect() {
        val ctx = appContext ?: return
        val text = prefs?.getString(KEY_CONFIG, null) ?: return
        scope.launch {
            lock.withLock {
                _state.update { it.copy(status = KkVpnStatus.Connecting, errorMessage = null) }
                val result = runCatching {
                    val config = parse(withRouting(text, ctx.packageName, _state.value.wholeDevice))
                    val b = backend(ctx)
                    // Restart cleanly so a new config or routing choice takes effect.
                    if (b.getState(tunnel) == Tunnel.State.UP) b.setState(tunnel, Tunnel.State.DOWN, null)
                    b.setState(tunnel, Tunnel.State.UP, config)
                }
                result.onSuccess {
                    _state.update { it.copy(status = KkVpnStatus.Connected, errorMessage = null) }
                }.onFailure { error ->
                    Log.w(TAG, "VPN connect failed", error)
                    val needsPermission = permissionIntent() != null
                    _state.update {
                        it.copy(
                            status = if (needsPermission) KkVpnStatus.NeedsPermission else KkVpnStatus.Error,
                            errorMessage = if (needsPermission) null else "Couldn't connect. Check the config or try another IPVanish server.",
                        )
                    }
                }
            }
        }
    }

    private fun disconnect() {
        val ctx = appContext ?: return
        scope.launch {
            lock.withLock {
                runCatching { backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) }
                    .onFailure { Log.w(TAG, "VPN disconnect failed", it) }
                _state.update { it.copy(status = KkVpnStatus.Off) }
            }
        }
    }

    private fun backend(ctx: Context): GoBackend =
        backend ?: GoBackend(ctx).also { backend = it }

    private fun parse(text: String): Config =
        BufferedReader(StringReader(text)).use { Config.parse(it) }

    /**
     * Unless "whole phone" is on, limits the tunnel to Kable Killers itself so the rest of the
     * phone keeps its normal connection. A config that already lists apps is left as is.
     */
    internal fun withRouting(text: String, packageName: String, wholeDevice: Boolean): String {
        if (wholeDevice) return text
        val lower = text.lowercase()
        if ("includedapplications" in lower || "excludedapplications" in lower) return text
        val lines = text.lines().toMutableList()
        val index = lines.indexOfFirst { it.trim().equals("[Interface]", ignoreCase = true) }
        if (index < 0) return text
        lines.add(index + 1, "IncludedApplications = $packageName")
        return lines.joinToString("\n")
    }

    internal fun serverLabelOf(text: String): String? =
        text.lines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Endpoint", ignoreCase = true) && it.contains('=') }
            ?.substringAfter('=')
            ?.trim()
            ?.substringBeforeLast(':')
            ?.takeIf { it.isNotBlank() }

    private fun openPrefs(ctx: Context): SharedPreferences =
        runCatching {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            Log.w(TAG, "Encrypted storage unavailable, using private app storage", it)
            ctx.getSharedPreferences("${PREFS_NAME}_plain", Context.MODE_PRIVATE)
        }
}
