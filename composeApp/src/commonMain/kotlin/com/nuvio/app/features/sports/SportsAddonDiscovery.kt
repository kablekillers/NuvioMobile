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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal object SportsAddonDiscovery {
    private val catalogRequests = Semaphore(3)

    /** Results stay fresh long enough to cover open a stream → Back → pick another. */
    private const val CACHE_TTL_MILLIS = 5 * 60_000L
    private const val MAX_CACHED_RESULTS = 40
    private val cacheLock = Mutex()
    private val resultCache = LinkedHashMap<String, Pair<Long, List<SportsCatalogMatch>>>()

    private fun now(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    private fun addonKey(addons: List<ManagedAddon>): String =
        addons.filter { it.isActive }.map { it.manifestUrl }.sorted().joinToString(",")

    private suspend fun cached(key: String): List<SportsCatalogMatch>? = cacheLock.withLock {
        val entry = resultCache[key] ?: return@withLock null
        if (now() - entry.first > CACHE_TTL_MILLIS) {
            resultCache.remove(key)
            null
        } else entry.second
    }

    /** Non-suspending peek so a screen can show the last results on its first frame. */
    fun peek(league: SportsLeague, events: List<SportsEvent>, addons: List<ManagedAddon>, replays: Boolean): List<SportsCatalogMatch>? {
        val entry = runCatching { resultCache[findKey(league, events, addons, replays)] }.getOrNull() ?: return null
        return entry.second.takeIf { now() - entry.first <= CACHE_TTL_MILLIS }
    }

    fun peekNflChannels(addons: List<ManagedAddon>): List<SportsCatalogMatch>? {
        val entry = runCatching { resultCache["nfl-channels|" + addonKey(addons)] }.getOrNull() ?: return null
        return entry.second.takeIf { now() - entry.first <= CACHE_TTL_MILLIS }
    }

    private suspend fun store(key: String, value: List<SportsCatalogMatch>) = cacheLock.withLock {
        resultCache.remove(key)
        resultCache[key] = now() to value
        while (resultCache.size > MAX_CACHED_RESULTS) resultCache.remove(resultCache.keys.first())
    }

    // Keyed by game IDs (not scores), so live score refreshes and re-opening the
    // same game reuse the last search instead of asking every add-on again.
    private fun findKey(league: SportsLeague, events: List<SportsEvent>, addons: List<ManagedAddon>, replays: Boolean) =
        listOf(league.name, replays.toString(), events.map { it.id }.sorted().joinToString(","), addonKey(addons))
            .joinToString("|")

    suspend fun find(
        league: SportsLeague,
        events: List<SportsEvent>,
        addons: List<ManagedAddon>,
        replays: Boolean,
    ): List<SportsCatalogMatch> {
        val key = findKey(league, events, addons, replays)
        cached(key)?.let { return it }
        val found = findUncached(league, events, addons, replays)
        // Don't hold on to an empty result (e.g. a network blip); try again next time.
        if (found.isNotEmpty()) store(key, found)
        return found
    }

    private suspend fun findUncached(
        league: SportsLeague,
        events: List<SportsEvent>,
        addons: List<ManagedAddon>,
        replays: Boolean,
    ): List<SportsCatalogMatch> = withContext(Dispatchers.Default) {
        val matcher = SportsEventMatcher.Prepared(events)
        val useRecordingMetadata = replays && !isIos && SportsRecordingClassifier.appliesTo(league)
        // Live channels (CNCBridge etc.) carrying these games: their items are channel names, not game titles.
        val channelTerms = if (replays) emptySet() else SportsChannelMatcher.terms(league, events)
        val channelQueries = if (replays) emptyList() else SportsChannelMatcher.searchQueries(league, events)
        val requests: List<Deferred<List<SportsCatalogMatch>>> = addons.filter { it.isActive }.flatMap { addon ->
            val manifest = addon.manifest ?: return@flatMap emptyList<Deferred<List<SportsCatalogMatch>>>()
            val listed = SportsAddonCatalogs.isListedProvider(manifest)
            SportsAddonCatalogs.routes(manifest, league, replays, expanded = !isIos).map { catalog ->
                async {
                    try {
                        val required = catalog.extra.filter { it.isRequired }
                        if (required.any { it.name !in setOf("search", "genre", "skip") }) return@async emptyList()
                        val genres = SportsAddonCatalogs.genreValues(catalog, league)
                        if (genres.isEmpty()) return@async emptyList()
                        val searchRequired = required.any { it.name == "search" }
                        val searchTerms: List<String?> = if (searchRequired) {
                            // Unlisted live TV add-ons are only asked for the networks, not every team.
                            if (listed) (deepSearchQueries(league, events) + channelQueries).distinct() else channelQueries
                        } else listOf(null)
                        if (searchTerms.isEmpty()) return@async emptyList()
                        val skipRequired = required.any { it.name == "skip" }
                        val pages = genres.flatMap { genreValue -> searchTerms.map { genreValue to it } }.mapNotNull { (genreValue, term) ->
                            catalogRequests.withPermit {
                                withTimeoutOrNull(15_000) {
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
                        // Long live lists (hundreds of events) page with "skip": read a few more
                        // pages so games further down the list aren't missed.
                        val morePages = if (!searchRequired && catalog.extra.any { it.name == "skip" } &&
                            pages.size == 1 && pages.first().rawItemCount >= 20
                        ) {
                            var next = pages.first().nextSkip
                            buildList {
                                repeat(3) {
                                    val skipValue = next ?: return@repeat
                                    val page = catalogRequests.withPermit {
                                        withTimeoutOrNull(15_000) {
                                            runCatching {
                                                fetchCatalogPage(
                                                    manifestUrl = manifest.transportUrl,
                                                    type = catalog.type,
                                                    catalogId = catalog.id,
                                                    genre = genres.first(),
                                                    skip = skipValue,
                                                )
                                            }.getOrElse { if (it is CancellationException) throw it; null }
                                        }
                                    } ?: return@repeat
                                    add(page)
                                    next = if (page.rawItemCount >= 20) page.nextSkip else null
                                }
                            }
                        } else emptyList()
                        val allPages = pages + morePages
                        val unscoredAllowed = if (league.scoreboardPath == null) {
                            SportsAddonCatalogs.isScopedCatalog(catalog, league)
                        } else {
                            (replays && isLeagueCatalog(catalog.id, league)) ||
                                (!replays && SportsAddonCatalogs.isCncBridgeScopedCatalog(manifest, catalog, league))
                        }
                        allPages.flatMap { it.items }.distinctBy { it.type to it.id }.mapNotNull { item ->
                            ensureActive()
                            if (!manifest.supportsResource("stream", item.type, item.id)) return@mapNotNull null
                            // Replays: drop recordings labelled for another sport (MLB "Kansas City" in NFL).
                            if (replays && !useRecordingMetadata && SportsAddonCatalogs.namesOtherSport(
                                    (item.genres + item.id.replace('_', ' ') + item.name + catalog.id.replace('_', ' ') + catalog.name)
                                        .joinToString(" "),
                                    league,
                                )
                            ) return@mapNotNull null
                            val gameScore = matcher.score(item.name)
                            // A channel showing the game ranks after real game matches.
                            val score = if (gameScore == 0 && SportsChannelMatcher.matches(item.name, channelTerms)) 1 else gameScore
                            if (useRecordingMetadata) {
                                if (!SportsRecordingClassifier.accepts(manifest.id, catalog, item, league, score)) return@mapNotNull null
                            } else if (score == 0 && !unscoredAllowed) return@mapNotNull null
                            val kind = when {
                                !replays -> SportsContentKind.EVENT
                                useRecordingMetadata -> SportsRecordingClassifier.kind(manifest.id, catalog, item)
                                replays -> SportsRecordingClassifier.kind(manifest.id, catalog, item)
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
        // Premium add-ons first (Sports Streams, Flix-Streams, StremVerse, Nebula), best match first within each.
        val matches = requests.awaitAll().flatten()
            .sortedWith(compareBy({ SportsAddonPriority.rank(it.addonName) }, { -it.score }))
            .distinctBy { Triple(it.addonName, it.item.type, it.item.id) }
        if (useRecordingMetadata) matches.groupBy { it.kind }.values.flatMap { it.take(40) }
        else matches.take(40)
    }

    /**
     * NFL RedZone and NFL Network are continuous channels, not ESPN game fixtures.
     * Discover only catalog-provided, stream-capable IDs; never invent a URL or ID.
     */
    suspend fun findNflChannels(addons: List<ManagedAddon>): List<SportsCatalogMatch> {
        val key = "nfl-channels|" + addonKey(addons)
        cached(key)?.let { return it }
        val found = findNflChannelsUncached(addons)
        if (found.isNotEmpty()) store(key, found)
        return found
    }

    private suspend fun findNflChannelsUncached(addons: List<ManagedAddon>): List<SportsCatalogMatch> =
        withContext(Dispatchers.Default) {
            val requests: List<Deferred<List<SportsCatalogMatch>>> = addons.filter { it.isActive }.flatMap { addon ->
                val manifest = addon.manifest ?: return@flatMap emptyList()
                manifest.catalogs.filter { catalog ->
                    catalog.type in setOf("tv", "sport", "sports", "channel", "livetv", "live-tv", "live_tv") &&
                        !Regex("replays?|highlights?|recaps?", RegexOption.IGNORE_CASE)
                            .containsMatchIn("${catalog.id} ${catalog.name}") &&
                        (Regex("nfl|sport|live|channel|tv", RegexOption.IGNORE_CASE)
                            .containsMatchIn("${catalog.id} ${catalog.name}") ||
                            Regex("sport|nfl|live|cncbridge|cncverse|ustv|usatv|plavio|flix", RegexOption.IGNORE_CASE)
                                .containsMatchIn(manifest.id + " " + manifest.name))
                }.take(10).map { catalog ->
                    async {
                        try {
                            val required = catalog.extra.filter { it.isRequired }
                            if (required.any { it.name !in setOf("search", "genre", "skip") }) return@async emptyList()
                            val genres = SportsAddonCatalogs.genreValues(catalog, SportsLeague.NFL)
                            if (genres.isEmpty()) return@async emptyList()
                            val queries: List<String?> = if (required.any { it.name == "search" }) {
                                listOf("NFL", "NFL Network", "NFL RedZone", "RedZone", "Football")
                            } else listOf(null)
                            val pages = genres.flatMap { genreValue -> queries.map { genreValue to it } }.mapNotNull { (genreValue, query) ->
                                catalogRequests.withPermit {
                                    withTimeoutOrNull(10_000) {
                                        fetchCatalogPage(
                                            manifestUrl = manifest.transportUrl,
                                            type = catalog.type,
                                            catalogId = catalog.id,
                                            search = query,
                                            genre = genreValue,
                                            skip = if (required.any { it.name == "skip" }) 0 else null,
                                            includeInitialSkip = required.any { it.name == "skip" },
                                        )
                                    }
                                }
                            }
                            pages.flatMap { it.items }.distinctBy { it.type to it.id }.mapNotNull { item ->
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
            SportsAddonPriority.sorted(requests.awaitAll().flatten()) { it.addonName }
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

    /**
     * Broader provider searches: a game may be indexed by its matchup, one team,
     * an abbreviated team name, or its competition rather than ESPN's title.
     * Keep the request budget bounded per catalog to avoid flooding add-ons.
     * Returned items still require stream support and league/event filtering.
     */
    internal fun deepSearchQueries(league: SportsLeague, events: List<SportsEvent>): List<String> {
        val competition = when (league) {
            SportsLeague.COLLEGE_FOOTBALL -> listOf("College Football", "NCAA Football")
            SportsLeague.NCAA_MEN -> listOf("NCAA Basketball", "College Basketball")
            SportsLeague.NCAA_WOMEN -> listOf("Women's College Basketball")
            SportsLeague.NFL -> listOf("NFL")
            SportsLeague.NBA -> listOf("NBA")
            SportsLeague.MLB -> listOf("MLB")
            SportsLeague.NHL -> listOf("NHL")
            SportsLeague.UFC -> listOf("UFC")
            else -> listOf(league.label)
        }
        val liveFirst = events.sortedBy { when (it.status) {
            SportsEventStatus.LIVE -> 0
            SportsEventStatus.SCHEDULED -> 1
            else -> 2
        } }
        val matches = liveFirst.flatMap { event ->
            listOf(event.title, event.catalogMatchTitle.orEmpty()) +
                event.competitors.flatMap { listOf(it.name, it.abbreviation.orEmpty()) }
        }
        return (matches + competition).map { it.trim() }
            .filter { it.length >= 3 }.distinct().take(18)
    }

    private val HighlightTitle = Regex("\\b(highlights?|recap)\\b", RegexOption.IGNORE_CASE)

    private fun isLeagueCatalog(id: String, league: SportsLeague): Boolean = when (league) {
        SportsLeague.NBA -> id.endsWith("_basketball")
        SportsLeague.MLB -> id.endsWith("_baseball")
        SportsLeague.NHL -> id.endsWith("_hockey")
        else -> false
    }
}
