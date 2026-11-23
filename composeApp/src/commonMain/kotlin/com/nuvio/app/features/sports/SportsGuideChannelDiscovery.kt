package com.nuvio.app.features.sports

/**
 * Discover channels from real broadcaster metadata in sports scoreboards.
 *
 * A broadcast listing is NOT an EPG entry or proof of a playable stream.
 * EPG and stream resolution must come from their respective providers.
 */
internal object SportsGuideChannelDiscovery {
    fun fromBroadcasts(events: List<SportsEvent>): List<SportsGuideChannel> {
        val channels = events.flatMap { it.broadcasts }
            .mapNotNull { SportsGuideSchedule.channelFromProviderName(it) }
            .groupBy { it.id }
            .map { (_, matches) ->
                matches.first().copy(sourceNames = matches.flatMap { it.sourceNames }.toSet())
            }
        val priority = listOf("nfl-network", "nfl-redzone", "espn", "espn2", "espnu", "fs1")
        return channels.sortedWith(compareBy<SportsGuideChannel> {
            priority.indexOf(it.id).let { index -> if (index == -1) Int.MAX_VALUE else index }
        }.thenBy { it.name })
    }
}
