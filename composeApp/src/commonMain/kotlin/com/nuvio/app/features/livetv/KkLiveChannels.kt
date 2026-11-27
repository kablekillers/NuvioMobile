package com.nuvio.app.features.livetv

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.supportsResource
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import com.nuvio.app.features.sports.SportsAddonPriority
import com.nuvio.app.features.sports.SportsCatalogMatch
import com.nuvio.app.features.sports.SportsChannelMatcher
import com.nuvio.app.features.sports.SportsContentKind
import com.nuvio.app.features.sports.SportsLiveTv
import com.nuvio.app.features.sports.SportsMultiviewSourceSelection
import com.nuvio.app.features.sports.SportsMultiviewStreamResolver
import com.nuvio.app.features.streams.probeLiveLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One add-on link for a channel, small enough to keep on disk. */
@Serializable
data class KkChannelSource(val addonName: String, val type: String, val id: String, val name: String)

/** One US channel with every add-on that carries it, best add-on first. */
@Serializable
data class KkLiveChannel(
    val slug: String,
    val name: String,
    val group: String,
    val logo: String? = null,
    val sources: List<KkChannelSource>,
)

/** A playable link for a channel. */
data class KkResolvedStream(val url: String, val headers: Map<String, String>, val addonName: String)

/**
 * Kable Killers Live TV channels: every US / USA channel in the installed add-ons' live TV lists,
 * merged across add-ons. The Live TV page reads them as a playlist served on this phone
 * ([PLAYLIST_URL]); a channel's link is resolved through the add-ons when it's played.
 */
object KkLiveChannels {
    const val PORT = 38734
    const val BASE = "http://127.0.0.1:$PORT/kk"
    const val PLAYLIST_URL = "$BASE/playlist.m3u"
    const val PLAYLIST_NAME = "Kable Killers"
    private const val CHANNEL_PREFIX = "$BASE/ch/"

    fun channelUrl(slug: String) = CHANNEL_PREFIX + slug

    /** The channel a playlist link stands for, or null for any other link. */
    fun slugOf(url: String): String? =
        if (url.startsWith(CHANNEL_PREFIX)) url.removePrefix(CHANNEL_PREFIX).substringBefore('?').substringBefore('#').ifBlank { null } else null

    /** Set on Android: where the last channel list is kept, so channels play straight after a restart. */
    var save: ((String) -> Unit)? = null
    var load: (() -> String?)? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var cache: Triple<String, Long, List<KkLiveChannel>>? = null
    private const val TTL_MILLIS = 20 * 60_000L
    private val requests = Semaphore(8)
    private fun now() = kotlin.time.Clock.System.now().toEpochMilliseconds()

    // ---- Which lists and which channels ----

