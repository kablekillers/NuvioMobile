package com.nuvio.app.features.player

import java.io.File

/**
 * External players on Windows: VLC and mpv, found in their usual install
 * folders or on PATH. The stream opens in the player with its headers and
 * resume position.
 */
internal actual object ExternalPlayerPlatform {
    private const val VLC = "vlc"
    private const val MPV = "mpv"

    actual fun defaultPlayerId(): String? = availablePlayers().firstOrNull()?.id

    actual fun availablePlayers(): List<ExternalPlayerApp> = buildList {
        if (DesktopExternalPlayers.find(VLC) != null) add(ExternalPlayerApp(VLC, "VLC"))
        if (DesktopExternalPlayers.find(MPV) != null) add(ExternalPlayerApp(MPV, "mpv"))
    }

    actual fun open(request: ExternalPlayerPlaybackRequest, playerId: String?): ExternalPlayerOpenResult {
        val command = DesktopExternalPlayers.command(request, playerId ?: defaultPlayerId())
            ?: return ExternalPlayerOpenResult.NoPlayerAvailable
        return runCatching {
            ProcessBuilder(command).redirectErrorStream(true).start()
            ExternalPlayerOpenResult.Opened
        }.getOrDefault(ExternalPlayerOpenResult.Failed)
    }

    actual fun buildIntent(request: ExternalPlayerPlaybackRequest, playerId: String?): ExternalPlayerIntentResult {
        val command = DesktopExternalPlayers.command(request, playerId ?: defaultPlayerId())
            ?: return ExternalPlayerIntentResult.NotConfigured
        return ExternalPlayerIntentResult.Success(DesktopExternalPlayers.Launch(command))
    }
}

internal object DesktopExternalPlayers {
    /** What [ExternalPlayerIntentResult.Success.intent] holds on Windows. */
    data class Launch(val command: List<String>)

    fun find(id: String): File? {
        val programFiles = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA"))
        val candidates = when (id) {
            "vlc" -> programFiles.map { File(it, "VideoLAN\\VLC\\vlc.exe") }
            "mpv" -> programFiles.flatMap { listOf(File(it, "mpv\\mpv.exe"), File(it, "Programs\\mpv\\mpv.exe")) }
            else -> emptyList()
        }
        candidates.firstOrNull { it.isFile }?.let { return it }
        val exe = if (System.getProperty("os.name").orEmpty().startsWith("Windows", true)) "$id.exe" else id
        return System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
            .map { File(it, exe) }
            .firstOrNull { it.isFile }
    }

    fun command(request: ExternalPlayerPlaybackRequest, playerId: String?): List<String>? {
        val id = playerId ?: return null
        val exe = find(id) ?: return null
        val title = request.buildPlayerTitle(includeEpisodeTitle = true)
        val headers = sanitizePlaybackHeaders(request.sourceHeaders)
        val startSeconds = (request.resumePositionMs / 1000L).takeIf { it > 0 }
        return when (id) {
            "vlc" -> buildList {
                add(exe.path)
                add("--meta-title=$title")
                headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { add("--http-user-agent=${it.value}") }
                headers.entries.firstOrNull { it.key.equals("Referer", true) }?.let { add("--http-referrer=${it.value}") }
                startSeconds?.let { add("--start-time=$it") }
                request.subtitles?.firstOrNull()?.let { add("--input-slave=${it.url}") }
                add(request.sourceUrl)
            }
            "mpv" -> buildList {
                add(exe.path)
                add("--force-media-title=$title")
                if (headers.isNotEmpty()) {
                    add("--http-header-fields=" + headers.entries.joinToString(",") { "${it.key}: ${it.value.replace(",", "\\,")}" })
                }
                startSeconds?.let { add("--start=$it") }
                request.subtitles?.forEach { add("--sub-file=${it.url}") }
                add(request.sourceUrl)
            }
            else -> null
        }
    }
}
