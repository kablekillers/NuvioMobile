package com.nuvio.app.features.sports

import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SportsLiveTvTest {
    @Test
    fun keepsUsChannelsAndDropsForeignFeeds() {
        assertEquals("ESPN", SportsLiveTv.usChannelName("4K| ESPN UHD"))
        assertEquals("ESPN2", SportsLiveTv.usChannelName("US| ESPN 2 HD"))
        assertEquals("ESPN2", SportsLiveTv.usChannelName("ESPN2"))
        assertEquals("FS1", SportsLiveTv.usChannelName("USA: Fox Sports 1"))
        assertEquals("FOX", SportsLiveTv.usChannelName("FOX 5 New York"))
        assertEquals("NFL RedZone", SportsLiveTv.usChannelName("NFL RedZone"))
        assertEquals("ESPNews", SportsLiveTv.usChannelName("ESPNews"))
        assertNull(SportsLiveTv.usChannelName("SA| ESPN 1 HD"))
        assertNull(SportsLiveTv.usChannelName("CO| CLARO-ESPN2"))
        assertNull(SportsLiveTv.usChannelName("ESPN Deportes"))
        assertNull(SportsLiveTv.usChannelName("TNT Sports 1"))
        assertNull(SportsLiveTv.usChannelName("FOX News"))
    }

    @Test
    fun groupsEventsBySport() {
        fun item(name: String, vararg genres: String) = MetaPreview(id = name, type = "tv", name = name, genres = genres.toList())
        assertEquals("Soccer", SportsLiveTv.sportOf(item("Fútbol UEFA Champions League - Slovan Bratislava vs. Real Betis")))
        assertEquals("Basketball", SportsLiveTv.sportOf(item("NCAA Men's Basketball - #10 Louisville vs. #16 Texas Tech")))
        assertEquals("Football", SportsLiveTv.sportOf(item("Chiefs vs Bills", "American Football")))
        assertEquals("Soccer", SportsLiveTv.sportOf(item("SPFL: Rangers vs Aberdeen", "Soccer")))
    }

    @Test
    fun searchMatchesTeamsAndChannels() {
        kotlin.test.assertTrue(SportsHubSearch.matches("packers", "Green Bay Packers at Chicago Bears"))
        kotlin.test.assertTrue(SportsHubSearch.matches("green bay", "Green Bay Packers"))
        kotlin.test.assertTrue(SportsHubSearch.matches("espn 2", "ESPN2"))
        kotlin.test.assertTrue(SportsHubSearch.matches("brew", "Milwaukee Brewers vs Chicago Cubs"))
        kotlin.test.assertTrue(!SportsHubSearch.matches("packers", "Milwaukee Brewers vs Chicago Cubs"))
    }

    @Test
    fun streamingGamesDoNotPullInLoopOrForeignChannels() {
        val event = SportsEvent(
            id = "1", league = SportsLeague.NFL, title = "Green Bay Packers at Los Angeles Rams",
            startTimeEpochMillis = null, status = SportsEventStatus.SCHEDULED, providerName = "ESPN",
            broadcasts = listOf("Netflix"),
        )
        val terms = SportsChannelMatcher.terms(SportsLeague.NFL, listOf(event))
        kotlin.test.assertTrue("netflix" !in terms)
        kotlin.test.assertTrue(!SportsChannelMatcher.matches("UK: 24/7 NETFLIX ACTION 1 RAW", terms + "netflix"))
        kotlin.test.assertTrue(!SportsChannelMatcher.matches("CA - NFL NETWORK HD", terms))
        kotlin.test.assertTrue(SportsChannelMatcher.matches("NFL RedZone", terms))
        assertNull(SportsLiveTv.usChannelName("CA - NFL NETWORK HD"))
    }

    @Test
    fun gamePagesDropSpanishAndLatinFeeds() {
        listOf(
            "ESPN Deportes", "ESPN Deportes US", "(NL) ESPN PLAY ESPN (D)", "(NL) ESPN PLAY ESPN2 (D)",
            "LATIN-BR| ESPN 2", "LATIN-MX| ESPN HD", "LATIN-ARG| ESPN", "ESPN Latino", "|DSTV| ESPN Africa UHD", "|DSTV| ESPN 2 Africa UHD",
        ).forEach { kotlin.test.assertTrue(SportsChannelMatcher.isForeignOrLoop(it), it) }
        listOf("US : ESPN HD", "ESPN 2 FHD US", "ESPN HD US", "ESPN U FHD US", "ACC Network US", "US| ESPN", "NFL RedZone", "FOX 5 New York")
            .forEach { kotlin.test.assertTrue(!SportsChannelMatcher.isForeignOrLoop(it), it) }
    }
}
