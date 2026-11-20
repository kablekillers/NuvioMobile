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

    private fun item(id: String, name: String, genres: List<String> = emptyList(), runtime: String? = null, description: String? = null) =
        MetaPreview(id = id, type = "sport", name = name, genres = genres, runtime = runtime, description = description)
}
