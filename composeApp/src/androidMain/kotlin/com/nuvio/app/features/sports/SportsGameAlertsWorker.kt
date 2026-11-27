package com.nuvio.app.features.sports

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nuvio.app.features.settings.AppIconPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Checks My Teams games in the background and sends the game alerts: every 15 minutes, every
 * few minutes while a favorite team is playing, and right before a favorite's game starts.
 */
class SportsGameAlertsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        SportsPreferencesStorage.initialize(app)
        val prefs = SportsPreferences.state.value
        if (!prefs.gameAlerts || prefs.favoriteTeams.isEmpty()) return Result.success()
        val store = app.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val seen = runCatching {
            store.getString(KEY_SEEN, null)?.let { json.decodeFromString(seenSerializer, it) }
        }.getOrNull().orEmpty()
        val events = SportsPreferences.favoriteLeagues(prefs).filter { it.scoreboardPath != null }.flatMap { league ->
            try {
                SportsEventRepository.shared.scoreboard(league, forceRefresh = true).events
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }
        val now = System.currentTimeMillis()
        val (alerts, nextSeen) = SportsGameAlerts.evaluate(events, prefs, seen, now)
        // Keep memory of games that dropped off today's board for a day, so a final isn't re-sent.
        val kept = seen.filterKeys { it !in nextSeen }.filterValues { (it.startMillis ?: now) > now - 36 * 60 * 60_000L }
        val merged = kept + nextSeen
        store.edit().putString(KEY_SEEN, json.encodeToString(seenSerializer, merged)).apply()
        alerts.forEach { notify(app, it) }
        SportsGameAlerts.nextCheckDelayMillis(nextSeen, now)?.let { scheduleSoon(app, it) }
        return Result.success()
    }

    companion object {
        private const val STORE = "kk_sports_alerts"
        private const val KEY_SEEN = "seen"
        private const val CHANNEL = "kk_game_alerts"
        private const val PERIODIC = "kk_game_alerts_periodic"
        private const val SOON = "kk_game_alerts_soon"
        private val json = Json { ignoreUnknownKeys = true }
        private val seenSerializer = MapSerializer(String.serializer(), SportsAlertSeen.serializer())

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Called at app start: the 15-minute check runs whenever alerts are on and a team is followed. */
        fun ensureScheduled(context: Context) {
            val work = PeriodicWorkRequestBuilder<SportsGameAlertsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, work)
            checkNow(context)
        }

        fun checkNow(context: Context) = scheduleSoon(context, 0L)

        private fun scheduleSoon(context: Context, delayMillis: Long) {
            val work = OneTimeWorkRequestBuilder<SportsGameAlertsWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, work)
        }

        private fun notify(context: Context, alert: SportsGameAlert) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Game alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Your teams' games: starting, close late, final"
                    }
                )
            }
            val link = "nuvio://sports?league=${alert.league.name}&event=${Uri.encode(alert.eventId)}"
            val intent = Intent().apply {
                component = AppIconPlatform.currentLauncherComponent(context)
                action = Intent.ACTION_VIEW
                data = Uri.parse(link)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val id = kotlin.math.abs(("game|" + alert.eventId).hashCode())
            val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(com.nuvio.app.R.drawable.ic_notification_small)
                .setContentTitle(alert.title)
                .setContentText(alert.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
        }
    }
}
