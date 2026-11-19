package com.nuvio.app.features.sports

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationPlatform
import com.nuvio.app.features.notifications.EpisodeReleaseNotificationWorker
import java.util.concurrent.TimeUnit

internal actual object SportsPreferencesStorage {
    private const val preferencesName = "kk_sports_hub"
    private const val payloadKey = "sports_prefs"
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    actual fun load(): String? = preferences?.getString(payloadKey, null)

    actual fun save(payload: String) {
        preferences?.edit()?.putString(payloadKey, payload)?.apply()
    }
}

internal actual object SportsReminderPlatform {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual suspend fun schedule(id: String, title: String, body: String, triggerAtEpochMillis: Long): Boolean {
        val context = appContext ?: return false
        if (!EpisodeReleaseNotificationPlatform.notificationsAuthorized() &&
            !EpisodeReleaseNotificationPlatform.requestAuthorization()
        ) return false
        EpisodeReleaseNotificationPlatform.ensureChannel()
        val delay = triggerAtEpochMillis - System.currentTimeMillis()
        if (delay <= 0L) return false
        val data = Data.Builder()
            .putString(EpisodeReleaseNotificationPlatform.workerRequestIdKey, id)
            .putString(EpisodeReleaseNotificationPlatform.workerTitleKey, title)
            .putString(EpisodeReleaseNotificationPlatform.workerBodyKey, body)
            .putString(EpisodeReleaseNotificationPlatform.workerDeepLinkKey, "nuvio://sports")
            .build()
        val work = OneTimeWorkRequestBuilder<EpisodeReleaseNotificationWorker>()
            .setInputData(data)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag("kk_sports_reminders")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, work)
        return true
    }

    actual suspend fun cancel(id: String) {
        val context = appContext ?: return
        WorkManager.getInstance(context).cancelUniqueWork(id)
    }
}
