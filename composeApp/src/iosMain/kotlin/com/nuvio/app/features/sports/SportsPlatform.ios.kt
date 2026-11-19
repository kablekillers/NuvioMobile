package com.nuvio.app.features.sports

import com.nuvio.app.features.notifications.EpisodeReleaseNotificationPlatform
import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

internal actual object SportsPreferencesStorage {
    private const val payloadKey = "kk_sports_prefs"

    actual fun load(): String? = NSUserDefaults.standardUserDefaults.stringForKey(payloadKey)

    actual fun save(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey)
    }
}

internal actual object SportsReminderPlatform {
    actual suspend fun schedule(id: String, title: String, body: String, triggerAtEpochMillis: Long): Boolean {
        if (!EpisodeReleaseNotificationPlatform.notificationsAuthorized() &&
            !EpisodeReleaseNotificationPlatform.requestAuthorization()
        ) return false
        val seconds = triggerAtEpochMillis / 1000.0 - NSDate().timeIntervalSince1970
        if (seconds <= 1.0) return false
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setSound(UNNotificationSound.defaultSound)
        }
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(timeInterval = seconds, repeats = false)
        val request = UNNotificationRequest.requestWithIdentifier(identifier = id, content = content, trigger = trigger)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request) { _ -> }
        return true
    }

    actual suspend fun cancel(id: String) {
        UNUserNotificationCenter.currentNotificationCenter().removePendingNotificationRequestsWithIdentifiers(listOf(id))
    }
}
