package com.nuvio.app.features.sports

import com.nuvio.app.features.home.MetaPreview

/**
 * An add-on catalog entry is the source of truth for the playable ID.
 * Never substitute an ESPN event ID into an add-on stream endpoint.
 */
data class SportsCatalogMatch(
    val addonName: String,
    val item: MetaPreview,
    val score: Int,
    val kind: SportsContentKind
)

internal object SportsEventMatcher {
    private val noise = setOf("fc", "cf", "the", "vs", "v", "at", "live", "stream", "hd", "4k", "watch")

    private fun tokens(value: String): Set<String> =
        value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .split(' ')
            .filter { it.length > 1 && it !in noise }
            .toSet()

    private val practice = Regex("""\b(?:fp|(?:free\s+)?practice\s*)([1-3])\b""", RegexOption.IGNORE_CASE)
    private val qualifying = Regex("""\b(?:qualifying|qual|shootout)\b""", RegexOption.IGNORE_CASE)

    private fun matchesRacingSession(session: String, title: String): Boolean {
        val advertisedPractice = practice.find(title)?.groupValues?.get(1)?.let { "fp$it" }
        val isQualifying = qualifying.containsMatchIn(title)
        val titleTokens = tokens(title)
        if (advertisedPractice == null && "practice" in titleTokens) return false
        val isSprint = "sprint" in titleTokens
        val advertised = when {
            advertisedPractice != null -> advertisedPractice
            isSprint && isQualifying -> "sprintqual"
            isSprint -> "sprint"
            isQualifying -> "qual"
            else -> "race"
        }
        val expected = session.lowercase().filter(Char::isLetterOrDigit)
        return advertised == when (expected) {
            "sprintshootout", "sprintqualifying", "sprintqual", "sq" -> "sprintqual"
            else -> expected
        }
    }

    /**
     * Require both teams for ordinary two-sided games. One matching team alone
     * is unsafe: multiple simultaneous games can share a team or league name.
     * UFC cards without two competitors require a full event-title overlap.
     */
    fun score(event: SportsEvent, catalogTitle: String): Int {
        if (event.racingSession?.let { !matchesRacingSession(it, catalogTitle) } == true) return 0
        val titleTokens = tokens(catalogTitle)
        if (titleTokens.isEmpty()) return 0
        val sides = event.competitors.take(2)
        if (sides.size == 2) {
            val first = tokens(sides[0].name)
            val second = tokens(sides[1].name)
            if (first.isEmpty() || second.isEmpty()) return 0
            val a = (first - second).intersect(titleTokens).size
            val b = (second - first).intersect(titleTokens).size
            if (a == 0 || b == 0) return 0
            return 50 + (a * 10) + (b * 10)
        }
        val eventTokens = tokens(event.catalogMatchTitle ?: event.title)
        if (eventTokens.size < 2) return 0
        val overlap = eventTokens.intersect(titleTokens).size
        return if (overlap >= 2 && overlap * 3 >= eventTokens.size * 2) 40 + overlap * 10 else 0
    }
}
