package com.nuvio.app.features.sports

import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.supportsResource
import com.nuvio.app.features.streams.InstalledStreamAddonTarget
import com.nuvio.app.features.streams.StreamLoadCompletion
import com.nuvio.app.features.streams.StreamSearchSessions
import com.nuvio.app.features.streams.StreamSearchTargets
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resolve each Sports Hub catalog item independently using its actual catalog
 * type and ID. This does not mutate StreamsRepository's global picker state.
 * Only direct streams are eligible for the current multiview player.
 */
internal object SportsMultiviewStreamResolver {
    suspend fun directStreams(
        source: SportsCatalogMatch,
        addons: List<ManagedAddon>,
    ): List<StreamItem> {
        val addon = addons.firstOrNull { managed ->
            managed.isActive && managed.displayTitle == source.addonName &&
                managed.manifest?.supportsResource("stream", source.item.type, source.item.id) == true
        } ?: return emptyList()
        val manifest = addon.manifest ?: return emptyList()
        val target = InstalledStreamAddonTarget(
            addonName = addon.displayTitle,
            addonId = addon.streamAddonInstanceId(manifest.id),
            manifest = manifest,
        )
        val targets = StreamSearchTargets(
            type = source.item.type,
            videoId = source.item.id,
            season = null,
            episode = null,
            streamAddons = listOf(target),
            serverSources = emptyList(),
            pluginProviderGroups = emptyList(),
        )
        val streams = mutableListOf<StreamItem>()
        withTimeoutOrNull(15_000L) {
            StreamSearchSessions.search(
                profileId = ProfileRepository.activeProfileId,
                targets = targets,
                forceRefresh = false,
            ).collect { completion ->
                if (completion is StreamLoadCompletion.Addon) {
                    streams.addAll(completion.group.streams)
                }
            }
        }
        return streams.filter { SportsMultiviewSourceSelection.directUrl(it) != null }
            .distinctBy { it.playableDirectUrl }
    }
}
