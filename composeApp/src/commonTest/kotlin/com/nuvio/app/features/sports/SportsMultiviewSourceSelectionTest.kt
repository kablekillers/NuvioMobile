package com.nuvio.app.features.sports

import com.nuvio.app.features.streams.StreamItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SportsMultiviewSourceSelectionTest {
    @Test
    fun acceptsResolvedHttpMedia() {
        assertEquals(
            "https://example.org/live.m3u8",
            SportsMultiviewSourceSelection.directUrl(
                StreamItem(url = "https://example.org/live.m3u8", addonName = "Test", addonId = "addon:test")
            )
        )
    }

    @Test
    fun rejectsNonMediaSchemesAndUnresolvedTorrents() {
        assertNull(SportsMultiviewSourceSelection.directUrl(
            StreamItem(url = "file:///local/video.mp4", addonName = "Test", addonId = "addon:test")
        ))
        assertNull(SportsMultiviewSourceSelection.directUrl(
            StreamItem(infoHash = "1234567890abcdef1234567890abcdef12345678", addonName = "Test", addonId = "addon:test")
        ))
    }
}
