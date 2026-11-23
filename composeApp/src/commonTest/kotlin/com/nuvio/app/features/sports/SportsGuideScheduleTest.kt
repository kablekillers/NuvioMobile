package com.nuvio.app.features.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SportsGuideScheduleTest {
    @Test fun selectsCurrentAndNextFromActualSchedule() {
        val programs = listOf(
            SportsGuideProgram("espn", "Later", 2000, 3000),
            SportsGuideProgram("espn", "Now", 1000, 2000),
            SportsGuideProgram("fs1", "Other network", 1000, 2000),
        )
        val (now, next) = SportsGuideSchedule.nowAndNext("espn", 1500, programs)
        assertEquals("Now", now?.title)
        assertEquals("Later", next?.title)
    }

    @Test fun noScheduleDoesNotInventListings() {
        val (now, next) = SportsGuideSchedule.nowAndNext("nfl-network", 1500, emptyList())
        assertNull(now)
        assertNull(next)
    }

    @Test fun recognizesProviderNameWithoutClaimingPlayback() {
        val channel = SportsGuideSchedule.channelFromProviderName("ESPN HD")
        assertEquals("ESPN", channel?.name)
        assertEquals(false, channel?.playable)
        assertNull(SportsGuideSchedule.channelFromProviderName("Unknown network"))
    }
}
