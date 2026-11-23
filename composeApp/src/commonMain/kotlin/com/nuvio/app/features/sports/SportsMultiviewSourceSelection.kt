package com.nuvio.app.features.sports

import com.nuvio.app.features.streams.StreamItem

/**
 * Multiview only accepts already resolved direct HTTP media streams.
 * Do not bypass existing torrent, debrid, client-resolve, or server preparation
 * flows by treating their metadata as a playable URL.
 */
internal object SportsMultiviewSourceSelection {
    fun directUrl(stream: StreamItem): String? {
        if (stream.needsServerPreparation ||
            stream.needsLocalDebridResolve ||
            stream.isTorrentStream ||
            stream.clientResolve != null ||
            stream.shouldOpenExternally ||
            stream.youTubeIdToResolve != null
        ) return null

        return stream.playableDirectUrl?.takeIf { url ->
            url.startsWith("https://", ignoreCase = true) ||
                url.startsWith("http://", ignoreCase = true)
        }
    }
}
