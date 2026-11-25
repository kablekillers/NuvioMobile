package com.nuvio.app.features.sports

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.supportsResource
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One US sports channel (e.g. "ESPN2") with every add-on link found for it. */
internal data class SportsTvChannel(val name: String, val sources: List<SportsCatalogMatch>)

/** One live event with every add-on link found for it, grouped by sport. */
internal data class SportsLiveEvent(val title: String, val sport: String, val sources: List<SportsCatalogMatch>)

/**
 * Sweeps every enabled add-on's live TV and live event lists:
 *  - US / English sports channels (ESPN, ESPN2, FS1, NFL Network…), with
 *    foreign feeds ("SA| ESPN 1", "CO| CLARO-ESPN2", "ESPN Deportes") left out.
 *  - Live events (any sport), merged across add-ons by title.
 */
internal object SportsLiveTv {
    /** Display name to compact names that mean that channel, in display order. */
    private val usChannels: List<Pair<String, List<String>>> = listOf(
        "ESPN" to listOf("espn", "espnusa", "espnus", "espnuhd", "espn4k"),
        "ESPN2" to listOf("espn2", "espntwo"),
        "ESPNU" to listOf("espnu", "espncollege"),
        "ESPNews" to listOf("espnews", "espnnews"),
        "ACC Network" to listOf("accnetwork", "accn"),
        "SEC Network" to listOf("secnetwork", "secn"),
        "Big Ten Network" to listOf("bigtennetwork", "btn", "b1g", "bignetwork"),
        "FOX" to listOf("fox", "foxusa", "foxnetwork"),
        "FS1" to listOf("fs1", "foxsports1"),
        "FS2" to listOf("fs2", "foxsports2"),
        "CBS" to listOf("cbs", "cbsusa"),
        "CBS Sports Network" to listOf("cbssportsnetwork", "cbssn", "cbssports"),
        "NBC" to listOf("nbc", "nbcusa"),
        "NBC Sports" to listOf("nbcsports", "nbcsn"),
        "ABC" to listOf("abc", "abcusa"),
        "TNT" to listOf("tnt", "tntsports", "tntusa"),
        "TBS" to listOf("tbs"),
        "truTV" to listOf("trutv"),
        "NFL Network" to listOf("nflnetwork", "nfln", "nflchannel"),
        "NFL RedZone" to listOf("nflredzone", "redzone", "redzonenfl"),
        "NBA TV" to listOf("nbatv"),
        "MLB Network" to listOf("mlbnetwork", "mlbn"),
        "NHL Network" to listOf("nhlnetwork", "nhln"),
        "Golf Channel" to listOf("golfchannel"),
        "Tennis Channel" to listOf("tennischannel"),
        "Fox Soccer Plus" to listOf("foxsoccerplus", "foxsoccer"),
        "beIN Sports USA" to listOf("beinsportsusa", "beinusa"),
        "SportsNet New York" to listOf("sny", "sportsnetnewyork"),
        "YES Network" to listOf("yesnetwork"),
        "Marquee Sports" to listOf("marquee", "marqueesports"),
        "Bally / FanDuel Sports" to listOf("ballysports", "fanduelsports", "fanduelsportsnetwork"),
    )
    private val aliasToChannel: Map<String, String> =
        usChannels.flatMap { (name, aliases) -> aliases.map { it to name } }.toMap()
    private val channelOrder = usChannels.mapIndexed { index, (name, _) -> name to index }.toMap()

