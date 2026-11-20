package com.nuvio.app.features.notifications

import com.nuvio.app.desktop.DesktopNotifier
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal actual object EpisodeReleaseNotificationPlatform {
    private const val prefix = "episode."

    actual suspend fun notificationsAuthorized(): Boolean = DesktopNotifier.isSupported

    actual suspend fun requestAuthorization(): Boolean = DesktopNotifier.isSupported

    actual suspend fun scheduleEpisodeReleaseNotifications(requests: List<EpisodeReleaseNotificationRequest>) {
        DesktopNotifier.cancelAll(prefix)
        requests.forEach { request ->
            val at = runCatching {
                LocalDate.parse(request.releaseDateIso)
                    .atTime(LocalTime.of(EpisodeReleaseNotificationHour, EpisodeReleaseNotificationMinute))
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull() ?: return@forEach
            DesktopNotifier.schedule(prefix + request.requestId, request.notificationTitle, request.notificationBody, at)
        }
    }

    actual suspend fun clearScheduledEpisodeReleaseNotifications() = DesktopNotifier.cancelAll(prefix)

    actual suspend fun showTestNotification(request: EpisodeReleaseNotificationRequest) =
        DesktopNotifier.show(request.notificationTitle, request.notificationBody)
}
