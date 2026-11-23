package com.nuvio.app.features.sports

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun SportsMultiviewSurface(
    urls: List<Pair<String, String>>,
    focusedId: String?,
    onFocus: (String) -> Unit,
    modifier: Modifier,
) {
    SportsMultiviewPlayers(
        tiles = urls.map { (id, url) -> SportsMultiviewTile(id, url) },
        focusedId = focusedId,
        onFocus = onFocus,
        modifier = modifier,
    )
}
