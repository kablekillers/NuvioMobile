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
    const val NUVIO_LIVE = "community.nuvio.live-sports"
    const val STREMVERSE = "com.stremverse.org"

    fun routes(addon: AddonManifest, league: SportsLeague, replays: Boolean = false): List<AddonCatalog> {
        val candidates = when (addon.id) {
            SPORTS_STREAMS -> if (replays) listOf("sports_recaps") else listOfNotNull(
                when (league) {
                    SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL -> "sports_american_football"
                    SportsLeague.NBA -> "sports_basketball"
                    SportsLeague.MLB -> "sports_baseball"
                    SportsLeague.NHL -> "sports_hockey"
                    SportsLeague.UFC -> "sports_fight"
                }, "sports_live", "sports_today"
            )
            NUVIO_LIVE -> if (replays) when (league) {
                SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL -> listOf("nuvio_sports_replays_american_football")
                SportsLeague.NBA -> listOf("nuvio_sports_replays_basketball")
                SportsLeague.MLB -> listOf("nuvio_sports_replays_baseball")
                SportsLeague.NHL -> listOf("nuvio_sports_replays_hockey")
                SportsLeague.UFC -> listOf("nuvio_sports_replays")
            } else listOfNotNull(
                when (league) {
                    SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL -> "nuvio_sports_american_football"
                    SportsLeague.NBA -> "nuvio_sports_basketball"
                    SportsLeague.MLB -> "nuvio_sports_baseball"
                    SportsLeague.NHL -> "nuvio_sports_hockey"
                    SportsLeague.UFC -> "nuvio_sports_mma"
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
    private val additionalProviders = listOf("nebula", "pencari", "penguplay", "tennis")
    private val eventTypes = setOf("sport", "sports", "tv", "channel", "livetv", "live-tv", "live_tv")
    private val replayCatalog = Regex(
        """(?:^|[^a-z])(?:replays?|highlights?|recaps?)(?:[^a-z]|$)""",
        RegexOption.IGNORE_CASE,
    )

    private fun additionalCatalogs(addon: AddonManifest, replays: Boolean): List<AddonCatalog> {
        val identity = (addon.id + " " + addon.name).lowercase().filter(Char::isLetterOrDigit)
        if (additionalProviders.none { it in identity }) return emptyList()

        return addon.catalogs.filter { catalog ->
            catalog.type in eventTypes &&
                replayCatalog.containsMatchIn("${catalog.id} ${catalog.name}") == replays &&
                addon.resources.any { it.name == "stream" && catalog.type in it.types }
        }
    }

}
