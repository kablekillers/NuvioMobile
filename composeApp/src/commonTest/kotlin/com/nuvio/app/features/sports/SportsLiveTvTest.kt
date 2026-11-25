package com.nuvio.app.features.sports

import com.nuvio.app.features.home.MetaPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SportsLiveTvTest {
    @Test
    fun keepsUsChannelsAndDropsForeignFeeds() {
        assertEquals("ESPN", SportsLiveTv.usChannelName("4K| ESPN UHD"))
        assertEquals("ESPN2", SportsLiveTv.usChannelName("US| ESPN 2 HD"))
        assertEquals("ESPN2", SportsLiveTv.usChannelName("ESPN2"))
        assertEquals("FS1", SportsLiveTv.usChannelName("USA: Fox Sports 1"))
        assertEquals("FOX", SportsLiveTv.usChannelName("FOX 5 New York"))
        assertEquals("NFL RedZone", SportsLiveTv.usChannelName("NFL RedZone"))
        assertEquals("ESPNews", SportsLiveTv.usChannelName("ESPNews"))
        assertNull(SportsLiveTv.usChannelName("SA| ESPN 1 HD"))
        assertNull(SportsLiveTv.usChannelName("CO| CLARO-ESPN2"))
        assertNull(SportsLiveTv.usChannelName("ESPN Deportes"))
        assertNull(SportsLiveTv.usChannelName("TNT Sports 1"))
        assertNull(SportsLiveTv.usChannelName("FOX News"))
    }

    @Test
    fun groupsEventsBySport() {
        fun item(name: String, vararg genres: String) = MetaPreview(id = name, type = "tv", name = name, genres = genres.toList())
        assertEquals("Soccer", SportsLiveTv.sportOf(item("Fútbol UEFA Champions League - Slovan Bratislava vs. Real Betis")))
        assertEquals("Basketball", SportsLiveTv.sportOf(item("NCAA Men's Basketball - #10 Louisville vs. #16 Texas Tech")))
        assertEquals("Football", SportsLiveTv.sportOf(item("Chiefs vs Bills", "American Football")))
        assertEquals("Soccer", SportsLiveTv.sportOf(item("SPFL: Rangers vs Aberdeen", "Soccer")))
    }
}
