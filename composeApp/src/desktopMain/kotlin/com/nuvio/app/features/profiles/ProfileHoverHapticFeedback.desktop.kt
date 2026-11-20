package com.nuvio.app.features.profiles

// PCs have no haptics.
internal actual object ProfileHoverHapticFeedback {
    actual fun prepare() = Unit
    actual fun perform() = Unit
    actual fun release() = Unit
}
