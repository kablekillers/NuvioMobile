package com.nuvio.app.core.auth

import java.net.InetAddress

internal actual fun currentDeviceClientMetadata(): DeviceClientMetadata {
    val computerName = System.getenv("COMPUTERNAME")?.trim()?.takeIf { it.isNotBlank() }
        ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        ?: "Windows PC"
    val platform = "${System.getProperty("os.name")} ${System.getProperty("os.version")}".trim()
    return DeviceClientMetadata(deviceName = computerName, platform = platform)
}
