package com.nuvio.app.features.sports

/**
 * Live-TV channel add-ons (CNCBridge and similar) list channels ("ESPN",
 * "FOX 5 New York", "NFL Network"), not games, so their items never match a
 * game title. This matches a channel item to the networks actually carrying the
 * game (ESPN's broadcast list) plus the league's own channels.
 */
internal object SportsChannelMatcher {
    private val aliases = mapOf(
        "nflnet" to "nflnetwork",
        "nfln" to "nflnetwork",
        "fs1" to "foxsports1",
        "fs2" to "foxsports2",
        "btn" to "bigtennetwork",
        "secn" to "secnetwork",
        "accn" to "accnetwork",
        "cbssn" to "cbssportsnetwork",
        "mlbn" to "mlbnetwork",
        "nhln" to "nhlnetwork",
        "espnplus" to "espn",
        "abcespn" to "abc",
    )

    private fun leagueChannels(league: SportsLeague): List<String> = when (league) {
        SportsLeague.NFL -> listOf("nflnetwork", "nflredzone", "redzone")
        SportsLeague.NBA -> listOf("nbatv")
        SportsLeague.MLB -> listOf("mlbnetwork")
        SportsLeague.NHL -> listOf("nhlnetwork")
        SportsLeague.COLLEGE_FOOTBALL, SportsLeague.NCAA_MEN, SportsLeague.NCAA_WOMEN ->
            listOf("secnetwork", "bigtennetwork", "accnetwork", "espnu")
        SportsLeague.UFC -> listOf("ufcfightpass")
        else -> emptyList()
    }

    // Streaming services aren't TV channels: a game "on Netflix" or "on Prime Video"
    // must not pull in every "NETFLIX ..." 24/7 loop channel.
    private val streamingServices = setOf(
        "netflix", "primevideo", "amazonprimevideo", "amazon", "prime", "peacock", "espnplus", "espn+",
        "appletv", "appletvplus", "paramountplus", "paramount", "max", "hbomax", "youtube", "youtubetv",
        "fubo", "dazn", "nflplus", "nbaleaguepass", "mlbtv", "nhltv", "sling", "hulu", "tubi", "roku",
    )

    private val usTags = setOf("us", "usa", "en", "eng", "4k", "uhd", "fhd", "hd", "vip")
    private val countryCodes = setOf(
        "ca", "uk", "gb", "au", "nz", "ie", "mx", "es", "fr", "de", "it", "pt", "br", "ar", "co", "cl", "pe",
        "za", "in", "nl", "be", "tr", "pl", "se", "no", "dk", "fi", "gr", "ro", "sa", "ae", "latam", "lat",
        "latin", "arg", "bra", "mex", "ven", "uy", "ec", "esp", "ger", "ita", "fra", "por",
    )
    private val foreignWords = Regex(
        """\b(deportes|latin|latino|latina|latam|español|espanol|en espanol|mexico|méxico|brasil|brazil|argentina|colombia|caribe|caribbean|africa|españa|espana|portugal|arabia|mena)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val loopChannel = Regex("""\b24\s*/\s*7\b|\b247\b""", RegexOption.IGNORE_CASE)

    /**
     * Foreign feeds ("UK: ...", "CA - NFL NETWORK HD", "SA| ESPN 1") and 24/7 loop
     * channels (replaying shows on repeat) are never a live game.
     */
    internal fun isForeignOrLoop(name: String): Boolean {
        if (loopChannel.containsMatchIn(name)) return true
        // Spanish / Latin American / other-country feeds of US channels ("ESPN Deportes", "ESPN Latino").
        if (foreignWords.containsMatchIn(name)) return true
        // "LATIN-BR| ESPN 2", "SA| ESPN 1", "UK: ..."
        Regex("""^\s*[\[(]?([A-Za-z0-9 _-]{1,14})[\])]?\s*[|:]\s*\S""").find(name)?.let { match ->
            val tag = match.groupValues[1].trim().lowercase()
            val tokens = tag.split(Regex("[ _-]+")).filter { it.isNotBlank() }
            if (tokens.any { it in countryCodes }) return true
            if (tag.length <= 8 && tag !in usTags) return true
        }
        // "(NL) ESPN PLAY ESPN", "[BR] ESPN"
        Regex("""^\s*[\[(]([A-Za-z]{2,5})[\])]\s*\S""").find(name)?.let { match ->
            if (match.groupValues[1].lowercase() in countryCodes) return true
        }
        Regex("""^\s*[\[(]?([A-Za-z]{2,5})[\])]?\s+-\s+\S""").find(name)?.let { match ->
            if (match.groupValues[1].lowercase() in countryCodes) return true
        }
        return false
    }

    private fun words(text: String): List<String> =
        text.lowercase().replace("+", " plus").split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }

    private fun compact(text: String): String = words(text).joinToString("")

    /** Compact channel names to look for, e.g. "espn", "foxsports1", "nflnetwork". */
    fun terms(league: SportsLeague, events: List<SportsEvent>): Set<String> {
        val broadcast = events.flatMap { it.broadcasts }
            .flatMap { it.split('/', ',', '&') }
            .map { compact(it) }
            .filter { it.length >= 2 }
            .map { aliases[it] ?: it }
            .filter { it !in streamingServices }
        return (broadcast + leagueChannels(league)).toSet()
    }

    /** Search words for channel catalogs that only answer searches. */
    fun searchQueries(league: SportsLeague, events: List<SportsEvent>): List<String> =
        events.flatMap { it.broadcasts }.flatMap { it.split('/', ',', '&') }.map { it.trim() }
            .filter { it.length >= 2 && compact(it) !in streamingServices }.distinct().take(6)

    private val excluded = Regex("""\b(news|weather|replays?|highlights?|recaps?|condensed)\b""", RegexOption.IGNORE_CASE)

    fun matches(channelName: String, terms: Set<String>): Boolean {
        if (terms.isEmpty() || excluded.containsMatchIn(channelName) || isForeignOrLoop(channelName)) return false
        val name = words(channelName)
        // A term matches a run of whole words: "fox" fits "FOX 5 New York" but not "Foxtel".
        for (start in name.indices) {
            val run = StringBuilder()
            for (end in start until minOf(name.size, start + 4)) {
                run.append(name[end])
                if (run.toString() in terms) return true
            }
        }
        return false
    }
}
