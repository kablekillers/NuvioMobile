package com.nuvio.app.features.sports

import com.nuvio.app.isIos
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.supportsResource
import com.nuvio.app.features.catalog.fetchCatalogPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object SportsAddonDiscovery {
    private val catalogRequests = Semaphore(3)

    suspend fun find(
        league: SportsLeague,
        events: List<SportsEvent>,
        addons: List<ManagedAddon>,
        replays: Boolean,
    ): List<SportsCatalogMatch> = withContext(Dispatchers.Default) {
        val matcher = SportsEventMatcher.Prepared(events)
        val useRecordingMetadata = replays && !isIos && SportsRecordingClassifier.appliesTo(league)
        val requests = addons.filter { it.isActive }.flatMap { addon ->
            val manifest = requireNotNull(addon.manifest)
            SportsAddonCatalogs.routes(manifest, league, replays, expanded = !isIos).map { catalog ->
                async {
                    try {
                        val required = catalog.extra.filter { it.isRequired }
                        if (required.any { it.name !in setOf("search", "genre", "skip") }) return@async emptyList()
                        val genre = required.firstOrNull { it.name == "genre" }
                        val genreValue = genre?.options?.firstOrNull { it.equals("all", ignoreCase = true) }
                        if (genre != null && genreValue == null) return@async emptyList()
                        val searchRequired = required.any { it.name == "search" }
                        val searchTerms = if (searchRequired) sportsSearchQueries(events) else listOf<String?>(null)
                        val skipRequired = required.any { it.name == "skip" }
                        val pages = searchTerms.mapNotNull { term ->
                            catalogRequests.withPermit {
                                withTimeoutOrNull(10_000) {
                                    fetchCatalogPage(
                                        manifestUrl = manifest.transportUrl,
                                        type = catalog.type,
                                        catalogId = catalog.id,
                                        search = term,
                                        genre = genreValue,
                                        skip = if (skipRequired) 0 else null,
                                        includeInitialSkip = skipRequired,
                                    )
                                }
                            }
                        }
                        if (pages.isEmpty()) return@async emptyList()
                        val unscoredAllowed = if (league.scoreboardPath == null) {
                            SportsAddonCatalogs.isScopedCatalog(catalog, league)
                        } else {
                            replays && isLeagueCatalog(catalog.id, league)
                        }
                        pages.flatMap { it.items }.distinctBy { it.type to it.id }.mapNotNull { item ->
                            ensureActive()
                            if (!manifest.supportsResource("stream", item.type, item.id)) return@mapNotNull null
                            val score = matcher.score(item.name)
                            if (useRecordingMetadata) {
                                if (!SportsRecordingClassifier.accepts(manifest.id, catalog, item, league, score)) return@mapNotNull null
                            } else if (score == 0 && !unscoredAllowed) return@mapNotNull null
                            val kind = when {
                                !replays -> SportsContentKind.EVENT
                                useRecordingMetadata -> SportsRecordingClassifier.kind(manifest.id, catalog, item)
                                "recap" in catalog.id || HighlightTitle.containsMatchIn(item.name) -> SportsContentKind.HIGHLIGHT
                                else -> SportsContentKind.REPLAY
                            }
                            SportsCatalogMatch(addon.displayTitle, item, score, kind)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
        }
        val matches = requests.awaitAll().flatten().sortedByDescending { it.score }
            .distinctBy { Triple(it.addonName, it.item.type, it.item.id) }
        if (useRecordingMetadata) matches.groupBy { it.kind }.values.flatMap { it.take(40) }
        else matches.take(40)
    }

    /**
     * NFL RedZone and NFL Network are continuous channels, not ESPN game fixtures.
     * Discover only catalog-provided, stream-capable IDs; never invent a URL or ID.
     */
    suspend fun findNflChannels(addons: List<ManagedAddon>): List<SportsCatalogMatch> =
        withContext(Dispatchers.Default) {
            val requests: List<Deferred<List<SportsCatalogMatch>>> = addons.filter { it.isActive }.flatMap { addon ->
                val manifest = addon.manifest ?: return@flatMap emptyList()
                manifest.catalogs.filter { catalog ->
                    catalog.type in setOf("tv", "sport", "sports", "channel", "livetv", "live-tv", "live_tv") &&
                        !Regex("replays?|highlights?|recaps?", RegexOption.IGNORE_CASE)
                            .containsMatchIn("${catalog.id} ${catalog.name}") &&
                        (Regex("nfl|sport|live|channel|tv", RegexOption.IGNORE_CASE)
                            .containsMatchIn("${catalog.id} ${catalog.name}") ||
                            Regex("sport|nfl|live", RegexOption.IGNORE_CASE)
                                .containsMatchIn(manifest.id))
                }.take(10).map { catalog ->
                    async {
                        try {
                            val required = catalog.extra.filter { it.isRequired }
                            if (required.any { it.name !in setOf("search", "genre", "skip") }) return@async emptyList()
                            val genre = required.firstOrNull { it.name == "genre" }
                            val genreValue = genre?.options?.firstOrNull { it.equals("all", true) }
                            if (genre != null && genreValue == null) return@async emptyList()
                            val page = catalogRequests.withPermit {
                                withTimeoutOrNull(10_000) {
                                    fetchCatalogPage(
                                        manifestUrl = manifest.transportUrl,
                                        type = catalog.type,
                                        catalogId = catalog.id,
                                        search = if (required.any { it.name == "search" }) "NFL" else null,
                                        genre = genreValue,
                                        skip = if (required.any { it.name == "skip" }) 0 else null,
                                        includeInitialSkip = required.any { it.name == "skip" },
                                    )
                                }
                            } ?: return@async emptyList()
                            page.items.mapNotNull { item ->
                                ensureActive()
                                if (nflChannelLabel(item.name) == null ||
                                    !manifest.supportsResource("stream", item.type, item.id)
                                ) return@mapNotNull null
                                SportsCatalogMatch(addon.displayTitle, item, 100, SportsContentKind.EVENT)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                }
            }
            requests.awaitAll().flatten()
                .distinctBy { Triple(it.addonName, it.item.type, it.item.id) }
                .sortedWith(compareBy({ if (nflChannelLabel(it.item.name) == "NFL RedZone") 0 else 1 }, { it.addonName }))
                .take(20)
        }

    private fun nflChannelLabel(title: String): String? {
        if (Regex("replay|highlight|recap|condensed", RegexOption.IGNORE_CASE).containsMatchIn(title)) return null
        val normalized = title.lowercase().replace(Regex("[^a-z0-9]"), "")
        return when {
            "nflredzone" in normalized || "redzonenfl" in normalized -> "NFL RedZone"
            "nflnetwork" in normalized || "nflchannel" in normalized -> "NFL Network"
            else -> null
        }
    }

    /**
     * Some add-ons index games by one team's name instead of the ESPN fixture title.
     * Search both formats without manufacturing stream identifiers.
     */
    internal fun sportsSearchQueries(events: List<SportsEvent>): List<String> =
        events.flatMap { event ->
            listOf(event.title) + event.competitors.map { it.name }
        }.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(12)

    private val HighlightTitle = Regex("\\b(highlights?|recap)\\b", RegexOption.IGNORE_CASE)

    private fun isLeagueCatalog(id: String, league: SportsLeague): Boolean = when (league) {
        SportsLeague.NBA -> id.endsWith("_basketball")
        SportsLeague.MLB -> id.endsWith("_baseball")
        SportsLeague.NHL -> id.endsWith("_hockey")
        else -> false
    }
}
