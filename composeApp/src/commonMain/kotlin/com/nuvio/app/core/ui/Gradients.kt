package com.nuvio.app.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

internal val SurfaceEdge = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f)),
)
