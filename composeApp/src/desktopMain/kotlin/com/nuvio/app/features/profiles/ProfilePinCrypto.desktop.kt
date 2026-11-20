package com.nuvio.app.features.profiles

import java.security.MessageDigest

actual object ProfilePinCrypto {
    actual fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}
