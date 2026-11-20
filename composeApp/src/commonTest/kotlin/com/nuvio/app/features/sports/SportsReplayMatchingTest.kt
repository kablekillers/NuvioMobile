package com.nuvio.app.features.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SportsReplayMatchingTest {
    @Test
    fun findsReplaysAcrossFullSchedulesWithoutMatchingOneTeamAlone() {
        val scenarios = listOf(
            Scenario(SportsLeague.NHL, "Detroit Red Wings", "Toronto Maple Leafs", "Red Wings vs Maple Leafs"),
            Scenario(SportsLeague.COLLEGE_FOOTBALL, "Wisconsin Badgers", "Iowa Hawkeyes", "Wisconsin at Iowa"),
            Scenario(SportsLeague.NCAA_MEN, "Duke Blue Devils", "North Carolina Tar Heels", "Duke at North Carolina"),
            Scenario(SportsLeague.NCAA_WOMEN, "UConn Huskies", "South Carolina Gamecocks", "UConn vs South Carolina"),
            Scenario(SportsLeague.MLB, "Milwaukee Brewers", "Chicago Cubs", "Brewers vs Cubs"),
            Scenario(SportsLeague.NFL, "Green Bay Packers", "Chicago Bears", "Packers vs Bears"),
            Scenario(SportsLeague.NBA, "Milwaukee Bucks", "Boston Celtics", "Bucks vs Celtics"),
        )
        for (scenario in scenarios) {
            val fillers = List(100) { i -> event(scenario.league, "test-$i", "Red Team $i", "Blue Squad $i") }
            val matcher = SportsEventMatcher.Prepared(
                fillers + event(scenario.league, "target", scenario.away, scenario.home),
            )
            repeat(300) { assertEquals(0, matcher.score("Unrelated content $it")) }
            assertTrue(matcher.score(scenario.replay) > 0, scenario.league.label)
            assertEquals(0, matcher.score(scenario.away), scenario.league.label)
        }
    }

    private data class Scenario(
        val league: SportsLeague,
        val away: String,
        val home: String,
        val replay: String,
    )

    private fun event(league: SportsLeague, id: String, away: String, home: String) =
        SportsEvent(
            id = id,
            league = league,
            title = "$away at $home",
            startTimeEpochMillis = null,
            status = SportsEventStatus.FINAL,
            providerName = "Test",
            competitors = listOf(SportsCompetitor(away), SportsCompetitor(home, isHome = true)),
        )
}