    private val liveTypes = setOf("tv", "channel", "channels", "livetv", "live-tv", "live_tv", "iptv")
    private val notChannels = Regex("""replays?|highlights?|recaps?|\bvod\b|movies?\b|series|shows?\b|events?|schedule|matches|fixtures|ppv|radio""", RegexOption.IGNORE_CASE)
    private val usLabel = Regex("""\b(us|usa|u\.s\.a?\.?|united states|america|american)\b""", RegexOption.IGNORE_CASE)
    private val usAddon = Regex("""us\s*-?\s*tv|usa\s*-?\s*tv|\busa?\b|united states|america""", RegexOption.IGNORE_CASE)
    private val otherCountry = Regex(
        """\b(uk|gb|united kingdom|canada|ca|mexico|méxico|mx|latam|latino|brasil|brazil|argentina|colombia|chile|peru|spain|españa|espana|france|italia|italy|germany|deutschland|de|portugal|india|pakistan|arab|arabic|mena|africa|australia|au|nz|ireland|nederland|netherlands|turkey|türkiye|poland|russia|philippines|caribbean|caribe|español|espanol|spanish|deportes)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val usTags = setOf("us", "usa", "u.s.", "u.s.a.", "am", "us/ca")
    private val neutralTags = setOf("en", "eng", "english", "na", "4k", "uhd", "fhd", "hd", "sd", "hevc", "vip", "24 7", "247", "live", "tv")
    private val tagPrefix = Regex("""^\s*[\[(]?([A-Za-z0-9 ./]{1,8})[\])]?\s*[|:]\s*(.+)$""")
    private val dashPrefix = Regex("""^\s*([A-Za-z]{2,3})\s+-\s+(.+)$""")
    private val usSuffix = Regex("""[\s\-(\[]+(us|usa)[)\]]?\s*$""", RegexOption.IGNORE_CASE)
    private val quality = Regex("""\b(hd|fhd|uhd|sd|4k|8k|hevc|h265|1080p?|720p?|backup|alt|feed|live|vip|raw|\d+fps)\b""", RegexOption.IGNORE_CASE)

    /** The name without its region tag and quality words, and whether the name itself says US. */
    private fun clean(raw: String): Pair<String, Boolean>? {
        var name = raw.trim()
        var saysUs = false
        tagPrefix.find(name)?.let { m ->
            val tag = m.groupValues[1].trim().lowercase()
            when {
                tag in usTags -> { saysUs = true; name = m.groupValues[2] }
                tag in neutralTags -> name = m.groupValues[2]
                else -> return null
            }
        }
        dashPrefix.find(name)?.let { m ->
            val tag = m.groupValues[1].lowercase()
            if (tag in usTags) { saysUs = true; name = m.groupValues[2] }
        }
        if (usSuffix.containsMatchIn(name)) { saysUs = true; name = name.replace(usSuffix, "") }
        name = name.replace(quality, " ").replace(Regex("""[\[\](){}|]+"""), " ").replace(Regex("""\s+"""), " ").trim(' ', '-', ':', '.')
        if (name.length < 2) return null
        return name to saysUs
    }

    private fun mergeKey(name: String) = name.lowercase().replace("+", "plus").replace(Regex("[^a-z0-9]+"), "")

    private fun slugOfName(name: String) = name.lowercase().replace("+", "plus").replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "channel" }

    private val groups: List<Pair<String, Regex>> = listOf(
        "News" to Regex("""\b(news|cnn|msnbc|cnbc|fox business|bloomberg|newsmax|newsnation|c-?span|weather|hln|abc news|cbs news|nbc news)\b""", RegexOption.IGNORE_CASE),
        "Sports" to Regex("""\b(sports?|espn\w*|fs1|fs2|nfl|nba|mlb|nhl|golf|tennis|redzone|acc ?network|sec ?network|big ten|btn|bein|marquee|yes network|sny|nesn|msg|tnt|tbs|trutv|olympic|ufc|wwe)\b""", RegexOption.IGNORE_CASE),
        "Kids" to Regex("""\b(disney|nick\w*|cartoon|boomerang|pbs kids|baby|kids|universal kids|discovery family|nat geo kids)\b""", RegexOption.IGNORE_CASE),
        "Movies" to Regex("""\b(hbo|cinemax|showtime|starz|encore|mgm|epix|tcm|amc|ifc|sundance|hallmark|lifetime movie|fxm|flix|movies?|cinema|paramount network)\b""", RegexOption.IGNORE_CASE),
        "Music" to Regex("""\b(mtv\w*|vh1|cmt|bet|music|revolt|axs)\b""", RegexOption.IGNORE_CASE),
        "Local" to Regex("""^\s*(abc|cbs|nbc|fox|cw|pbs|my ?network|telemundo|univision)\b(\s*\d+)?(\s+[A-Za-z .-]+)?$""", RegexOption.IGNORE_CASE),
        // Station call letters (WABC, KTLA).
        "Local" to Regex("""\b[KW][A-Z]{2,3}(-?(TV|DT|HD))?\b"""),
    )

    private fun groupOf(name: String, genres: List<String>, catalogGenre: String?): String {
        val local = groups.filter { it.first == "Local" }.any { it.second.containsMatchIn(name) }
        if (local && !Regex("""\b(sports?|news|business|kids)\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)) return "Local"
        if (SportsLiveTv.usChannelName(name) != null) return "Sports"
        groups.firstOrNull { (_, r) -> r.containsMatchIn(name) }?.let { return it.first }
        val labels = (genres + listOfNotNull(catalogGenre)).filterNot { usLabel.containsMatchIn(it) && it.length <= 14 }
        labels.forEach { label -> groups.firstOrNull { (_, r) -> r.containsMatchIn(label) }?.let { return it.first } }
        return "Entertainment"
    }

    private fun liveCatalogs(manifest: AddonManifest): List<AddonCatalog> =
        manifest.catalogs.filter { catalog ->
            catalog.type.lowercase() in liveTypes &&
                !notChannels.containsMatchIn("${catalog.id} ${catalog.name}") &&
                manifest.resources.any { it.name == "stream" && (it.types.isEmpty() || catalog.type in it.types) } &&
                catalog.extra.none { it.isRequired && it.name !in setOf("genre", "skip") }
        }.take(16)

    private suspend fun pages(manifest: AddonManifest, catalog: AddonCatalog, genre: String?, maxPages: Int): List<MetaPreview> {
        val canPage = catalog.extra.any { it.name == "skip" }
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

    private class Found(val addonName: String, val item: MetaPreview, val name: String, val group: String)

    private suspend fun sweep(addons: List<ManagedAddon>): List<Found> = coroutineScope {
        addons.filter { it.isActive }.flatMap { addon ->
            val manifest = addon.manifest ?: return@flatMap emptyList()
            val addonIsUs = usAddon.containsMatchIn("${manifest.id} ${manifest.name} ${addon.displayTitle}") &&
                !otherCountry.containsMatchIn(manifest.name)
            liveCatalogs(manifest).map { catalog ->
                async {
                    val catalogText = "${catalog.id} ${catalog.name}"
                    if (otherCountry.containsMatchIn(catalog.name) && !usLabel.containsMatchIn(catalog.name)) return@async emptyList()
                    val catalogIsUs = usLabel.containsMatchIn(catalogText)
                    val genreExtra = catalog.extra.firstOrNull { it.name == "genre" }
                    val usGenres = genreExtra?.options.orEmpty().filter { usLabel.containsMatchIn(it) && !otherCountry.containsMatchIn(it) }
                    // Lists split by country: only the US ones. Split by kind (News, Sports…): each one.
                    val asks: List<Pair<String?, Boolean>> = when {
                        usGenres.isNotEmpty() -> usGenres.take(4).map { it to true }
                        genreExtra?.isRequired == true -> genreExtra.options
                            .filterNot { otherCountry.containsMatchIn(it) }
                            .take(14).map { it to false }
                        else -> listOf(null to false)
                    }
                    asks.flatMap { (genre, genreIsUs) ->
                        pages(manifest, catalog, genre, maxPages = 12)
                            .filter { manifest.supportsResource("stream", it.type, it.id) }
                            .mapNotNull { item ->
                                if (SportsChannelMatcher.isForeignOrLoop(item.name)) return@mapNotNull null
                                val (name, nameSaysUs) = clean(item.name) ?: return@mapNotNull null
                                if (otherCountry.containsMatchIn(name)) return@mapNotNull null
                                val known = SportsLiveTv.usChannelName(item.name)
                                val itemGenresUs = item.genres.any { usLabel.containsMatchIn(it) && !otherCountry.containsMatchIn(it) }
                                val itemGenresForeign = item.genres.any { otherCountry.containsMatchIn(it) } && !itemGenresUs
                                val isUs = !itemGenresForeign &&
                                    (nameSaysUs || known != null || itemGenresUs || genreIsUs || catalogIsUs || addonIsUs)
                                if (!isUs) return@mapNotNull null
                                val display = known ?: name
                                Found(addon.displayTitle, item, display, groupOf(display, item.genres, genre))
                            }
                    }
                }
            }
        }.awaitAll().flatten()
    }

    /**
     * The profile's add-ons once they're read. The app reads them after a profile is picked (and its
     * PIN entered), so this waits for that rather than reading them itself.
     */
    private suspend fun readyAddons(): List<ManagedAddon> {
        withTimeoutOrNull(60_000) {
            while (AddonRepository.uiState.value.addons.none { it.isActive }) delay(300)
        }
        // Give the rest a moment to finish loading.
        withTimeoutOrNull(15_000) {
            while (AddonRepository.uiState.value.addons.any { it.enabled && it.isRefreshing }) delay(300)
        }
        return AddonRepository.uiState.value.addons.filter { it.isActive }
    }

    /** Changes whenever the active add-ons change (to download the channels again). */
    fun addonsKey(addons: List<ManagedAddon>): String =
        addons.filter { it.isActive }.map { it.manifestUrl }.sorted().joinToString(",")

    /** The channel list (cached for a while). */
    suspend fun channels(force: Boolean = false): List<KkLiveChannel> = withContext(Dispatchers.Default) {
        val addons = readyAddons()
        val key = addonsKey(addons)
        if (!force) lock.withLock { cache?.takeIf { it.first == key && now() - it.second < TTL_MILLIS }?.third }?.let { return@withContext it }
        val found = sweep(addons)
        val channels = found.groupBy { mergeKey(it.name) }
            .filterKeys { it.length >= 2 }
            .map { (_, list) ->
                val ordered = SportsAddonPriority.sorted(list) { it.addonName }
                val first = ordered.first()
                KkLiveChannel(
                    slug = "",
                    name = first.name,
                    group = list.groupingBy { it.group }.eachCount().maxByOrNull { it.value }?.key ?: first.group,
                    logo = ordered.firstNotNullOfOrNull { it.item.logo?.takeIf(String::isNotBlank) ?: it.item.poster?.takeIf(String::isNotBlank) },
                    sources = ordered.map { KkChannelSource(it.addonName, it.item.type, it.item.id, it.item.name) }
                        .distinctBy { Triple(it.addonName, it.type, it.id) },
                )
            }
            .sortedWith(compareBy({ groupOrder.indexOf(it.group).let { i -> if (i < 0) groupOrder.size else i } }, { it.name.lowercase() }))
        // Slugs that stay the same from one refresh to the next (the Live TV page keys favorites by link).
        val used = HashSet<String>()
        val withSlugs = channels.map { channel ->
            var slug = slugOfName(channel.name)
            var n = 2
            while (!used.add(slug)) slug = slugOfName(channel.name) + "-" + n++
            channel.copy(slug = slug)
        }
        if (withSlugs.isNotEmpty()) {
            lock.withLock { cache = Triple(key, now(), withSlugs) }
            runCatching { save?.invoke(json.encodeToString(withSlugs)) }
        }
        // A sweep that came back empty (network hiccup): the last good list.
        withSlugs.ifEmpty { cachedOrSaved().orEmpty() }
    }

    private val groupOrder = listOf("Local", "Sports", "News", "Entertainment", "Movies", "Kids", "Music")

    private suspend fun cachedOrSaved(): List<KkLiveChannel>? {
        lock.withLock { cache?.third }?.let { return it }
        val saved = runCatching { load?.invoke()?.let { json.decodeFromString<List<KkLiveChannel>>(it) } }.getOrNull()
        if (!saved.isNullOrEmpty()) lock.withLock { if (cache == null) cache = Triple("", 0L, saved) }
        return saved
    }

    /** The playlist the Live TV page reads. Fails when nothing was found, so Live TV tries again later. */
    suspend fun playlist(force: Boolean = false): String {
        val list = channels(force)
        if (list.isEmpty()) error("No US channels found in your add-ons yet")
        return buildString {
            append("#EXTM3U\n")
            list.forEach { c ->
                val name = c.name.replace(",", " ").replace("\"", "'")
                append("#EXTINF:-1 tvg-id=\"kk.").append(c.slug).append("\" tvg-name=\"").append(name).append('"')
                c.logo?.let { append(" tvg-logo=\"").append(it.replace("\"", "%22")).append('"') }
                append(" group-title=\"").append(c.group).append("\",").append(name).append('\n')
                append(channelUrl(c.slug)).append('\n')
            }
        }
    }

    /**
     * A working link for the channel: asks the add-ons that carry it (best first) and tries
     * the links in add-on order, skipping ones that are clearly dead.
     */
    suspend fun resolve(slug: String): KkResolvedStream? = withContext(Dispatchers.Default) {
        val channel = (cachedOrSaved() ?: channels()).firstOrNull { it.slug == slug }
            ?: channels(force = true).firstOrNull { it.slug == slug }
            ?: return@withContext null
        val addons = readyAddons()
        val streams = coroutineScope {
            channel.sources.distinctBy { it.type to it.id }.take(6).map { source ->
                async {
                    runCatching {
                        SportsMultiviewStreamResolver.directStreams(
                            SportsCatalogMatch(
                                source.addonName,
                                MetaPreview(id = source.id, type = source.type, name = source.name),
                                100,
                                SportsContentKind.EVENT,
                            ),
                            addons,
                        )
                    }.getOrElse { if (it is CancellationException) throw it; emptyList() }
                }
            }.awaitAll().flatten()
        }
        val candidates = SportsAddonPriority.sorted(streams) { it.addonName }
            .mapNotNull { stream ->
                val url = SportsMultiviewSourceSelection.directUrl(stream) ?: return@mapNotNull null
                KkResolvedStream(url, sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request), stream.addonName)
            }
            .distinctBy { it.url }
            // Remember what works: the add-on that played this channel before goes first.
            .sortedByDescending { com.nuvio.app.features.streams.LinkMemory.score(null, channel.name, it.addonName) }
        if (candidates.isEmpty()) return@withContext null
        // Check the first few quickly; the first one that answers wins (unknown counts as fine).
        for (candidate in candidates.take(5)) {
            val alive = withTimeoutOrNull(6_000) { runCatching { probeLiveLink(candidate.url, candidate.headers) }.getOrNull() }
            if (alive != false) return@withContext candidate
        }
        candidates.first()
    }
}
