package com.nuvio.app.features.sports

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun SportsMultiviewSurface(
    tiles: List<SportsMultiviewTile>,
    focusedId: String?,
    onFocus: (String) -> Unit,
    modifier: Modifier,
) = Unit
