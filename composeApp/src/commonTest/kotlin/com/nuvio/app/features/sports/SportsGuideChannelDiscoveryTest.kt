package com.nuvio.app.features.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SportsGuideChannelDiscoveryTest {
    @Test fun deduplicatesAndPrioritizesRealBroadcastNetworks() {
        fun event(id: String, broadcasts: List<String>) = SportsEvent(
            id = id,
            league = SportsLeague.NFL,
            title = "Example matchup",
            startTimeEpochMillis = null,
            status = SportsEventStatus.SCHEDULED,
            providerName = "test",
            broadcasts = broadcasts,
        )
        val channels = SportsGuideChannelDiscovery.fromBroadcasts(listOf(
            event("one", listOf("ESPN", "NFL Network", "Unknown")),
            event("two", listOf("ESPN HD", "FS1")),
        ))
        assertEquals(listOf("nfl-network", "espn", "fs1"), channels.map { it.id })
        assertTrue(channels.none { it.playable })
    }
}
