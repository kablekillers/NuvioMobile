package com.nuvio.app.features.sports

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Platform video surface; URLs are independently resolved before entering here. */
@Composable
internal expect fun SportsMultiviewSurface(
    urls: List<Pair<String, String>>,
    focusedId: String?,
    onFocus: (String) -> Unit,
    modifier: Modifier = Modifier,
)
