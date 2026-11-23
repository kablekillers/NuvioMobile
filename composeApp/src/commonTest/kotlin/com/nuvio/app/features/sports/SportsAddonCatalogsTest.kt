package com.nuvio.app.features.sports

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonResource
import com.nuvio.app.features.catalog.buildCatalogUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SportsAddonCatalogsTest {
    @Test
    fun discoversEventCatalogsForTheAdditionalProviders() {
        val providers = listOf(
            addon("org.nebulastreams.sports", "Nebula Sports", AddonCatalog("sports", "events", "Events")),
            addon("example.pencari", "Pencari Sports", AddonCatalog("tv", "live", "Live")),
            addon("com.penguplay", "PenguPlay", AddonCatalog("channel", "live-tv", "Live TV")),
            addon("com.sleeyax.dlive-addon", "Tennis Channel", AddonCatalog("tv", "dlive_catalog", "Tennis")),
            addon("com.cncbridge", "CNCBridge", AddonCatalog("sports", "badgers-live", "College Football")),
            addon("org.plavio.live", "Plavio Live", AddonCatalog("channel", "sports-channels", "Sports Channels")),
        )

        providers.forEach { manifest ->
            assertEquals(manifest.catalogs, SportsAddonCatalogs.routes(manifest, SportsLeague.NBA))
        }
    }

    @Test
    fun separatesLiveAndReplayCatalogsAndExcludesMovies() {
        val live = AddonCatalog("sports", "live", "Live")
        val replay = AddonCatalog("sports", "nba-replays", "NBA Replays")
        val addon = addon("org.nebulastreams.sports", "Nebula Sports", live, replay, AddonCatalog("movie", "sports-films", "Sports Films"))

        assertEquals(listOf(live), SportsAddonCatalogs.routes(addon, SportsLeague.NBA))
        assertEquals(listOf(replay), SportsAddonCatalogs.routes(addon, SportsLeague.NBA, replays = true))
    }

    @Test
    fun preservesCurrentLeagueCatalogs() {
        val basketball = AddonCatalog("sport", "sports_basketball", "Basketball")
        val live = AddonCatalog("sport", "sports_live", "Live")
        val addon = addon(
            SportsAddonCatalogs.SPORTS_STREAMS, "Sports Streams",
            basketball, live, AddonCatalog("sport", "sports_hockey", "Hockey"),
        )

        assertEquals(listOf(basketball, live), SportsAddonCatalogs.routes(addon, SportsLeague.NBA))
    }

    @Test
    fun discoveryQueriesIncludeFixtureAndBothTeamNames() {
        // Empty schedule must not generate an invalid required-search catalog request.
        assertTrue(SportsAddonDiscovery.sportsSearchQueries(emptyList()).isEmpty())
    }

    @Test
    fun sendsSkipZeroOnlyWhenRequested() {
        val manifest = "https://example.com/manifest.json"
        assertEquals(
            "https://example.com/catalog/sports/live/skip=0.json",
            buildCatalogUrl(manifest, "sports", "live", null, null, 0, includeInitialSkip = true),
        )
        assertEquals(
            "https://example.com/catalog/sports/live.json",
            buildCatalogUrl(manifest, "sports", "live", null, null, 0),
        )
    }

    @Test
    fun cncBridgeCollegeFootballExtensionCanSupplyUnmatchedGames() {
        val college = AddonCatalog("sports", "badgers-live", "College Football")
        val generic = AddonCatalog("sports", "live", "Live")
        val manifest = addon("com.cncbridge", "CNCBridge", college, generic)
        assertTrue(SportsAddonCatalogs.isCncBridgeScopedCatalog(manifest, college, SportsLeague.COLLEGE_FOOTBALL))
        assertTrue(!SportsAddonCatalogs.isCncBridgeScopedCatalog(manifest, generic, SportsLeague.COLLEGE_FOOTBALL))
        assertTrue(!SportsAddonCatalogs.isCncBridgeScopedCatalog(manifest, college, SportsLeague.NBA))
    }

    @Test
    fun deeperSearchIncludesCollegeFootballWithoutScoreboardGames() {
        assertEquals(
            listOf("College Football", "NCAA Football"),
            SportsAddonDiscovery.deepSearchQueries(SportsLeague.COLLEGE_FOOTBALL, emptyList()),
        )
    }

    @Test
    fun deeperSearchIncludesTeamAliasesAndStaysBounded() {
        val events = (1..15).map { index ->
            SportsEvent(
                id = "game-$index",
                league = SportsLeague.COLLEGE_FOOTBALL,
                title = "Wisconsin vs Opponent $index",
                startTimeEpochMillis = null,
                status = SportsEventStatus.SCHEDULED,
                providerName = "test",
                competitors = listOf(
                    SportsCompetitor(name = "Wisconsin Badgers", abbreviation = "WIS"),
                    SportsCompetitor(name = "Opponent $index", abbreviation = "OP$index"),
                ),
            )
        }
        val queries = SportsAddonDiscovery.deepSearchQueries(SportsLeague.COLLEGE_FOOTBALL, events)
        assertTrue("Wisconsin Badgers" in queries)
        assertTrue("WIS" in queries)
        assertTrue(queries.size <= 18)
        assertEquals(queries.size, queries.distinct().size)
    }

    private fun addon(id: String, name: String, vararg catalogs: AddonCatalog): AddonManifest {
        val types = catalogs.map { it.type }
        return AddonManifest(
            id = id,
            name = name,
            description = "",
            version = "1.0.0",
            resources = listOf(AddonResource("stream", types)),
            types = types,
            catalogs = catalogs.toList(),
            transportUrl = "https://example.com/manifest.json",
        )
    }
}
