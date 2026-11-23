package com.nuvio.app.features.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SportsGuideTest {
    @Test
    fun noChannelsProducesNoGuideEntries() {
        assertTrue(SportsGuide.fromChannels(emptyList()).isEmpty())
        assertTrue(SportsGuide.current(emptyList(), 1000L).isEmpty())
    }

    @Test
    fun guideDoesNotInventScheduleData() {
        // An unknown schedule remains visible as a channel, without a fake program time.
        // Real timed entries are included only during their provider-reported interval.
        val unknown = SportsGuideEntry(source = sampleSource())
        val timed = SportsGuideEntry(source = sampleSource(), startEpochMillis = 100L, endEpochMillis = 200L)
        assertEquals(listOf(unknown), SportsGuide.current(listOf(unknown, timed), 250L))
        assertEquals(2, SportsGuide.current(listOf(unknown, timed), 150L).size)
    }

    private fun sampleSource(): SportsCatalogMatch =
        SportsCatalogMatch(
            addonName = "Test Provider",
            item = com.nuvio.app.features.home.MetaPreview(
                id = "test",
                type = "tv",
                name = "Test Channel",
                poster = null,
            ),
            score = 100,
            kind = SportsContentKind.EVENT,
        )
}
