package com.nuvio.app.features.sports

import com.nuvio.app.features.addons.AddonCatalog
import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SportsRecordingClassifierTest {
    private val recaps = AddonCatalog("sport", "sports_recaps", "Sport Recaps")
    private val hockey = AddonCatalog("tv", "nuvio_sports_replays_hockey", "Hockey Replays")

    @Test
    fun durationPlacesFullRecordingsInReplaysEvenInTheRecapsCatalog() {
        val replay = item("recap:streamed_hockey_vegas_dallas", "Vegas Golden Knights vs Dallas Stars",
            genres = listOf("hockey", "Recap"), description = "Recorded 20 Nov 2026\nDuration: 4h 12m")
        assertTrue(SportsRecordingClassifier.accepts(SportsAddonCatalogs.SPORTS_STREAMS, recaps, replay, SportsLeague.NHL, 0))
        assertEquals(SportsContentKind.REPLAY, SportsRecordingClassifier.kind(SportsAddonCatalogs.SPORTS_STREAMS, recaps, replay))
        assertEquals(SportsContentKind.REPLAY, SportsRecordingClassifier.kind(SportsAddonCatalogs.NUVIO_LIVE, hockey, replay))
    }

    @Test
    fun shortHockeyClipsShowAsHighlightsEvenInAReplayCatalog() {
        val clip = item("h1", "Vegas vs Dallas", runtime = "00:09:48")
        assertEquals(SportsContentKind.HIGHLIGHT, SportsRecordingClassifier.kind(SportsAddonCatalogs.NUVIO_LIVE, hockey, clip))
        assertEquals(SportsContentKind.HIGHLIGHT, SportsRecordingClassifier.kind(SportsAddonCatalogs.NUVIO_LIVE, hockey, clip.copy(runtime = null)))
        assertEquals(SportsContentKind.REPLAY, SportsRecordingClassifier.kind(SportsAddonCatalogs.SPORTS_STREAMS, recaps, clip.copy(runtime = null)))
    }

    @Test
    fun stremVerseReplaysAreFullGamesAndHighlightsAreClips() {
        val replays = AddonCatalog("tv", "stremverse_replays", "Match Replays")
        val highlights = AddonCatalog("tv", "stremverse_highlights", "Match Highlights")
        val game = item("sv1", "Chiefs vs Bills")
        assertEquals(SportsContentKind.REPLAY, SportsRecordingClassifier.kind(SportsAddonCatalogs.STREMVERSE, replays, game))
        assertEquals(SportsContentKind.HIGHLIGHT, SportsRecordingClassifier.kind(SportsAddonCatalogs.STREMVERSE, highlights, game))
        assertEquals(SportsContentKind.HIGHLIGHT,
            SportsRecordingClassifier.kind(SportsAddonCatalogs.STREMVERSE, replays, item("sv2", "Chiefs vs Bills Extended Highlights")))
        assertEquals(SportsContentKind.REPLAY,
            SportsRecordingClassifier.kind(SportsAddonCatalogs.STREMVERSE, highlights, item("sv3", "Chiefs vs Bills", runtime = "3h 05m")))
        assertEquals(SportsContentKind.REPLAY,
            SportsRecordingClassifier.kind("x", replays, item("sv4", "Chiefs vs Bills", description = "Full match replay")))
    }

    @Test
    fun baseballCannotEnterCollegeTabsEvenWhenTeamWordsCoincide() {
        val collegeLeagues = listOf(SportsLeague.COLLEGE_FOOTBALL, SportsLeague.NCAA_MEN, SportsLeague.NCAA_WOMEN)
        for (league in collegeLeagues) {
            val mlb = item("recap:streamed_baseball_cincinnati_pittsburgh", "Cincinnati vs Pittsburgh")
            assertFalse(SportsRecordingClassifier.accepts(SportsAddonCatalogs.SPORTS_STREAMS, recaps, mlb, league, 90))
            assertFalse(SportsRecordingClassifier.accepts("mixed", recaps, mlb.copy(id = "untyped", genres = listOf("MLB")), league, 90))
        }
    }

    @Test
    fun collegeRecordingsFollowFootballAndTheCorrectBasketballDivision() {
        val football = item("recap:streamed_american_football_temple_rice", "Temple vs Rice", runtime = "4h 15m")
        val basketball = item("recap:streamed_basketball_usc_tarleton", "USC vs Tarleton State",
            genres = listOf("basketball", "NCAAW"), runtime = "3h 46m")
        assertTrue(SportsRecordingClassifier.accepts(SportsAddonCatalogs.SPORTS_STREAMS, recaps, football, SportsLeague.COLLEGE_FOOTBALL, 80))
        assertTrue(SportsRecordingClassifier.accepts(SportsAddonCatalogs.SPORTS_STREAMS, recaps, basketball, SportsLeague.NCAA_WOMEN, 80))
        assertFalse(SportsRecordingClassifier.accepts(SportsAddonCatalogs.SPORTS_STREAMS, recaps, basketball, SportsLeague.NCAA_MEN, 80))
        assertEquals(SportsContentKind.REPLAY, SportsRecordingClassifier.kind(SportsAddonCatalogs.SPORTS_STREAMS, recaps, basketball))
    }

    @Test
    fun hockeyRecapsCannotEnterNbaWithMatchingCityNames() {
        val hockeyRecaps = listOf(
            item("recap:streamed_hockey_toronto_los_angeles", "Toronto Maple Leafs vs Los Angeles Kings",
                runtime = "4h 14m"),
            item("untyped", "Boston Bruins vs Los Angeles Kings", genres = listOf("hockey")),
        )
        assertTrue(SportsRecordingClassifier.appliesTo(SportsLeague.NBA))
        for (recording in hockeyRecaps) {
            assertFalse(SportsRecordingClassifier.accepts(SportsAddonCatalogs.SPORTS_STREAMS, recaps, recording, SportsLeague.NBA, 90))
            assertTrue(SportsRecordingClassifier.accepts(SportsAddonCatalogs.SPORTS_STREAMS, recaps, recording, SportsLeague.NHL, 0))
        }
    }

    @Test
    fun nbaRecapsUseDurationAndRejectOtherBasketballLeagues() {
        val replay = item("recap:streamed_basketball_boston_los_angeles", "Boston Celtics vs Los Angeles Lakers Recap",
            genres = listOf("NBA"), runtime = "3h 14m")
        val addonId = SportsAddonCatalogs.SPORTS_STREAMS
        assertTrue(SportsRecordingClassifier.accepts(addonId, recaps, replay, SportsLeague.NBA, 0))
        assertEquals(SportsContentKind.REPLAY, SportsRecordingClassifier.kind(addonId, recaps, replay))
        assertEquals(SportsContentKind.HIGHLIGHT, SportsRecordingClassifier.kind(addonId, recaps, replay.copy(runtime = "9m 23s")))
        for (otherLeague in listOf("NCAAM", "NCAAW", "WNBA")) {
            assertFalse(SportsRecordingClassifier.accepts(addonId, recaps, replay.copy(genres = listOf(otherLeague)), SportsLeague.NBA, 90))
        }
        val nuvioBasketball = AddonCatalog("tv", "nuvio_sports_replays_basketball", "Basketball Replays")
        assertTrue(SportsRecordingClassifier.accepts(SportsAddonCatalogs.NUVIO_LIVE, nuvioBasketball,
            replay.copy(genres = emptyList()), SportsLeague.NBA, 0))
    }

    @Test
    fun onlyFullSportsStreamsRecordingsGetTheReplayDisplayLabel() {
        val addonId = "addon:${SportsAddonCatalogs.SPORTS_STREAMS}:https://addon.example/manifest.json"
        val label = "Recap (CA EN: SPORTSNET EAST)"
        val subtitle = "Recorded · 4h 14m"
        assertEquals("Replay (CA EN: SPORTSNET EAST)", SportsRecordingClassifier.streamLabel(addonId, label, subtitle))
        assertEquals(label, SportsRecordingClassifier.streamLabel(addonId, label, "Recorded · 9m 23s"))
        assertEquals(label, SportsRecordingClassifier.streamLabel(addonId, label, null))
        assertEquals(label, SportsRecordingClassifier.streamLabel("addon:other:manifest", label, subtitle))
    }

    private fun item(id: String, name: String, genres: List<String> = emptyList(), runtime: String? = null, description: String? = null) =
        MetaPreview(id = id, type = "sport", name = name, genres = genres, runtime = runtime, description = description)
}
