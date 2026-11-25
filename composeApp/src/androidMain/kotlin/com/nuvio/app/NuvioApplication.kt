package com.nuvio.app

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.nuvio.app.core.network.IPv4FirstDns
import com.nuvio.app.core.network.NetworkErrorInterceptor
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor
import com.nuvio.app.features.servers.plex.PlexArtworkInterceptor
import com.nuvio.app.features.servers.silo.SiloArtworkInterceptor
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Dispatcher
import okio.Path.Companion.toOkioPath

/**
 * Custom Application class that implements [SingletonImageLoader.Factory] to guarantee
 * the Coil ImageLoader (with [CustomPosterFallbackInterceptor]) is created before any
 * image request. Without this, Coil's auto-init ContentProvider creates the singleton
 * before MainActivity.onCreate, and interceptors registered later are ignored.
 */
class NuvioApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // Kable Killers Music (ported from Nuvio One M, GPL-3.0) lives in Android-only code
        // and is plugged into the shared Music tab here.
        com.nuvio.app.features.music.MusicHost.screen = { modifier ->
            androidx.compose.foundation.layout.Box(modifier) { com.nuvio.app.music.MusicScreen() }
        }
        com.nuvio.app.features.music.MusicHost.nowPlayingPill = { modifier, onOpen ->
            com.nuvio.app.music.MusicNowPlayingPill(modifier, onOpen)
        }
        com.nuvio.app.music.MusicWidgetUpdater.startIfPlaced(this)
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        return ImageLoader.Builder(context)
            .crossfade(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            // Keep more posters on disk so returning to the app rarely re-downloads artwork.
            // Same directory as Coil's default, so the existing cache is kept.
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            .components {
                add(CustomPosterFallbackInterceptor())
                add(PlexArtworkInterceptor())
                add(SiloArtworkInterceptor())
                add(SvgDecoder.Factory())
                add(
                    KtorNetworkFetcherFactory(
                        httpClient = {
                            HttpClient(OkHttp) {
                                engine {
                                    addInterceptor(NetworkErrorInterceptor())
                                    config {
                                        // Same IPv4-first lookup the addon client uses, to avoid
                                        // stalls on networks with broken IPv6 routes.
                                        dns(IPv4FirstDns())
                                        // OkHttp allows only 5 parallel requests per host by default,
                                        // so a catalog row of TMDB/metahub posters downloaded 5 at a time.
                                        dispatcher(
                                            Dispatcher().apply {
                                                maxRequests = IMAGE_MAX_REQUESTS
                                                maxRequestsPerHost = IMAGE_MAX_REQUESTS_PER_HOST
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        cacheStrategy = { CacheControlCacheStrategy() },
                    ),
                )
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }
}

private const val IMAGE_DISK_CACHE_BYTES = 512L * 1024L * 1024L
private const val IMAGE_MAX_REQUESTS = 64
private const val IMAGE_MAX_REQUESTS_PER_HOST = 16
