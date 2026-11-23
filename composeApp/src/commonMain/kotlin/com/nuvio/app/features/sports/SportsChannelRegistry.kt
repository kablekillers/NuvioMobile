package com.nuvio.app.features.sports

/**
 * Channel identities for the sports-only guide.
 *
 * This registry maps provider-specific channel names to stable IDs. Artwork is deliberately
 * not guessed: the guide should use licensed/approved channel-logo assets or provider-supplied
 * artwork and display the channel name when no verified logo is available.
 */
internal data class SportsChannelIdentity(
    val id: String,
    val displayName: String,
    val aliases: Set<String>,
)

internal object SportsChannelRegistry {
    private val channels = listOf(
        SportsChannelIdentity("espn", "ESPN", setOf("espn", "espn hd")),
        SportsChannelIdentity("espn2", "ESPN2", setOf("espn2", "espn 2")),
        SportsChannelIdentity("espnu", "ESPNU", setOf("espnu", "espn u")),
        SportsChannelIdentity("espn-news", "ESPNews", setOf("espnews", "espn news")),
        SportsChannelIdentity("nfl-network", "NFL Network", setOf("nfl network", "nfln")),
        SportsChannelIdentity("nfl-redzone", "NFL RedZone", setOf("nfl redzone", "redzone", "nfl rz")),
        SportsChannelIdentity("fs1", "FS1", setOf("fs1", "fox sports 1")),
        SportsChannelIdentity("fs2", "FS2", setOf("fs2", "fox sports 2")),
        SportsChannelIdentity("nba-tv", "NBA TV", setOf("nba tv", "nbatv")),
        SportsChannelIdentity("mlb-network", "MLB Network", setOf("mlb network", "mlbn")),
        SportsChannelIdentity("nhl-network", "NHL Network", setOf("nhl network", "nhln")),
        SportsChannelIdentity("big-ten-network", "Big Ten Network", setOf("big ten network", "btn")),
        SportsChannelIdentity("sec-network", "SEC Network", setOf("sec network", "secn")),
        SportsChannelIdentity("acc-network", "ACC Network", setOf("acc network", "accn")),
        SportsChannelIdentity("cbs-sports-network", "CBS Sports Network", setOf("cbs sports network", "cbssn")),
        SportsChannelIdentity("golf-channel", "Golf Channel", setOf("golf channel")),
        SportsChannelIdentity("tennis-channel", "Tennis Channel", setOf("tennis channel")),
    )

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
            .replace(Regex("\\s+(hd|fhd|uhd|4k)$"), "")

    private val byAlias = channels.flatMap { channel ->
        (channel.aliases + channel.displayName).map { normalize(it) to channel }
    }.toMap()

    fun identify(providerChannelName: String): SportsChannelIdentity? {
        // Preserve distinct linear channels: Pluto's NFL Channel is not NFL Network.
        val cleaned = providerChannelName
            .replace(Regex("^[\\s\\p{So}\\p{Sk}\\p{Cntrl}]+"), "")
            .replace(Regex("(?i)^(?:live|watch)\\s*[:\\-]\\s*"), "")
            .replace(Regex("(?i)\\s*[|·•]\\s*(?:live|hd|fhd|uhd|4k|us|usa)$"), "")
            .trim()
        val normalized = normalize(cleaned)
        byAlias[normalized]?.let { return it }
        if ("pluto" in normalized || "nfl channel" in normalized) return null
        val withoutRegion = normalized.replace(Regex("\\s+(?:us|usa|hd|fhd|uhd|4k)(?:\\s+(?:us|usa|hd|fhd|uhd|4k))*$"), "")
        return byAlias[withoutRegion]
    }

    fun knownChannels(): List<SportsChannelIdentity> = channels
}
