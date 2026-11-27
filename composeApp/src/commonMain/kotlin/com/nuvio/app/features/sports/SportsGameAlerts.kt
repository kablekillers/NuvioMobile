package com.nuvio.app.features.sports

import kotlinx.serialization.Serializable

/** What was last seen of a My Teams game, so each alert is sent once. */
@Serializable
data class SportsAlertSeen(
    val status: SportsEventStatus,
    val soonSent: Boolean = false,
    val startSent: Boolean = false,
    val closeSent: Boolean = false,
    val finalSent: Boolean = false,
    val startMillis: Long? = null,
)

data class SportsGameAlert(
    /** One notification per game: later alerts replace earlier ones. */
    val eventId: String,
    val league: SportsLeague,
    val title: String,
    val body: String,
)

/**
 * Game alerts for the user's favorite teams: about to start, started, close late, final.
 * Pure logic (no network or storage), so it can be checked in tests.
 */
object SportsGameAlerts {
    const val SOON_WINDOW_MILLIS = 20 * 60_000L

    private fun emoji(league: SportsLeague): String = when (league) {
        SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL -> "🏈"
        SportsLeague.NBA, SportsLeague.WNBA, SportsLeague.NCAA_MEN, SportsLeague.NCAA_WOMEN -> "🏀"
        SportsLeague.MLB, SportsLeague.COLLEGE_BASEBALL -> "⚾"
        SportsLeague.NHL, SportsLeague.NCAA_HOCKEY_MEN, SportsLeague.NCAA_HOCKEY_WOMEN -> "🏒"
        else -> "🏆"
    }

    private fun scoreLine(event: SportsEvent): String {
        val away = event.away
        val home = event.home
        return if (away != null && home != null && event.hasScores) {
            "${away.shortLabel} ${away.score} – ${home.shortLabel} ${home.score}"
        } else event.title
    }

    private fun margin(event: SportsEvent): Int? {
        if (!event.hasScores) return null
        val a = event.competitors[0].score?.toIntOrNull() ?: return null
        val b = event.competitors[1].score?.toIntOrNull() ?: return null
        return kotlin.math.abs(a - b)
    }

    /** Late in the game and within one score, by sport. */
    internal fun isCloseLate(event: SportsEvent): Boolean {
        val detail = event.statusDetail?.lowercase() ?: return false
        val m = margin(event) ?: return false
        val ot = Regex("""\bot\b|overtime|\bso\b|shootout""").containsMatchIn(detail)
        return when (event.league) {
            SportsLeague.NFL, SportsLeague.COLLEGE_FOOTBALL ->
                (ot || Regex("""\b4th\b""").containsMatchIn(detail)) && m <= 8
            SportsLeague.NBA, SportsLeague.WNBA ->
                (ot || Regex("""\b4th\b""").containsMatchIn(detail)) && m <= 6
            SportsLeague.NCAA_MEN, SportsLeague.NCAA_WOMEN ->
                (ot || Regex("""\b(2nd|4th)\b""").containsMatchIn(detail)) && m <= 6 && lastMinutes(detail, 5)
            SportsLeague.NHL, SportsLeague.NCAA_HOCKEY_MEN, SportsLeague.NCAA_HOCKEY_WOMEN ->
                (ot || Regex("""\b3rd\b""").containsMatchIn(detail)) && m <= 1
            SportsLeague.MLB, SportsLeague.COLLEGE_BASEBALL ->
                Regex("""\b(8th|9th|1[0-9]th)\b""").containsMatchIn(detail) && m <= 2
            else -> false
        }
    }

    /** "4:12 - 2nd" → under [minutes] left (unknown clock counts as late). */
    private fun lastMinutes(detail: String, minutes: Int): Boolean {
        val clock = Regex("""(\d{1,2}):(\d{2})""").find(detail) ?: return true
        return clock.groupValues[1].toInt() < minutes
    }

    /**
     * Compares the latest games with what was seen before and returns the alerts to send
     * and the new memory. Games not involving a favorite team are ignored.
     */
    fun evaluate(
        events: List<SportsEvent>,
        prefs: SportsPrefs,
        seen: Map<String, SportsAlertSeen>,
        nowMillis: Long,
    ): Pair<List<SportsGameAlert>, Map<String, SportsAlertSeen>> {
        val alerts = mutableListOf<SportsGameAlert>()
        val next = HashMap<String, SportsAlertSeen>()
        events.filter { SportsPreferences.involvesFavorite(it, prefs) }.forEach { event ->
            val key = event.league.name + "|" + event.id
            val before = seen[key]
            var now = (before ?: SportsAlertSeen(event.status)).copy(status = event.status, startMillis = event.startTimeEpochMillis)
            val icon = emoji(event.league)
            val start = event.startTimeEpochMillis
            when (event.status) {
                SportsEventStatus.SCHEDULED -> if (!now.soonSent && start != null &&
                    start - nowMillis in 0..SOON_WINDOW_MILLIS
                ) {
                    val mins = ((start - nowMillis) / 60_000L).coerceAtLeast(1)
                    alerts += SportsGameAlert(event.id, event.league, "$icon ${event.title}", "Starts in $mins min${if (mins == 1L) "" else "s"}. Tap to find a stream.")
                    now = now.copy(soonSent = true)
                }
                SportsEventStatus.LIVE -> {
                    if (!now.startSent) {
                        // Only "it's on" when we saw it before kick-off or it just started, not
                        // for a game already deep into the 3rd quarter when alerts were turned on.
                        val fresh = before != null || (start != null && nowMillis - start < 30 * 60_000L)
                        if (fresh) alerts += SportsGameAlert(event.id, event.league, "$icon ${event.title} is on", "${scoreLine(event)} · Tap to watch.")
                        now = now.copy(startSent = true, soonSent = true)
                    }
                    if (!now.closeSent && isCloseLate(event)) {
                        alerts += SportsGameAlert(event.id, event.league, "$icon Close game: ${event.title}", "${scoreLine(event)} · ${event.statusDetail.orEmpty()}. Tap to watch the finish.")
                        now = now.copy(closeSent = true)
                    }
                }
                SportsEventStatus.FINAL -> if (!now.finalSent) {
                    // Only for games we watched go live (not old finals on first run).
                    if (before != null && before.status == SportsEventStatus.LIVE) {
                        alerts += SportsGameAlert(event.id, event.league, "$icon Final: ${event.title}", scoreLine(event) + (event.statusDetail?.takeIf { "ot" in it.lowercase() }?.let { " ($it)" } ?: ""))
                    }
                    now = now.copy(finalSent = true, startSent = true, soonSent = true)
                }
                else -> Unit
            }
            next[key] = now
        }
        return alerts to next
    }

    /** When the next check should run: soon while a favorite plays, near the next start otherwise. */
    fun nextCheckDelayMillis(seen: Map<String, SportsAlertSeen>, nowMillis: Long): Long? {
        if (seen.values.any { it.status == SportsEventStatus.LIVE }) return 4 * 60_000L
        val nextStart = seen.values.filter { it.status == SportsEventStatus.SCHEDULED }
            .mapNotNull { it.startMillis }.filter { it > nowMillis }.minOrNull() ?: return null
        val soon = nextStart - SOON_WINDOW_MILLIS + 60_000L
        return when {
            soon > nowMillis -> soon - nowMillis
            else -> (nextStart + 2 * 60_000L - nowMillis).coerceAtLeast(60_000L)
        }.takeIf { it < 6 * 60 * 60_000L }
    }
}
