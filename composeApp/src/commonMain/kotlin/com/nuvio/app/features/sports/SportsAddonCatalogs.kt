package com.nuvio.app.features.sports

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonManifest

/**
 * Manifest-derived sports catalog routing. Never construct stream IDs from ESPN IDs:
 * a catalog item must first supply its own ID, then that ID can be passed to the
 * existing StreamRepository for the matching content type.
 *
 * This registry contains public manifest identifiers only. Personalized add-on
 * URLs and credentials are deliberately not embedded in the application.
 */
object SportsAddonCatalogs {
    const val SPORTS_STREAMS = "community.sports.streamed.premium"
    private const val FREE_SPORTS_STREAMS = "community.sports.fly"
    const val NUVIO_LIVE = "community.nuvio.live-sports"
    const val STREMVERSE = "com.stremverse.org"

    fun routes(
        addon: AddonManifest,
        league: SportsLeague,
        replays: Boolean = false,
        expanded: Boolean = league.androidOnly,
    ): List<AddonCatalog> {
        if (expanded) return expandedRoutes(addon, league, replays)
        val candidates = when (addon.id) {
            SPORTS_STREAMS -> if (replays) listOf("sports_recaps") else listOfNotNull(
                when (league) {
                    SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL -> "sports_american_football"
                    SportsLeague.NBA -> "sports_basketball"
                    SportsLeague.MLB -> "sports_baseball"
                    SportsLeague.NHL -> "sports_hockey"
                    SportsLeague.UFC -> "sports_fight"
                    else -> null
                }, "sports_live", "sports_today"
            )
            NUVIO_LIVE -> if (replays) when (league) {
                SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL -> listOf("nuvio_sports_replays_american_football")
                SportsLeague.NBA -> listOf("nuvio_sports_replays_basketball")
                SportsLeague.MLB -> listOf("nuvio_sports_replays_baseball")
                SportsLeague.NHL -> listOf("nuvio_sports_replays_hockey")
                SportsLeague.UFC -> listOf("nuvio_sports_replays")
                else -> emptyList()
            } else listOfNotNull(
                when (league) {
                    SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL -> "nuvio_sports_american_football"
                    SportsLeague.NBA -> "nuvio_sports_basketball"
                    SportsLeague.MLB -> "nuvio_sports_baseball"
                    SportsLeague.NHL -> "nuvio_sports_hockey"
                    SportsLeague.UFC -> "nuvio_sports_mma"
                    else -> null
                }, "nuvio_sports_live", "nuvio_sports_upcoming"
            )
            STREMVERSE -> if (replays) listOf("stremverse_replays")
                else listOf("stremverse_xtra_events", "stremverse_live_events")
            else -> null
        }
        val catalogs = if (candidates != null) {
            addon.catalogs.filter { it.id in candidates && it.type in setOf("tv", "sport") }
        } else {
            additionalCatalogs(addon, replays)
        }
        return catalogs
    }
    private val expandedProviders = setOf(SPORTS_STREAMS, FREE_SPORTS_STREAMS, NUVIO_LIVE, STREMVERSE)
    private val generalCatalogs = setOf(
        "sports_live", "sports_today", "sports_recaps",
        "nuvio_sports_live", "nuvio_sports_today", "nuvio_sports_upcoming", "nuvio_sports_replays",
        "stremverse_xtra_events", "stremverse_live_events", "stremverse_replays",
    )

    private fun expandedRoutes(addon: AddonManifest, league: SportsLeague, replays: Boolean): List<AddonCatalog> {
        if (addon.id !in expandedProviders) {
            return additionalCatalogs(addon, replays).filter {
                league.scoreboardPath != null || isScopedCatalog(it, league)
            }
        }
        return addon.catalogs.filter { catalog ->
            catalog.type in eventTypes &&
                replayCatalog.containsMatchIn("${catalog.id} ${catalog.name}") == replays &&
                (isScopedCatalog(catalog, league) || league.scoreboardPath != null && catalog.id in generalCatalogs)
        }
    }

    internal fun isScopedCatalog(catalog: AddonCatalog, league: SportsLeague): Boolean {
        val sport = catalog.id.lowercase().replace('-', '_')
            .removePrefix("nuvio_").removePrefix("sports_").removePrefix("replays_")
        val exact = sport == league.catalogSport || when (league.catalogSport) {
            "motor_sports" -> sport in setOf("motorsport", "motorsports", "motor_sport")
            "fight" -> sport in setOf("mma", "boxing", "mma_boxing")
            "football" -> sport == "soccer"
            "other" -> sport in setOf("others", "other_sports")
            else -> false
        }
        return exact || namesSport(catalog.id + " " + catalog.name, league)
    }

    // "plavioBasketball" / "live_tv-nfl" -> " plavio basketball " / " live tv nfl "
    private fun words(text: String) = " " + text.replace(Regex("([a-z])([A-Z])"), "\$1 \$2").lowercase()
        .replace(Regex("[^a-z0-9]+"), " ").trim() + " "

