package com.nuvio.app.features.auth

import androidx.compose.runtime.Composable

/** Renders the device-link verification URL as a scannable QR code when supported. */
@Composable
internal expect fun DeviceLoginQr(url: String)
