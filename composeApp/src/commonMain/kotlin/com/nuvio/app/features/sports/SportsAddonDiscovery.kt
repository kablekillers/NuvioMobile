package com.nuvio.app.features.sports

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.supportsResource
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

internal object SportsAddonDiscovery {
    data class CatalogSource(val addon: ManagedAddon, val catalog: AddonCatalog)

    fun catalogs(addons: List<ManagedAddon>): List<CatalogSource> = addons.filter { it.isActive }.flatMap { addon ->
        SportsAddonCatalogs.browseCatalogs(requireNotNull(addon.manifest)).map { CatalogSource(addon, it) }
    }

    suspend fun browse(source: CatalogSource): List<SportsCatalogMatch> {
        val (addon, catalog) = source
        return checkNotNull(loadCatalog(addon, catalog)).map {
            SportsCatalogMatch(addon.displayTitle, it, score = 0, kind = SportsContentKind.EVENT)
        }
    }

    private suspend fun loadCatalog(addon: ManagedAddon, catalog: AddonCatalog): List<MetaPreview>? {
        val manifest = requireNotNull(addon.manifest)
        val skipRequired = catalog.extra.any { it.name == "skip" && it.isRequired }
        return withTimeoutOrNull(10_000) {
            fetchCatalogPage(
                manifestUrl = manifest.transportUrl,
                type = catalog.type,
                catalogId = catalog.id,
                genre = SportsAddonCatalogs.genre(catalog),
                skip = if (skipRequired) 0 else null,
                includeInitialSkip = skipRequired,
            ).items.filter { manifest.supportsResource("stream", it.type, it.id) }
        }
    }

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
                        val items = loadCatalog(addon, catalog) ?: return@async emptyList()
                        items.mapNotNull { item ->
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
