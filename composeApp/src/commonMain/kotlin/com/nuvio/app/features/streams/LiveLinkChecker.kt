package com.nuvio.app.features.streams

import androidx.compose.runtime.mutableStateMapOf
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import com.nuvio.app.features.sports.SportsMultiviewSourceSelection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class LinkHealth { Checking, Ok, Dead }

/**
 * Quick reachability test for a direct live link with the headers it needs:
 * true = answers with playable media / a real HLS playlist, false = dead
 * (error status, HTML error page, empty playlist, no answer), null = can't tell.
 */
internal expect suspend fun probeLiveLink(url: String, headers: Map<String, String>): Boolean?

/**
 * Tests live sports / TV links in the background and hides the ones that are
 * clearly dead. Links show right away while they're tested.
 * Results are kept for 10 minutes and shared by the stream list and Multi-View.
 * Links that aren't plain direct URLs (torrents, debrid, external apps) aren't
 * tested and always show.
 */
internal object LiveLinkChecker {
    private const val TTL_MILLIS = 10 * 60_000L
    private val liveTypes = setOf("tv", "sport", "sports", "channel", "channels", "livetv", "live", "event", "events")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Semaphore(6)
    private val results = mutableStateMapOf<String, Pair<LinkHealth, Long>>()

    fun appliesTo(type: String?): Boolean = type?.lowercase()?.trim() in liveTypes

    private fun now() = kotlin.time.Clock.System.now().toEpochMilliseconds()

    private fun headersOf(stream: StreamItem) = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)

    private fun key(stream: StreamItem): String? = SportsMultiviewSourceSelection.directUrl(stream)
        ?.let { url -> url + "|" + headersOf(stream).entries.sortedBy { it.key }.joinToString() }

    /** null when the link isn't tested (always shown). */
    fun health(stream: StreamItem): LinkHealth? {
        val key = key(stream) ?: return null
        return results[key]?.first ?: LinkHealth.Checking
    }

    fun check(streams: List<StreamItem>) {
        val time = now()
        streams.forEach { stream ->
            val key = key(stream) ?: return@forEach
            val existing = results[key]
            if (existing != null && (existing.first == LinkHealth.Checking || time - existing.second < TTL_MILLIS)) return@forEach
            results[key] = LinkHealth.Checking to time
            val url = SportsMultiviewSourceSelection.directUrl(stream) ?: return@forEach
            val headers = headersOf(stream)
            scope.launch {
                val alive = gate.withPermit {
                    // Only a clear "dead" counts, and it has to happen twice. Slow or
                    // unclear answers count as working so a good link is never hidden.
                    val first = withTimeoutOrNull(20_000) { probeLiveLink(url, headers) } ?: true
                    first || (withTimeoutOrNull(20_000) { probeLiveLink(url, headers) } ?: true)
                }
                withContext(Dispatchers.Main) {
                    results[key] = (if (alive) LinkHealth.Ok else LinkHealth.Dead) to now()
                }
            }
        }
    }
}
