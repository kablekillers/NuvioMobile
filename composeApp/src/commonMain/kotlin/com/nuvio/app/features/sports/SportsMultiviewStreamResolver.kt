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
 * Search all compatible installed add-ons. The current player still accepts direct links only.
 */
internal object SportsMultiviewStreamResolver {
    suspend fun directStreams(
        source: SportsCatalogMatch,
        addons: List<ManagedAddon>,
    ): List<StreamItem> {
        // Query every enabled addon that declares support for this actual catalog ID.
        // Never manufacture an ID for an addon whose catalog uses a different scheme.
        val targetsForItem = addons.filter { managed ->
            managed.isActive &&
                managed.manifest?.supportsResource("stream", source.item.type, source.item.id) == true
        }.mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            InstalledStreamAddonTarget(
                addonName = addon.displayTitle,
                addonId = addon.streamAddonInstanceId(manifest.id),
                manifest = manifest,
            )
        }
        if (targetsForItem.isEmpty()) return emptyList()
        val targets = StreamSearchTargets(
            type = source.item.type,
            videoId = source.item.id,
            season = null,
            episode = null,
            streamAddons = targetsForItem,
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
