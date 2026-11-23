package com.nuvio.app.features.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SportsM3uChannelParserTest {
    @Test fun recognizesOnlyKnownChannelsAndRetainsProviderArtwork() {
        val playlist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="espn.us" tvg-logo="https://example.org/espn.png",ESPN HD
            https://example.org/live/espn.m3u8
            #EXTINF:-1 tvg-name="NFL RedZone" tvg-logo="https://example.org/redzone.png",Red Zone
            https://example.org/live/redzone.m3u8
            #EXTINF:-1,Unknown Entertainment
            https://example.org/live/unknown.m3u8
        """.trimIndent()
        val channels = SportsM3uChannelParser.recognizedSportsChannels(playlist)
        assertEquals(listOf("espn", "nfl-redzone"), channels.map { it.id })
        assertEquals("https://example.org/espn.png", channels.first().logoUrl)
        assertTrue(channels.none { it.playable }) // Playback requires stream resolution.
    }

    @Test fun rejectsUnsecuredUrlsAndOrphanedEntries() {
        val playlist = """
            #EXTM3U
            https://example.org/orphan.m3u8
            #EXTINF:-1,ESPN
            http://example.org/insecure.m3u8
        """.trimIndent()
        assertTrue(SportsM3uChannelParser.parse(playlist).isEmpty())
    }
}
