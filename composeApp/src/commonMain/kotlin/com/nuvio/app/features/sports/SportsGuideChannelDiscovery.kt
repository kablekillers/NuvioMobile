package com.nuvio.app.features.sports

/**
 * Discover channels from real broadcaster metadata in sports scoreboards.
 *
 * A broadcast listing is NOT an EPG entry or proof of a playable stream.
 * EPG and stream resolution must come from their respective providers.
 */
internal object SportsGuideChannelDiscovery {
    /**
     * Merge actual broadcaster listings with metadata from configured playlists.
     * No source is considered playable until a stream resolver confirms it.
     */
    fun merge(
        broadcastChannels: List<SportsGuideChannel>,
        playlistChannels: List<SportsGuideChannel>,
    ): List<SportsGuideChannel> {
        val priority = listOf("nfl-network", "nfl-redzone", "espn", "espn2", "espnu", "fs1")
        return (broadcastChannels + playlistChannels).groupBy { it.id }.map { (_, matches) ->
            val first = matches.first()
            first.copy(
                logoUrl = matches.firstNotNullOfOrNull { it.logoUrl },
                sourceNames = matches.flatMap { it.sourceNames }.toSet(),
                playable = matches.any { it.playable },
            )
        }.sortedWith(compareBy<SportsGuideChannel> {
            priority.indexOf(it.id).let { index -> if (index == -1) Int.MAX_VALUE else index }
        }.thenBy { it.name })
    }

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
