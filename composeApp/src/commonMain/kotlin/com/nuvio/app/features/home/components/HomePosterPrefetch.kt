package com.nuvio.app.features.home.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.size.Scale
import com.nuvio.app.core.ui.NuvioPosterShape
import com.nuvio.app.core.ui.PosterCardStyleUiState
import com.nuvio.app.core.ui.aspectRatio
import com.nuvio.app.core.ui.cardWidth
import com.nuvio.app.core.ui.posterImageRequest
import com.nuvio.app.features.home.HomeCatalogSection
import kotlin.math.roundToInt

/**
 * Warms the image cache with the first posters of every home row as soon as the row's data
 * arrives, so rows further down already have artwork when the user scrolls to them.
 *
 * Requests use the same URL, fallback key and on-screen size as [HomePosterCard], so the
 * card picks the decoded image straight from the memory cache (or at worst from disk).
 */
@Composable
internal fun HomePosterPrefetchEffect(
    sections: List<HomeCatalogSection>,
    posterCardStyle: PosterCardStyleUiState,
) {
    val context = LocalPlatformContext.current
    val density = LocalDensity.current.density
    val requested = remember { mutableSetOf<String>() }

    LaunchedEffect(sections, posterCardStyle, density) {
        val imageLoader = SingletonImageLoader.get(context)
        val landscape = posterCardStyle.catalogLandscapeModeEnabled
        sections.forEach { section ->
            section.items.take(PREFETCH_POSTERS_PER_ROW).forEach { item ->
                val (imageUrl, fallbackImageUrl) = homePosterImageUrls(
                    item = item,
                    isLandscapeMode = landscape,
                    alwaysShowLandscapeClearlogo = posterCardStyle.alwaysShowLandscapeClearlogo,
                )
                if (imageUrl.isNullOrBlank()) return@forEach
                val shape = if (landscape) NuvioPosterShape.Landscape else item.posterShape.toNuvioPosterShape()
                val widthPx = (shape.cardWidth(posterCardStyle.widthDp).value * density).roundToInt()
                val heightPx = (widthPx / shape.aspectRatio).roundToInt()
                if (widthPx <= 0 || heightPx <= 0) return@forEach
                val requestKey = "$imageUrl|$fallbackImageUrl|$widthPx"
                if (!requested.add(requestKey)) return@forEach
                imageLoader.enqueue(
                    posterImageRequest(context, imageUrl, fallbackImageUrl) {
                        size(widthPx, heightPx)
                        scale(Scale.FILL)
                    },
                )
            }
        }
    }
}

// About one phone screen of posters per row; enough that rows look complete on arrival
// without decoding images the user may never scroll to.
private const val PREFETCH_POSTERS_PER_ROW = 6