    /** Words that name a league's sport, for add-ons with their own catalog IDs (Plavio, CNCBridge, US TV…). */
    internal fun sportTerms(league: SportsLeague): List<String> = when (league.catalogSport) {
        "american_football" -> if (league == SportsLeague.COLLEGE_FOOTBALL) {
            listOf("college football", "ncaa football", "ncaaf", "cfb")
        } else listOf("nfl", "american football", "redzone")
        "basketball" -> when (league) {
            SportsLeague.NBA -> listOf("nba", "basketball")
            SportsLeague.WNBA -> listOf("wnba", "basketball")
            else -> listOf("ncaa basketball", "college basketball", "ncaab", "basketball")
        }
        "baseball" -> listOf("mlb", "baseball")
        "hockey" -> listOf("nhl", "hockey", "ice hockey")
        "fight" -> listOf("ufc", "mma", "boxing", "fight", "fights", "combat", "wrestling", "ppv")
        "tennis" -> listOf("tennis", "atp", "wta")
        "golf" -> listOf("golf", "pga", "lpga")
        "motor_sports" -> listOf("motorsport", "motorsports", "motor sport", "motor sports", "racing", "f1", "formula 1", "formula one", "nascar", "indycar", "motogp")
        "football" -> listOf("soccer", "football", "premier league", "epl", "la liga", "laliga", "champions league", "serie a", "bundesliga", "ligue 1", "mls")
        "rugby" -> listOf("rugby")
        "afl" -> listOf("afl", "aussie rules")
        "cricket" -> listOf("cricket", "ipl")
        "darts" -> listOf("darts")
        "billiards" -> listOf("billiards", "snooker", "pool")
        else -> listOf("other sports", "others", "other")
    }

    internal fun namesSport(label: String, league: SportsLeague): Boolean {
        val text = words(label)
        // "football" alone is soccer; American football catalogs say so.
        if (league.catalogSport == "football" && (" american " in text || " nfl " in text || " college football " in text)) return false
        return sportTerms(league).any { " $it " in text }
    }

    /**
     * Value(s) to send for a required genre filter: "All" when offered, otherwise the
     * options naming this sport (Plavio-style per-sport lists), then a general sports one.
     * Empty means the catalog can't be asked for this sport.
     */
    internal fun genreValues(catalog: AddonCatalog, league: SportsLeague): List<String?> {
        val genre = catalog.extra.firstOrNull { it.isRequired && it.name == "genre" } ?: return listOf(null)
        val options = genre.options.orEmpty()
        options.firstOrNull { it.equals("all", ignoreCase = true) }?.let { return listOf(it) }
        val forSport = options.filter { namesSport(it, league) }.take(2)
        if (forSport.isNotEmpty()) return forSport
        return options.filter { Regex("sport|live|channel", RegexOption.IGNORE_CASE).containsMatchIn(it) }.take(1)
    }

    /**
     * CNCBridge exposes sport-specific extension catalogs whose IDs are not the
     * standard sports_* identifiers. Permit unmatched items only when the
     * catalog explicitly names the selected sport, never for generic live lists.
     */
    internal fun isCncBridgeScopedCatalog(addon: AddonManifest, catalog: AddonCatalog, league: SportsLeague): Boolean {
        val provider = (addon.id + " " + addon.name).lowercase().filter(Char::isLetterOrDigit)
        if ("cncbridge" !in provider && "cncverse" !in provider) return false
        val label = (catalog.id + " " + catalog.name).lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
        val terms = when (league) {
            SportsLeague.COLLEGE_FOOTBALL -> listOf("college football", "ncaa football", "ncaaf", "badgers")
            SportsLeague.NFL -> listOf("nfl", "pro football")
            SportsLeague.NBA -> listOf("nba", "basketball")
            SportsLeague.MLB -> listOf("mlb", "baseball")
            SportsLeague.NHL -> listOf("nhl", "hockey")
            SportsLeague.UFC -> listOf("ufc", "mma")
            else -> emptyList()
        }
        return terms.any { it in label }
    }

    private val additionalProviders = listOf(
        "nebula", "pencari", "penguplay", "tennis", "cncbridge", "cncverse", "plaviolive", "plavio",
        "ustv", "usatv", "ustvnow",
    )
    // Any other add-on that looks like live TV / sports gets its live catalogs searched too.
    private val liveLabel = Regex(
        """sports?|live|\btv\b|channels?|events?|ppv|iptv|nfl|nba|mlb|nhl|ufc|football|basketball|baseball|hockey|soccer|cricket|tennis|golf|racing|boxing|mma""",
        RegexOption.IGNORE_CASE,
    )
    private val sportsLabel = Regex(
        """sports?|nfl|nba|mlb|nhl|ufc|redzone|football|basketball|baseball|hockey|ncaa|live.?tv|channels?|events?""",
        RegexOption.IGNORE_CASE,
    )
    private val eventTypes = setOf("sport", "sports", "tv", "channel", "livetv", "live-tv", "live_tv")
    private val replayCatalog = Regex(
        """(?:^|[^a-z])(?:replays?|highlights?|recaps?)(?:[^a-z]|$)""",
        RegexOption.IGNORE_CASE,
    )

    private fun additionalCatalogs(addon: AddonManifest, replays: Boolean): List<AddonCatalog> {
        val identity = (addon.id + " " + addon.name).lowercase().filter(Char::isLetterOrDigit)
        val known = additionalProviders.any { it in identity }
        val cnc = "cncbridge" in identity || "cncverse" in identity
        val liveAddon = liveLabel.containsMatchIn(addon.id + " " + addon.name)
        val catalogs = addon.catalogs.filter { catalog ->
            val label = "${catalog.id} ${catalog.name}"
            val typeOk = catalog.type in eventTypes ||
                // CNCBridge runs Cloudstream extensions that type live sports as movie/series.
                (cnc && catalog.type in setOf("movie", "series") && sportsLabel.containsMatchIn(label))
            typeOk &&
                replayCatalog.containsMatchIn(label) == replays &&
                (known || liveAddon || liveLabel.containsMatchIn(label)) &&
                addon.resources.any { it.name == "stream" && (it.types.isEmpty() || catalog.type in it.types) }
        }
        // Unlisted add-ons: same reach as NFL Channels, capped so a huge add-on can't slow the hub.
        return if (known) catalogs else catalogs.take(10)
    }

}
