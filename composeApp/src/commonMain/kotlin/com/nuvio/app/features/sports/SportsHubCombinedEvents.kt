package com.nuvio.app.features.sports

/**
 * Cross-league, read-only Sports Hub feed. Only provider-reported LIVE events are live;
 * a scheduled start time does not establish that an event is broadcasting.
 */
data class SportsHubCombinedFeed(
    val live: List<SportsEvent>,
    val upcoming: List<SportsEvent>
)

object SportsHubCombinedEvents {
    const val UPCOMING_WINDOW_MILLIS: Long = 60L * 60L * 1000L

    fun combine(
        scoreboards: Collection<SportsScoreboard>,
        nowEpochMillis: Long,
        upcomingWindowMillis: Long = UPCOMING_WINDOW_MILLIS
    ): SportsHubCombinedFeed {
        require(upcomingWindowMillis >= 0L)
        val events = scoreboards.flatMap { it.events }
            .filter { it.kind == SportsContentKind.EVENT }
            .distinctBy { event ->
                // Provider event ids are league-qualified. Avoid merging distinct
                // matchups based on fuzzy title similarity.
                event.league.name to event.id
            }
        val live = events.filter { it.status == SportsEventStatus.LIVE }
            .sortedWith(compareBy<SportsEvent> { it.startTimeEpochMillis ?: Long.MAX_VALUE }
                .thenBy { it.league.name }.thenBy { it.title })
        val upcoming = events.filter { event ->
            val start = event.startTimeEpochMillis ?: return@filter false
            event.status == SportsEventStatus.SCHEDULED &&
                start >= nowEpochMillis &&
                start - nowEpochMillis <= upcomingWindowMillis
        }.sortedWith(compareBy<SportsEvent> { it.startTimeEpochMillis ?: Long.MAX_VALUE }
            .thenBy { it.league.name }.thenBy { it.title })
        return SportsHubCombinedFeed(live, upcoming)
    }
}
