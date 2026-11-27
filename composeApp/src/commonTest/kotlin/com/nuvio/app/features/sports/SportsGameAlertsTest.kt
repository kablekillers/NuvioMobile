package com.nuvio.app.features.sports

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SportsGameAlertsTest {
    private val prefs = SportsPrefs(favoriteTeams = setOf("NFL:GB"))

    private fun game(status: SportsEventStatus, detail: String? = null, gb: String? = null, chi: String? = null, start: Long = 1_000_000L) =
        SportsEvent(
            id = "nfl:1", league = SportsLeague.NFL, title = "Packers at Bears", startTimeEpochMillis = start,
            status = status, providerName = "ESPN", statusDetail = detail,
            competitors = listOf(
                SportsCompetitor("Green Bay Packers", "GB", gb, isHome = false),
                SportsCompetitor("Chicago Bears", "CHI", chi, isHome = true),
            ),
        )

    @Test
    fun fullGameSendsSoonStartCloseFinalOnce() {
        val start = 10_000_000L
        var seen = emptyMap<String, SportsAlertSeen>()
        fun step(event: SportsEvent, now: Long): List<SportsGameAlert> {
            val (alerts, next) = SportsGameAlerts.evaluate(listOf(event), prefs, seen, now)
            seen = next
            return alerts
        }
        assertEquals(1, step(game(SportsEventStatus.SCHEDULED, start = start), start - 10 * 60_000L).size)
        assertEquals(0, step(game(SportsEventStatus.SCHEDULED, start = start), start - 5 * 60_000L).size)
        assertEquals(1, step(game(SportsEventStatus.LIVE, "1st 14:00", "0", "0", start), start + 60_000L).size)
        assertEquals(0, step(game(SportsEventStatus.LIVE, "3rd 2:00", "14", "10", start), start + 7_200_000L).size)
        val close = step(game(SportsEventStatus.LIVE, "4th 3:12", "21", "17", start), start + 9_000_000L)
        assertTrue(close.single().title.startsWith("🏈 Close game"))
        assertEquals(0, step(game(SportsEventStatus.LIVE, "4th 1:00", "21", "20", start), start + 9_500_000L).size)
        assertTrue(step(game(SportsEventStatus.FINAL, "Final", "24", "20", start), start + 11_000_000L).single().title.startsWith("🏈 Final"))
        assertEquals(0, step(game(SportsEventStatus.FINAL, "Final", "24", "20", start), start + 12_000_000L).size)
    }

    @Test
    fun noAlertsForOtherTeamsOrOldFinals() {
        val other = SportsPrefs(favoriteTeams = setOf("NFL:KC"))
        assertEquals(0, SportsGameAlerts.evaluate(listOf(game(SportsEventStatus.LIVE, "1st", "0", "0")), other, emptyMap(), 1_100_000L).first.size)
        assertEquals(0, SportsGameAlerts.evaluate(listOf(game(SportsEventStatus.FINAL, "Final", "3", "0")), prefs, emptyMap(), 9_000_000L).first.size)
    }
}
