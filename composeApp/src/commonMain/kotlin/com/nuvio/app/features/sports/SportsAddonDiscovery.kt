package com.nuvio.app.features.sports

import com.nuvio.app.isIos
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.supportsResource
import com.nuvio.app.features.catalog.fetchCatalogPage
import kotlinx.coroutines.CancellationException
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
        val requests = addons.filter { it.isActive }.flatMap { addon ->
            val manifest = requireNotNull(addon.manifest)
            SportsAddonCatalogs.routes(manifest, league, replays, expanded = !isIos).map { catalog ->
                async {
                    try {
                        val required = catalog.extra.filter { it.isRequired }
                        if (required.any { it.name != "genre" && it.name != "skip" }) return@async emptyList()
                        val genre = required.firstOrNull { it.name == "genre" }
                        val genreValue = genre?.options?.firstOrNull { it.equals("all", ignoreCase = true) }
                        if (genre != null && genreValue == null) return@async emptyList()
                        val skipRequired = required.any { it.name == "skip" }
                        val page = catalogRequests.withPermit {
                            withTimeoutOrNull(10_000) {
                                fetchCatalogPage(
                                    manifestUrl = manifest.transportUrl,
                                    type = catalog.type,
                                    catalogId = catalog.id,
                                    genre = genreValue,
                                    skip = if (skipRequired) 0 else null,
                                    includeInitialSkip = skipRequired,
                                )
                            }
                        } ?: return@async emptyList()
                        val unscoredAllowed = if (league.scoreboardPath == null) {
                            SportsAddonCatalogs.isScopedCatalog(catalog, league)
                        } else {
                            replays && isLeagueCatalog(catalog.id, league)
                        }
                        page.items.mapNotNull { item ->
                            ensureActive()
                            if (!manifest.supportsResource("stream", item.type, item.id)) return@mapNotNull null
                            val score = matcher.score(item.name)
                            if (score == 0 && !unscoredAllowed) return@mapNotNull null
                            val kind = when {
                                !replays -> SportsContentKind.EVENT
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
        requests.awaitAll().flatten().sortedByDescending { it.score }
            .distinctBy { Triple(it.addonName, it.item.type, it.item.id) }
            .take(40)
    }

    private val HighlightTitle = Regex("\\b(highlights?|recap)\\b", RegexOption.IGNORE_CASE)

    private fun isLeagueCatalog(id: String, league: SportsLeague): Boolean = when (league) {
        SportsLeague.NBA -> id.endsWith("_basketball")
        SportsLeague.MLB -> id.endsWith("_baseball")
        SportsLeague.NHL -> id.endsWith("_hockey")
        else -> false
    }
}
