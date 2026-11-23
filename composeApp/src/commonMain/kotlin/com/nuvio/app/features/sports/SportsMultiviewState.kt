package com.nuvio.app.features.sports

/**
 * Immutable 2- or 4-tile multiview selection. Only the focused tile has audio.
 * Actual stream preparation and playback are owned by the Android player layer.
 */
internal data class SportsMultiviewState(
    val firstId: String? = null,
    val secondId: String? = null,
    val focusedId: String? = null,
    val thirdId: String? = null,
    val fourthId: String? = null,
    val maxTiles: Int = 2,
) {
    init { require(maxTiles == 2 || maxTiles == 4) }

    val streamIds: List<String> get() = listOfNotNull(firstId, secondId, thirdId, fourthId)
    val canAdd: Boolean get() = streamIds.size < maxTiles

    fun withLayout(count: Int): SportsMultiviewState {
        require(count == 2 || count == 4)
        val ids = streamIds.take(count)
        return fromIds(ids, focusedId?.takeIf { it in ids } ?: ids.firstOrNull(), count)
    }

    fun add(id: String): SportsMultiviewState {
        if (id.isBlank() || id in streamIds || !canAdd) return this
        val ids = streamIds + id
        return fromIds(ids, focusedId ?: id, maxTiles)
    }

    fun focus(id: String): SportsMultiviewState =
        if (id in streamIds) copy(focusedId = id) else this

    fun remove(id: String): SportsMultiviewState {
        if (id !in streamIds) return this
        val ids = streamIds.filterNot { it == id }
        return fromIds(ids, focusedId?.takeIf { it in ids } ?: ids.firstOrNull(), maxTiles)
    }

    fun shouldPlayAudio(id: String): Boolean = id == focusedId && id in streamIds

    private fun fromIds(ids: List<String>, focus: String?, capacity: Int) = SportsMultiviewState(
        firstId = ids.getOrNull(0),
        secondId = ids.getOrNull(1),
        focusedId = focus,
        thirdId = ids.getOrNull(2),
        fourthId = ids.getOrNull(3),
        maxTiles = capacity,
    )
}
