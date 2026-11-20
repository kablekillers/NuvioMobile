package com.nuvio.app.core.ui

// Windows has no native tab bar; the Compose tab bar is used instead.

internal actual fun isLiquidGlassNativeTabBarSupported(): Boolean = false

internal actual fun publishLiquidGlassNativeTabBarEnabled(enabled: Boolean) = Unit

internal actual fun publishNativeTabBarVisible(visible: Boolean) = Unit

internal actual fun publishNativeSelectedTab(tabName: String) = Unit

internal actual fun publishNativeTabAccentColor(hexColor: String) = Unit

internal actual fun publishNativeTabTitles(
    home: String,
    search: String,
    library: String,
    profile: String,
) = Unit

internal actual fun publishNativeProfileTabIcon(
    name: String?,
    avatarColorHex: String?,
    avatarImageUrl: String?,
    avatarBackgroundColorHex: String?,
) = Unit
