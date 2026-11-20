package com.nuvio.app.features.downloads

// No lock-screen live activity on Windows; the Downloads screen shows progress.
internal actual object DownloadsLiveStatusPlatform {
    actual fun onItemsChanged(items: List<DownloadItem>) = Unit
}
