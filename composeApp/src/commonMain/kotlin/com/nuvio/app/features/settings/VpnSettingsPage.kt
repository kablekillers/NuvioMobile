package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable

/** True on builds that ship the built-in Kable Killers VPN (WireGuard, for IPVanish configs). */
internal expect val isVpnSupported: Boolean

/** Body of the Settings > VPN page. The config, its private key and the on/off state stay on this device only. */
@Composable
internal expect fun VpnSettingsBody(isTablet: Boolean)

internal fun LazyListScope.vpnSettingsContent(isTablet: Boolean) {
    item(key = "kk_vpn_body") {
        VpnSettingsBody(isTablet = isTablet)
    }
}
