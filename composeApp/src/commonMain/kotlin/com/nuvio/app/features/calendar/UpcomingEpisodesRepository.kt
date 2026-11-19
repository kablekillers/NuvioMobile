package com.nuvio.app.features.calendar

import co.touchlab.kermit.Logger
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.notifications.isSeriesLibraryType
import com.nuvio.app.features.notifications.releaseDateIso
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.math.roundToInt

/** One episode of a show in the viewer's library, with its air date. */
data class CalendarEpisode(
    val showId: String,
    val showType: String,
    val showName: String,
    val poster: String?,
    val background: String?,
    val thumbnail: String?,
    val season: Int?,
    val episode: Int?,
    val title: String?,
    val dateIso: String,
    /** Days from today: 0 today, 1 tomorrow, -1 yesterday. */
    val dayOffset: Int,
) {
    val code: String
        get() = when {
            season != null && episode != null -> "S${season}E$episode"
            episode != null -> "E$episode"
            else -> ""
        }
}

data class CalendarUiState(
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val upcoming: List<CalendarEpisode> = emptyList(),
    val recent: List<CalendarEpisode> = emptyList(),
)

/**
 * Upcoming and recently aired episodes of the series in the library, for the Calendar screen and
 * Home's "Coming up" row. Built from the same show details the new-episode alerts use; kept in
 * memory and refreshed at most every few hours.
 */
object UpcomingEpisodesRepository {
    private const val MAX_SHOWS = 60
    private const val PAST_DAYS = 14
    private const val FUTURE_DAYS = 60
    private const val REFRESH_AFTER_MS = 3 * 60 * 60 * 1000L
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private val log = Logger.withTag("UpcomingEpisodes")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()
    private var loadedAtMs = 0L
    private var loadedForDay: String? = null

    fun ensureLoaded(force: Boolean = false) {
        scope.launch { refresh(force) }
    }

    private suspend fun refresh(force: Boolean) = mutex.withLock {
        val today = CurrentDateProvider.todayIsoDate()
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        if (!force && _state.value.hasLoaded && loadedForDay == today && now - loadedAtMs < REFRESH_AFTER_MS) return@withLock
        _state.value = _state.value.copy(isLoading = true)
        val todayStart = CurrentDateProvider.localStartOfDayEpochMs(today) ?: now
        LibraryRepository.ensureLoaded()
        val shows = LibraryRepository.uiState.value.items
            .filter { isSeriesLibraryType(it.type) }
            .distinctBy { it.type to it.id }
            .take(MAX_SHOWS)
        val gate = Semaphore(4)
        val episodes = coroutineScopeAsync(shows.map { show ->
            suspend {
                gate.withPermit {
                    val meta = runCatching { MetaDetailsRepository.fetch(type = show.type, id = show.id) }
                        .onFailure { log.w { "Couldn't load episodes for ${show.name}: ${it.message}" } }
                        .getOrNull() ?: return@withPermit emptyList<CalendarEpisode>()
                    meta.videos.mapNotNull { video ->
                        if (video.season == null && video.episode == null) return@mapNotNull null
                        if (video.season == 0) return@mapNotNull null
                        val date = releaseDateIso(video.released) ?: return@mapNotNull null
                        val start = CurrentDateProvider.localStartOfDayEpochMs(date) ?: return@mapNotNull null
                        val offset = ((start - todayStart).toDouble() / DAY_MS).roundToInt()
                        if (offset < -PAST_DAYS || offset > FUTURE_DAYS) return@mapNotNull null
                        CalendarEpisode(
                            showId = show.id,
                            showType = show.type,
                            showName = meta.name.ifBlank { show.name },
                            poster = meta.poster ?: show.poster,
                            background = meta.background ?: show.banner,
                            thumbnail = video.thumbnail,
                            season = video.season,
                            episode = video.episode,
                            title = video.title?.takeIf { it.isNotBlank() },
                            dateIso = date,
                            dayOffset = offset,
                        )
                    }
                }
            }
        }).flatten()
        _state.value = CalendarUiState(
            isLoading = false,
            hasLoaded = true,
            upcoming = episodes.filter { it.dayOffset >= 0 }
                .sortedWith(compareBy<CalendarEpisode> { it.dateIso }.thenBy { it.showName }.thenBy { it.season ?: 0 }.thenBy { it.episode ?: 0 }),
            recent = episodes.filter { it.dayOffset < 0 }
                .sortedWith(compareByDescending<CalendarEpisode> { it.dateIso }.thenBy { it.showName }),
        )
        loadedAtMs = now
        loadedForDay = today
    }

    private suspend fun <T> coroutineScopeAsync(blocks: List<suspend () -> T>): List<T> =
        kotlinx.coroutines.coroutineScope { blocks.map { block -> async { block() } }.awaitAll() }

    /** "Today", "Tomorrow", "Yesterday", or e.g. "Fri, Nov 21". */
    fun dayLabel(episode: CalendarEpisode): String = when (episode.dayOffset) {
        0 -> "Today"
        1 -> "Tomorrow"
        -1 -> "Yesterday"
        else -> shortDate(episode.dateIso)
    }

    fun shortDate(iso: String): String {
        val parts = iso.split('-').mapNotNull { it.toIntOrNull() }
        if (parts.size < 3) return iso
        val (y, m, d) = parts
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val days = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        // Sakamoto's day-of-week method.
        val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
        val yy = if (m < 3) y - 1 else y
        val dow = (yy + yy / 4 - yy / 100 + yy / 400 + t[(m - 1).coerceIn(0, 11)] + d) % 7
        return "${days[dow]}, ${months[(m - 1).coerceIn(0, 11)]} $d"
    }
}
