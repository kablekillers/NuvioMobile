package com.nuvio.app.features.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SportsChannelRegistryTest {
    @Test fun identifiesNetworkAliases() {
        assertEquals("espn", SportsChannelRegistry.identify("ESPN HD")?.id)
        assertEquals("nfl-redzone", SportsChannelRegistry.identify("NFL RZ")?.id)
        assertEquals("fs1", SportsChannelRegistry.identify("FOX Sports 1")?.id)
        assertEquals("big-ten-network", SportsChannelRegistry.identify("BTN")?.id)
    }

    @Test fun rejectsUnverifiedChannelNames() {
        assertNull(SportsChannelRegistry.identify("Some Sports Stream"))
        assertNull(SportsChannelRegistry.identify("ESPN Random Extra"))
    }

    @Test fun recognizesInstalledAddonNamesWithoutMergingDifferentChannels() {
        assertEquals("nfl-redzone", SportsChannelRegistry.identify("NFL RedZone")?.id)
        assertEquals("nfl-network", SportsChannelRegistry.identify("NFL Network HD US")?.id)
        assertEquals("nfl-network", SportsChannelRegistry.identify("🔴 LIVE: NFL Network")?.id)
        assertEquals("nfl-network", SportsChannelRegistry.identify("NFL Network")?.id)
        assertNull(SportsChannelRegistry.identify("NFL Channel · Pluto TV"))
    }

    @Test fun channelIdsAreUnique() {
        val channels = SportsChannelRegistry.knownChannels()
        assertEquals(channels.size, channels.map { it.id }.distinct().size)
        assertTrue(channels.isNotEmpty())
    }
}
