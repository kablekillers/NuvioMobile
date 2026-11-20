package com.nuvio.app.features.sports

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
internal actual fun SportsHubIntro(onFinished: () -> Unit) {
    LaunchedEffect(Unit) { onFinished() }
}
