package com.nuvio.app.features.sports

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Device-local Sports Hub choices. Never synced, so profiles and cloud sync are untouched. */
internal expect object SportsPreferencesStorage {
    fun load(): String?
    fun save(payload: String)
}

@Serializable
data class SportsPrefs(
    /** "LEAGUE:ABBR" keys, e.g. "NFL:GB". */
    val favoriteTeams: Set<String> = emptySet(),
    /** Seconds the scores on screen lag behind, so they don't spoil a delayed stream. */
    val scoreDelaySeconds: Int = 0,
    /** Event ids with a start reminder scheduled. */
    val reminders: Set<String> = emptySet(),
)

object SportsPreferences {
    val delayOptions = listOf(0, 30, 60, 120, 300)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SportsPrefs> = _state.asStateFlow()

    private fun load(): SportsPrefs =
        SportsPreferencesStorage.load()?.let { runCatching { json.decodeFromString(SportsPrefs.serializer(), it) }.getOrNull() }
            ?: SportsPrefs()

    private fun update(change: (SportsPrefs) -> SportsPrefs) {
        val next = change(_state.value)
        _state.value = next
        SportsPreferencesStorage.save(json.encodeToString(SportsPrefs.serializer(), next))
    }

    fun teamKey(league: SportsLeague, team: SportsCompetitor): String = "${league.name}:${team.shortLabel.uppercase()}"

    fun isFavorite(league: SportsLeague, team: SportsCompetitor): Boolean = teamKey(league, team) in _state.value.favoriteTeams

    fun toggleFavorite(league: SportsLeague, team: SportsCompetitor) = update { prefs ->
        val key = teamKey(league, team)
        prefs.copy(favoriteTeams = if (key in prefs.favoriteTeams) prefs.favoriteTeams - key else prefs.favoriteTeams + key)
    }

    fun toggleFavoriteKey(key: String) = update { prefs ->
        prefs.copy(favoriteTeams = if (key in prefs.favoriteTeams) prefs.favoriteTeams - key else prefs.favoriteTeams + key)
    }

    fun setScoreDelay(seconds: Int) = update { it.copy(scoreDelaySeconds = seconds.coerceIn(0, 600)) }

    fun setReminder(eventId: String, on: Boolean) = update { prefs ->
        prefs.copy(reminders = if (on) prefs.reminders + eventId else prefs.reminders - eventId)
    }

    /** Leagues that have at least one favorite team. */
    fun favoriteLeagues(prefs: SportsPrefs = _state.value): Set<SportsLeague> =
        prefs.favoriteTeams.mapNotNull { key -> SportsLeague.entries.firstOrNull { it.name == key.substringBefore(':') } }.toSet()

    fun involvesFavorite(event: SportsEvent, prefs: SportsPrefs = _state.value): Boolean =
        event.competitors.any { "${event.league.name}:${it.shortLabel.uppercase()}" in prefs.favoriteTeams }
}

/** Schedules a local notification shortly before a game starts. */
internal expect object SportsReminderPlatform {
    /** Returns false when notifications aren't allowed or the time has passed. */
    suspend fun schedule(id: String, title: String, body: String, triggerAtEpochMillis: Long): Boolean
    suspend fun cancel(id: String)
}

object SportsReminders {
    const val LEAD_MILLIS = 10 * 60_000L

    suspend fun toggle(event: SportsEvent, nowMillis: Long): Boolean {
        val on = event.id in SportsPreferences.state.value.reminders
        val id = reminderId(event)
        if (on) {
            SportsReminderPlatform.cancel(id)
            SportsPreferences.setReminder(event.id, false)
            return false
        }
        val start = event.startTimeEpochMillis ?: return false
        val trigger = (start - LEAD_MILLIS).coerceAtLeast(nowMillis + 5_000L)
        if (start <= nowMillis) return false
        val scheduled = SportsReminderPlatform.schedule(
            id = id,
            title = "${event.title} starts soon",
            body = listOfNotNull("Kick-off in about 10 minutes", event.broadcasts.takeIf { it.isNotEmpty() }?.let { "TV: ${it.joinToString()}" })
                .joinToString(" · "),
            triggerAtEpochMillis = trigger,
        )
        if (scheduled) SportsPreferences.setReminder(event.id, true)
        return scheduled
    }

    private fun reminderId(event: SportsEvent) = "kk-sports-${event.id.replace(':', '-')}"
}
