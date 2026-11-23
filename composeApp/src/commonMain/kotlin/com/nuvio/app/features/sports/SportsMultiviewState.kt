package com.nuvio.app.features.sports

/**
 * Pure state for a two-stream sports view. Player instances must be owned by the
 * Android playback layer; this model does not open streams or claim playback works.
 */
internal data class SportsMultiviewState(
    val firstId: String? = null,
    val secondId: String? = null,
    val focusedId: String? = null,
) {
    val streamIds: List<String> get() = listOfNotNull(firstId, secondId)
    val canAdd: Boolean get() = streamIds.size < 2

    fun add(id: String): SportsMultiviewState {
        if (id.isBlank() || id in streamIds || !canAdd) return this
        return if (firstId == null) copy(firstId = id, focusedId = focusedId ?: id)
        else copy(secondId = id, focusedId = focusedId ?: id)
    }

    fun focus(id: String): SportsMultiviewState =
        if (id in streamIds) copy(focusedId = id) else this

    fun remove(id: String): SportsMultiviewState {
        if (id !in streamIds) return this
        val remaining = streamIds.filterNot { it == id }
        return SportsMultiviewState(
            firstId = remaining.getOrNull(0),
            secondId = remaining.getOrNull(1),
            focusedId = focusedId?.takeIf { it in remaining } ?: remaining.firstOrNull(),
        )
    }

    fun shouldPlayAudio(id: String): Boolean = id == focusedId && id in streamIds
}
