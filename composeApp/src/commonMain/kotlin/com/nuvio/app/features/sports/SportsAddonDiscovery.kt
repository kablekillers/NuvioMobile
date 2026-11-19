package com.nuvio.app.features.sports

import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.supportsResource
import com.nuvio.app.features.catalog.fetchCatalogPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

internal object SportsAddonDiscovery {
    suspend fun find(
        league: SportsLeague,
        events: List<SportsEvent>,
        addons: List<ManagedAddon>,
        replays: Boolean,
    ): List<SportsCatalogMatch> = coroutineScope {
        val requests = addons.filter { it.isActive }.flatMap { addon ->
            val manifest = requireNotNull(addon.manifest)
            SportsAddonCatalogs.routes(manifest, league, replays).map { catalog ->
                async {
                    try {
                        val genre = catalog.extra.firstOrNull { it.name == "genre" && it.isRequired }
                        if (genre != null && "All" !in genre.options) return@async emptyList()
                        val page = withTimeoutOrNull(10_000) {
                            fetchCatalogPage(
                                manifestUrl = manifest.transportUrl,
                                type = catalog.type,
                                catalogId = catalog.id,
                                genre = genre?.let { "All" },
                                skip = if (catalog.extra.any { it.name == "skip" && it.isRequired }) 0 else null,
                            )
                        } ?: return@async emptyList()
                        page.items.mapNotNull { item ->
                            if (!manifest.supportsResource("stream", item.type, item.id)) return@mapNotNull null
                            val score = events.maxOfOrNull { SportsEventMatcher.score(it, item.name) } ?: 0
                            if (score == 0 && (!replays || !isLeagueCatalog(catalog.id, league))) {
                                return@mapNotNull null
                            }
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
