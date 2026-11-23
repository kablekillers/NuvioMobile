package com.nuvio.app.features.sports

import kotlin.test.Test
import kotlin.test.assertTrue

class SportsGuideTest {
    @Test
    fun noChannelsProducesNoGuideEntries() {
        assertTrue(SportsGuide.fromChannels(emptyList()).isEmpty())
        assertTrue(SportsGuide.current(emptyList(), 1000L).isEmpty())
    }
}
