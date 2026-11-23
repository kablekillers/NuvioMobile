package com.nuvio.app.features.sports

/**
 * Parses channel metadata from an M3U playlist supplied by a configured provider.
 * This does not fetch playlists, validate rights, or expose unverified streams.
 * Stream URLs deliberately stay out of the guide model until player integration.
 */
internal data class SportsM3uEntry(
    val name: String,
    val tvgId: String?,
    val logoUrl: String?,
    val streamUrl: String,
)

internal object SportsM3uChannelParser {
    private val attribute = Regex("""([a-zA-Z][a-zA-Z0-9-]*)\s*=\s*"([^"]*)"""")

    fun parse(playlist: String): List<SportsM3uEntry> {
        val lines = playlist.lineSequence().map { it.trim() }.toList()
        val found = mutableListOf<SportsM3uEntry>()
        var pending: Map<String, String>? = null
        var pendingName = ""
        for (line in lines) {
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                pending = attribute.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                pendingName = line.substringAfterLast(',', "").trim()
            } else if (line.startsWith("#")) {
                continue
            } else if (line.isNotBlank()) {
                val attrs = pending
                if (attrs != null && line.startsWith("https://", ignoreCase = true)) {
                    val name = attrs["tvg-name"]?.takeIf { it.isNotBlank() } ?: pendingName
                    if (name.isNotBlank()) {
                        found += SportsM3uEntry(
                            name = name,
                            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                            logoUrl = attrs["tvg-logo"]?.takeIf { it.startsWith("https://", ignoreCase = true) },
                            streamUrl = line,
                        )
                    }
                }
                pending = null
            }
        }
        return found
    }

    fun recognizedSportsChannels(playlist: String): List<SportsGuideChannel> =
        parse(playlist).mapNotNull { entry ->
            SportsGuideSchedule.channelFromProviderName(entry.name, entry.logoUrl)
        }.groupBy { it.id }.map { (_, matches) ->
            matches.first().copy(sourceNames = matches.flatMap { it.sourceNames }.toSet())
        }
}
