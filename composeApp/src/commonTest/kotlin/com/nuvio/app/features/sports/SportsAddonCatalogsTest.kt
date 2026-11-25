package com.nuvio.app.features.sports

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.addons.AddonExtraProperty
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

    @Test
    fun liveTvAddonsOutsideTheListAreSearchedToo() {
        val usTv = addon("community.ustvnow", "US TV Now", AddonCatalog("tv", "channels", "All Channels"))
        val other = addon("org.example.live", "Some Live TV", AddonCatalog("tv", "us", "USA"), AddonCatalog("movie", "films", "Films"))
        val movies = addon("org.example.movies", "Movie Finder", AddonCatalog("movie", "top", "Top"))
        assertEquals(usTv.catalogs, SportsAddonCatalogs.routes(usTv, SportsLeague.NFL))
        assertEquals(listOf(other.catalogs.first()), SportsAddonCatalogs.routes(other, SportsLeague.NFL))
        assertTrue(SportsAddonCatalogs.routes(movies, SportsLeague.NFL).isEmpty())
    }

    @Test
    fun cncBridgeSportsCatalogsTypedAsMoviesAreIncluded() {
        val nfl = AddonCatalog("movie", "cnc-nfl", "NFL Live")
        val film = AddonCatalog("movie", "cnc-films", "Films")
        val manifest = addon("com.cncverse.bridge", "CNCVerse Bridge", nfl, film)
        assertEquals(listOf(nfl), SportsAddonCatalogs.routes(manifest, SportsLeague.NFL))
    }

    @Test
    fun perSportCatalogsAndGenresAreRecognised() {
        assertTrue(SportsAddonCatalogs.isScopedCatalog(AddonCatalog("tv", "plavioCricket", "Cricket"), SportsLeague.CRICKET))
        assertTrue(SportsAddonCatalogs.isScopedCatalog(AddonCatalog("tv", "pl-soccer", "Soccer TV"), SportsLeague.SOCCER))
        assertTrue(!SportsAddonCatalogs.isScopedCatalog(AddonCatalog("tv", "pl-nfl", "American Football"), SportsLeague.SOCCER))
        val genre = AddonCatalog(
            "tv", "live", "Live",
            extra = listOf(AddonExtraProperty(name = "genre", isRequired = true, options = listOf("Cricket", "Basketball", "News"))),
        )
        assertEquals(listOf<String?>("Basketball"), SportsAddonCatalogs.genreValues(genre, SportsLeague.NBA))
        assertTrue(SportsAddonCatalogs.genreValues(genre, SportsLeague.NHL).isEmpty())
    }

    @Test
    fun channelsCarryingTheGameMatch() {
        val terms = SportsChannelMatcher.terms(SportsLeague.NFL, emptyList()) + setOf("fox", "espn")
        assertTrue(SportsChannelMatcher.matches("FOX 5 New York", terms))
        assertTrue(SportsChannelMatcher.matches("NFL RedZone HD", terms))
        assertTrue(SportsChannelMatcher.matches("ESPN", terms))
        assertTrue(!SportsChannelMatcher.matches("FOX News", terms))
        assertTrue(!SportsChannelMatcher.matches("Foxtel One", terms))
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
