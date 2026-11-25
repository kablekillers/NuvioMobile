package com.nuvio.app.features.sports

import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.supportsResource
import com.nuvio.app.features.catalog.fetchCatalogPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sports Hub search: "packers", "brewers", "espn2", "ufc"… across ESPN games,
 * US channels, live events and every sports / live TV add-on that can search.
 */
internal object SportsHubSearch {
    private fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }

    /** Every word typed appears somewhere in the text ("green bay", "espn 2" also finds "ESPN2"). */
    fun matches(query: String, text: String): Boolean {
        val q = words(query)
        if (q.isEmpty()) return false
        val hay = words(text)
        val compact = hay.joinToString("")
        return q.all { w -> hay.any { it.startsWith(w) } } || compact.contains(q.joinToString(""))
    }

    fun games(query: String, events: List<SportsEvent>): List<SportsEvent> =
        events.distinctBy { it.league to it.id }
            .filter { event ->
                matches(
                    query,
                    listOf(event.title, event.league.label, event.catalogMatchTitle.orEmpty()) +
                        event.competitors.flatMap { listOf(it.name, it.abbreviation.orEmpty()) } +
                        event.broadcasts,
                )
            }
            .sortedBy {
                when (it.status) {
                    SportsEventStatus.LIVE -> 0
                    SportsEventStatus.SCHEDULED -> 1
                    else -> 2
                }
            }

    private fun matches(query: String, texts: List<String>) = matches(query, texts.joinToString(" "))

    fun channels(query: String, channels: List<SportsTvChannel>): List<SportsTvChannel> =
        channels.filter { channel -> matches(query, channel.name) || channel.sources.any { matches(query, it.item.name) } }

    fun events(query: String, events: List<SportsLiveEvent>): List<SportsLiveEvent> =
        events.filter { event -> matches(query, event.title + " " + event.sport) || event.sources.any { matches(query, it.item.name) } }

    private val sportsTypes = setOf("tv", "sport", "sports", "channel", "livetv", "live-tv", "live_tv", "events", "event")
    private val requests = Semaphore(8)

    /** Asks every add-on list that supports searching (sports / live TV types only, no movies). */
    suspend fun addons(query: String, addons: List<ManagedAddon>): List<SportsCatalogMatch> = withContext(Dispatchers.Default) {
        val jobs: List<Deferred<List<SportsCatalogMatch>>> = addons.filter { it.isActive }.flatMap { addon ->
            val manifest = addon.manifest ?: return@flatMap emptyList()
            manifest.catalogs.filter { catalog ->
                catalog.type in sportsTypes &&
                    catalog.extra.any { it.name == "search" } &&
                    catalog.extra.none { it.isRequired && it.name !in setOf("search", "skip") } &&
                    manifest.resources.any { it.name == "stream" && (it.types.isEmpty() || catalog.type in it.types) }
            }.take(8).map { catalog ->
                async {
                    val skipRequired = catalog.extra.any { it.name == "skip" && it.isRequired }
                    val page = requests.withPermit {
                        withTimeoutOrNull(12_000) {
                            runCatching {
                                fetchCatalogPage(
                                    manifestUrl = manifest.transportUrl,
                                    type = catalog.type,
                                    catalogId = catalog.id,
                                    search = query,
                                    skip = if (skipRequired) 0 else null,
                                    includeInitialSkip = skipRequired,
                                )
                            }.getOrElse { if (it is CancellationException) throw it; null }
                        }
                    } ?: return@async emptyList()
                    page.items
                        .filter { manifest.supportsResource("stream", it.type, it.id) }
                        .map { SportsCatalogMatch(addon.displayTitle, it, if (matches(query, it.name)) 2 else 1, SportsContentKind.EVENT) }
                }
            }
        }
        jobs.awaitAll().flatten()
            .distinctBy { Triple(it.addonName, it.item.type, it.item.id) }
            // Premium add-ons first; within each, titles that contain what was typed first.
            .sortedWith(compareBy({ SportsAddonPriority.rank(it.addonName) }, { -it.score }))
            .take(80)
    }
}
