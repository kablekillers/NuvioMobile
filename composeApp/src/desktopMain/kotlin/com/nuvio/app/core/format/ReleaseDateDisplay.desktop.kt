package com.nuvio.app.core.format

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal actual fun formatCalendarDate(isoDate: String, localeTag: String, includeYear: Boolean): String {
    val epochMs = parseEpisodeReleaseEpochMs(isoDate) ?: return isoDate
    val locale = Locale.forLanguageTag(localeTag)
    val pattern = if (includeYear) "d MMMM yyyy" else "d MMMM"
    return runCatching {
        DateTimeFormatter.ofPattern(pattern, locale)
            .format(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC))
    }.getOrDefault(isoDate)
}
