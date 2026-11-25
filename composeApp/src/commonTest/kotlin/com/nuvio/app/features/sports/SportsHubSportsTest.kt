package com.nuvio.app.features.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SportsHubSportsTest {
    @Test
    fun onlyUsSportsAreOffered() {
        val members = HubSports.list.flatMap { it.members }
        listOf(SportsLeague.CRICKET, SportsLeague.RUGBY, SportsLeague.AFL, SportsLeague.DARTS, SportsLeague.BILLIARDS, SportsLeague.OTHER)
            .forEach { assertTrue(it !in members, "$it should not be in the hub") }
        assertEquals(listOf(SportsLeague.NCAA_MEN, SportsLeague.NCAA_WOMEN), HubSports.byKey("cbb").members)
        assertEquals(HubSports.All, HubSports.byKey("cricket"))
    }

    @Test
    fun scoreboardsCoverTheNewLeagues() {
        listOf(SportsLeague.NCAA_HOCKEY_MEN, SportsLeague.NCAA_HOCKEY_WOMEN, SportsLeague.COLLEGE_BASEBALL, SportsLeague.MLS, SportsLeague.WORLD_CUP)
            .forEach { assertTrue(it in HubSports.scoreboardLeagues) }
        assertTrue(HubSports.byKey("fight").catalogMembers.containsAll(listOf(SportsLeague.COMBAT, SportsLeague.WRESTLING)))
    }
}
