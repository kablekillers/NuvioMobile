package com.nuvio.app.features.sports

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Platform video grid; every tile is an already resolved direct link. Only [focusedId] has audio. */
@Composable
internal expect fun SportsMultiviewSurface(
    tiles: List<SportsMultiviewTile>,
    focusedId: String?,
    onFocus: (String) -> Unit,
    modifier: Modifier = Modifier,
)