    // Region tags allowed in front of a channel ("US| ESPN", "USA: FS1", "EN - ESPN2").
    private val usTags = setOf("us", "usa", "en", "eng", "english", "na", "4k", "uhd", "fhd", "hd", "sd", "hevc", "vip", "24 7", "247")
    // Foreign / Spanish feeds of US-named channels.
    private val foreign = Regex(
        """\b(claro|deportes|latam|latino|latin|mexico|méxico|mx|brasil|brazil|argentina|colombia|chile|peru|caribe|caribbean|africa|españa|espana|spain|arabia|mena|india|canada|australia|nz|uk|ireland|portugal|france|italia|deutsch|nederland|netherlands|holland|español|espanol|spanish|sur|norte)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val excluded = Regex("""\b(news|replays?|highlights?|recaps?|radio|weather)\b""", RegexOption.IGNORE_CASE)
    private val quality = setOf("hd", "fhd", "uhd", "sd", "4k", "hevc", "1080p", "720p", "backup", "alt", "east", "west", "live", "tv", "channel", "us", "usa", "feed")

    /** The US channel this item is, or null (foreign feed, news, not a known channel). */
    internal fun usChannelName(title: String): String? {
        val raw = title.trim()
        if (excluded.containsMatchIn(raw)) return null
        // "SA| ESPN 1 HD", "CO|CLARO-ESPN2", "MX: FOX Sports" -> tag before | or :
        val tagMatch = Regex("""^\s*[\[(]?([A-Za-z0-9 ]{1,8})[\])]?\s*[|:]\s*(.+)$""").find(raw)
        val name = if (tagMatch != null) {
            val tag = tagMatch.groupValues[1].trim().lowercase()
            if (tag !in usTags) return null
            tagMatch.groupValues[2]
        } else raw
        if (foreign.containsMatchIn(name)) return null
        val words = name.lowercase().replace("+", " plus").split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        // Longest run of words that spells a channel: "ESPN 2" -> espn2, "Fox Sports 1" -> foxsports1.
        var best: Pair<Int, String>? = null
        for (start in words.indices) {
            for (end in minOf(words.size, start + 4) - 1 downTo start) {
                val run = words.subList(start, end + 1).joinToString("")
                val channel = aliasToChannel[run] ?: continue
                // "ESPN 1" / "TNT Sports 1" style numbering is a foreign feed (Africa, UK…).
                // Local stations ("FOX 5 New York") are fine.
                val next = words.getOrNull(end + 1)
                if (next != null && next.all(Char::isDigit) && run in setOf("espn", "tnt", "tntsports")) break
                val length = end - start + 1
                if (best == null || length > best.first) best = length to channel
                break
            }
        }
        val channel = best?.second ?: return null
        // A local station needs nothing but the network plus quality / region words:
        // "FOX" yes, "FOX 5 New York" yes (that's a FOX affiliate), "Fox Life" no.
        if (channel in setOf("FOX", "CBS", "NBC", "ABC")) {
            val others = words.filterNot { it in quality || it.all(Char::isDigit) || it == channel.lowercase() }
            if (others.size > 3) return null
        }
        return channel
    }

    private val eventLabel = Regex("""\b(events?|live\s*now|live\s*sports?|live\s*events?|matches|fixtures|ppv|games?)\b""", RegexOption.IGNORE_CASE)
    private val channelLabel = Regex("""\b(tv|channels?|live|iptv|24\s*/?\s*7|sports?|usa?|united states|america|en|english)\b""", RegexOption.IGNORE_CASE)
    private val liveTypes = setOf("tv", "sport", "sports", "channel", "livetv", "live-tv", "live_tv", "events", "event")
    private val replayLabel = Regex("""replays?|highlights?|recaps?|vod""", RegexOption.IGNORE_CASE)
    private val requests = Semaphore(8)

    private fun liveCatalogs(manifest: AddonManifest, label: Regex): List<AddonCatalog> =
        manifest.catalogs.filter { catalog ->
            val text = "${catalog.id} ${catalog.name}"
            catalog.type in liveTypes && !replayLabel.containsMatchIn(text) && label.containsMatchIn(text) &&
                manifest.resources.any { it.name == "stream" && (it.types.isEmpty() || catalog.type in it.types) }
        }.take(12)

    /** Every page we're allowed to ask for (up to [maxPages]); catalogs that page via "skip". */
    private suspend fun pages(
        manifest: AddonManifest,
        catalog: AddonCatalog,
        search: String?,
        genre: String?,
        maxPages: Int,
    ): List<MetaPreview> {
        val canPage = search == null && catalog.extra.any { it.name == "skip" }
        val skipRequired = catalog.extra.any { it.name == "skip" && it.isRequired }
        val items = mutableListOf<MetaPreview>()
        var skip: Int? = if (skipRequired) 0 else null
        repeat(if (canPage) maxPages else 1) {
            val page = requests.withPermit {
                withTimeoutOrNull(15_000) {
                    runCatching {
                        fetchCatalogPage(
                            manifestUrl = manifest.transportUrl,
                            type = catalog.type,
                            catalogId = catalog.id,
                            genre = genre,
                            search = search,
                            skip = skip,
                            includeInitialSkip = skipRequired,
                        )
                    }.getOrElse { if (it is CancellationException) throw it; null }
                }
            } ?: return items
            items += page.items
            if (page.rawItemCount < 20 || page.nextSkip == null) return items
            skip = page.nextSkip
        }
        return items
    }

    private suspend fun sweep(
        addons: List<ManagedAddon>,
        label: Regex,
        queries: List<String>,
        genreLeague: SportsLeague?,
        maxPages: Int,
        allGenres: Boolean = false,
        keep: (AddonManifest, MetaPreview) -> Boolean,
    ): List<SportsCatalogMatch> = withContext(Dispatchers.Default) {
        val jobs: List<Deferred<List<SportsCatalogMatch>>> = addons.filter { it.isActive }.flatMap { addon ->
            val manifest = addon.manifest ?: return@flatMap emptyList()
            liveCatalogs(manifest, label).map { catalog ->
                async {
                    val required = catalog.extra.filter { it.isRequired }
                    if (required.any { it.name !in setOf("search", "genre", "skip") }) return@async emptyList()
                    val genres = if (genreLeague != null) SportsAddonCatalogs.genreValues(catalog, genreLeague)
                    else catalog.extra.firstOrNull { it.isRequired && it.name == "genre" }?.let { genre ->
                        val picked = genre.options.filter { Regex("all|sport|live|event|usa?|united", RegexOption.IGNORE_CASE).containsMatchIn(it) }.take(2)
                        // Event lists split by sport (Soccer, Basketball…): ask each one.
                        if (picked.isEmpty() && allGenres) genre.options.take(10) else picked
                    } ?: listOf(null)
                    if (genres.isEmpty()) return@async emptyList()
                    val searches: List<String?> = if (required.any { it.name == "search" }) queries else listOf(null)
                    genres.flatMap { genre -> searches.map { genre to it } }
                        .flatMap { (genre, search) -> pages(manifest, catalog, search, genre, maxPages) }
                        .distinctBy { it.type to it.id }
                        .filter { manifest.supportsResource("stream", it.type, it.id) && keep(manifest, it) }
                        .map { SportsCatalogMatch(addon.displayTitle, it, 100, SportsContentKind.EVENT) }
                }
            }
        }
        jobs.awaitAll().flatten().distinctBy { Triple(it.addonName, it.item.type, it.item.id) }
    }

    private const val TTL_MILLIS = 3 * 60_000L
    private val lock = Mutex()
    private var channelCache: Pair<Pair<String, Long>, List<SportsTvChannel>>? = null
    private var eventCache: Pair<Pair<String, Long>, List<SportsLiveEvent>>? = null
    private fun now() = kotlin.time.Clock.System.now().toEpochMilliseconds()
    private fun addonKey(addons: List<ManagedAddon>) = addons.filter { it.isActive }.map { it.manifestUrl }.sorted().joinToString(",")

    fun peekChannels(addons: List<ManagedAddon>): List<SportsTvChannel>? =
        channelCache?.takeIf { it.first.first == addonKey(addons) && now() - it.first.second < TTL_MILLIS }?.second

    fun peekEvents(addons: List<ManagedAddon>): List<SportsLiveEvent>? =
        eventCache?.takeIf { it.first.first == addonKey(addons) && now() - it.first.second < TTL_MILLIS }?.second

    suspend fun usChannels(addons: List<ManagedAddon>, force: Boolean = false): List<SportsTvChannel> {
        val key = addonKey(addons)
        if (!force) peekChannels(addons)?.let { return it }
        val found = sweep(
            addons = addons,
            label = channelLabel,
            queries = listOf("ESPN", "ESPN2", "FS1", "TNT", "NFL Network", "NBA TV", "MLB Network", "CBS Sports"),
            genreLeague = null,
            maxPages = 6,
        ) { _, item -> usChannelName(item.name) != null }
        val channels = found.groupBy { usChannelName(it.item.name)!! }
            .map { (name, sources) -> SportsTvChannel(name, sources.sortedBy { it.addonName }) }
            .sortedBy { channelOrder[it.name] ?: Int.MAX_VALUE }
        if (channels.isNotEmpty()) lock.withLock { channelCache = (key to now()) to channels }
        return channels
    }

    private val sportWords = listOf(
        "Football" to Regex("""\b(nfl|american football|college football|ncaaf)\b""", RegexOption.IGNORE_CASE),
        "Basketball" to Regex("""\b(basketball|nba|wnba|ncaab)\b""", RegexOption.IGNORE_CASE),
        "Baseball" to Regex("""\b(baseball|mlb)\b""", RegexOption.IGNORE_CASE),
        "Hockey" to Regex("""\b(hockey|nhl)\b""", RegexOption.IGNORE_CASE),
        "Soccer" to Regex("""\b(soccer|football|fútbol|futbol|uefa|premier league|la ?liga|serie a|bundesliga|ligue 1|mls|spfl|eredivisie|copa|champions league)\b""", RegexOption.IGNORE_CASE),
        "Fighting" to Regex("""\b(ufc|mma|boxing|wrestling|wwe|aew|fight)\b""", RegexOption.IGNORE_CASE),
        "Motorsports" to Regex("""\b(f1|formula|nascar|indycar|motogp|racing|grand prix)\b""", RegexOption.IGNORE_CASE),
        "Tennis" to Regex("""\b(tennis|atp|wta)\b""", RegexOption.IGNORE_CASE),
        "Golf" to Regex("""\b(golf|pga|lpga)\b""", RegexOption.IGNORE_CASE),
        "Cricket" to Regex("""\b(cricket|ipl|t20)\b""", RegexOption.IGNORE_CASE),
        "Rugby" to Regex("""\b(rugby)\b""", RegexOption.IGNORE_CASE),
    )

    internal fun sportOf(item: MetaPreview): String {
        val text = (item.genres + item.name).joinToString(" ")
        // "American football" must not read as soccer.
        sportWords.firstOrNull { (name, regex) -> name != "Soccer" && regex.containsMatchIn(text) }?.let { return it.first }
        return sportWords.firstOrNull { it.second.containsMatchIn(text) }?.first ?: "Other Sports"
    }

    private fun eventKey(title: String) = title.lowercase()
        .replace(Regex("""\b(live|hd|fhd|uhd|4k|8k|exclusive|stream|watch)\b"""), " ")
        .replace(Regex("[^a-z0-9]+"), "")

    suspend fun liveEvents(addons: List<ManagedAddon>, force: Boolean = false): List<SportsLiveEvent> {
        val key = addonKey(addons)
        if (!force) peekEvents(addons)?.let { return it }
        val found = sweep(
            addons = addons,
            label = eventLabel,
            queries = listOf("live", "vs"),
            genreLeague = null,
            maxPages = 4,
            allGenres = true,
        ) { _, item -> usChannelName(item.name) == null && !replayLabel.containsMatchIn(item.name) }
        val sportOrder = sportWords.mapIndexed { i, (name, _) -> name to i }.toMap()
        val events = found.groupBy { eventKey(it.item.name) }
            .filterKeys { it.length >= 4 }
            .map { (_, sources) ->
                val first = sources.first()
                SportsLiveEvent(first.item.name.trim(), sportOf(first.item), sources)
            }
            .sortedWith(compareBy({ sportOrder[it.sport] ?: Int.MAX_VALUE }, { -it.sources.size }, { it.title }))
        if (events.isNotEmpty()) lock.withLock { eventCache = (key to now()) to events }
        return events
    }
}
