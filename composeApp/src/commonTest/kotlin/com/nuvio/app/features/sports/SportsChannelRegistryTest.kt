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

    @Test fun channelIdsAreUnique() {
        val channels = SportsChannelRegistry.knownChannels()
        assertEquals(channels.size, channels.map { it.id }.distinct().size)
        assertTrue(channels.isNotEmpty())
    }
}
