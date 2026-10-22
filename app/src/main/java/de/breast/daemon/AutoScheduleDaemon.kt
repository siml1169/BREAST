package de.breast.daemon

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import de.breast.api.PointTApiClient
import de.breast.auth.AndroidPrefsScheduleStateStore
import de.breast.auth.AndroidPrefsTokenStore
import de.breast.auth.SingleKeyAuthException
import de.breast.auth.SingleKeyAuthManager
import de.breast.auth.UserSettingsStore
import de.breast.optimizer.DailySwitchSchedulePlan
import de.breast.optimizer.SwitchProgramScheduler
import de.breast.spot.SpotPriceClient
import de.breast.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

@Serializable
data class ScheduleSyncState(
    val lastSyncedTodayDate: String? = null,
    val lastSyncedTomorrowDate: String? = null,
    val lastAppliedSliderTempC: Double? = null,
    val lastAppliedOperationHours: Double? = null,
    val lastAppliedBudgetEuro: Double? = null,
    val lastSyncTimestampIso: String? = null,
    val todayWindowSummary: String? = null,
    val tomorrowWindowSummary: String? = null,
    val lastRunLog: String? = null
)

interface ScheduleStateStore {
    suspend fun loadState(): ScheduleSyncState
    suspend fun saveState(state: ScheduleSyncState)
}

@Serializable
data class HourlyCheckResult(
    val timestampIso: String,
    val checkedSpotApi: Boolean,
    val tomorrowPricesAvailable: Boolean,
    val pushedToHeatPump: Boolean,
    val syncedDates: List<String>,
    val logs: List<String>
)

class AutoScheduleDaemon(
    private val spotClient: SpotPriceClient,
    private val pointTClient: PointTApiClient,
    private val scheduler: SwitchProgramScheduler,
    private val stateStore: ScheduleStateStore,
    private val explicitGatewayId: String? = null,
    private val dryRun: Boolean = false
) {
    companion object {
        const val DAY_AHEAD_RELEASE_START_HOUR = 12
    }

    suspend fun runHourlyCheckCycle(
        now: ZonedDateTime = ZonedDateTime.now(SpotPriceClient.BERLIN_ZONE)
    ): HourlyCheckResult {
        val logs = mutableListOf<String>()
        val nowIso = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)
        val todayStr = today.toString()
        val tomorrowStr = tomorrow.toString()

        val state = stateStore.loadState()
        val cfg = scheduler.config

        val configChanged = state.lastAppliedSliderTempC != cfg.targetHeatingTempC ||
            state.lastAppliedOperationHours != cfg.targetOperationHoursPerDay ||
            state.lastAppliedBudgetEuro != cfg.dailyBudgetEuro

        val needsTodaySync = configChanged ||
            (state.lastSyncedTodayDate != todayStr && state.lastSyncedTomorrowDate != todayStr)

        val isAfterNoon = now.hour >= DAY_AHEAD_RELEASE_START_HOUR
        val needsTomorrowSync = isAfterNoon && (configChanged || state.lastSyncedTomorrowDate != tomorrowStr)

        if (!needsTodaySync && !needsTomorrowSync) {
            logs.add(
                "[$nowIso] Schedule already up-to-date (today=$todayStr, tomorrowSynced=${state.lastSyncedTomorrowDate ?: "pending 12:00"})."
            )
            return HourlyCheckResult(
                timestampIso = nowIso,
                checkedSpotApi = false,
                tomorrowPricesAvailable = state.lastSyncedTomorrowDate == tomorrowStr,
                pushedToHeatPump = false,
                syncedDates = emptyList(),
                logs = logs
            )
        }

        logs.add("[$nowIso] Checking EPEX Spot Day-Ahead prices (hour=${now.hour}:00)...")
        val spotSlots = spotClient.fetchDayAheadPrices(today, tomorrow)

        val todayComplete = scheduler.hasCompleteDayPrices(today, spotSlots)
        val tomorrowComplete = scheduler.hasCompleteDayPrices(tomorrow, spotSlots)

        val plansToSync = mutableListOf<DailySwitchSchedulePlan>()
        if (todayComplete) {
            scheduler.buildDailySwitchSchedule(today, spotSlots)?.let { plansToSync.add(it) }
        }
        if (isAfterNoon) {
            if (tomorrowComplete) {
                scheduler.buildDailySwitchSchedule(tomorrow, spotSlots)?.let { plansToSync.add(it) }
                logs.add("[$nowIso] Tomorrow's ($tomorrowStr) Day-Ahead prices published! Computed single contiguous lowest-price window.")
            } else {
                logs.add("[$nowIso] Tomorrow's ($tomorrowStr) Day-Ahead prices not yet published (12:00-15:00 window). Will retry next hour.")
            }
        }

        val shouldPushNow = (needsTodaySync && todayComplete) ||
            (needsTomorrowSync && tomorrowComplete)
        if (!shouldPushNow || plansToSync.isEmpty()) {
            return HourlyCheckResult(
                timestampIso = nowIso,
                checkedSpotApi = true,
                tomorrowPricesAvailable = tomorrowComplete,
                pushedToHeatPump = false,
                syncedDates = emptyList(),
                logs = logs
            )
        }

        val gatewayId = explicitGatewayId ?: if (!dryRun) {
            pointTClient.getGateways().firstOrNull()?.deviceId
                ?: error("No Bosch gateways found on SingleKey ID account.")
        } else {
            "DRY_RUN_GATEWAY"
        }

        for (plan in plansToSync) {
            logs.add(
                "[$nowIso] Plan ${plan.date} (${plan.dayOfWeekCode}): ${plan.plannedOperationMinutes} min " +
                    "(Budget Cut: ${plan.budgetCutMinutes} min) | Est. Cost: ${String.format("%.2f", plan.estimatedDailyCostEuro)} EUR | " +
                    "Slider Temp: ${plan.targetHeatingTempC}°C"
            )
            plan.sharedWindows.forEachIndexed { i, w ->
                logs.add("   Window ${i + 1}: ${w.formatWindow()}")
            }
        }

        val syncLogs = scheduler.syncSchedulesToHeatPump(
            apiClient = pointTClient,
            gatewayId = gatewayId,
            plans = plansToSync,
            dryRun = dryRun
        )
        logs.addAll(syncLogs)

        val syncSucceeded = !syncLogs.any { it.contains("ok=false") }

        fun formatPlanSummary(plan: DailySwitchSchedulePlan): String {
            val win = plan.sharedWindows.firstOrNull() ?: return "No window scheduled"
            val sh = win.startMinutes / 60
            val sm = win.startMinutes % 60
            val eh = win.endMinutes / 60
            val em = win.endMinutes % 60
            val hours = plan.plannedOperationMinutes / 60.0
            val budgetNote = if (plan.budgetCutMinutes > 0) " (budget-cut -${plan.budgetCutMinutes}m)" else ""
            return String.format(
                "%02d:%02d – %02d:%02d  (%.1f h%s)\nAvg %.1f ct/kWh gross  •  Est. %.2f €  •  Target %.1f °C",
                sh, sm, eh, em, hours, budgetNote,
                win.averageGrossCtPerKwh,
                plan.estimatedDailyCostEuro,
                plan.targetHeatingTempC
            )
        }

        val todayPlan = plansToSync.firstOrNull { it.date == todayStr }
        val tomorrowPlan = plansToSync.firstOrNull { it.date == tomorrowStr }

        val newState = ScheduleSyncState(
            lastSyncedTodayDate = if (syncSucceeded && todayComplete) todayStr else state.lastSyncedTodayDate,
            lastSyncedTomorrowDate = if (syncSucceeded && isAfterNoon && tomorrowComplete) tomorrowStr else state.lastSyncedTomorrowDate,
            lastAppliedSliderTempC = if (syncSucceeded) cfg.targetHeatingTempC else state.lastAppliedSliderTempC,
            lastAppliedOperationHours = if (syncSucceeded) cfg.targetOperationHoursPerDay else state.lastAppliedOperationHours,
            lastAppliedBudgetEuro = if (syncSucceeded) cfg.dailyBudgetEuro else state.lastAppliedBudgetEuro,
            lastSyncTimestampIso = nowIso,
            todayWindowSummary = todayPlan?.let { formatPlanSummary(it) } ?: state.todayWindowSummary,
            tomorrowWindowSummary = tomorrowPlan?.let { formatPlanSummary(it) }
                ?: if (state.lastSyncedTomorrowDate == tomorrowStr) state.tomorrowWindowSummary else null,
            lastRunLog = logs.joinToString("\n")
        )
        stateStore.saveState(newState)

        return HourlyCheckResult(
            timestampIso = nowIso,
            checkedSpotApi = true,
            tomorrowPricesAvailable = tomorrowComplete,
            pushedToHeatPump = syncSucceeded,
            syncedDates = if (syncSucceeded) plansToSync.map { it.date } else emptyList(),
            logs = logs
        )
    }

    suspend fun startBackgroundLoop(onCycleCompleted: ((HourlyCheckResult) -> Unit)? = null) {
        while (coroutineContext.isActive) {
            val now = ZonedDateTime.now(SpotPriceClient.BERLIN_ZONE)
            val result = runCatching {
                runHourlyCheckCycle(now)
            }.getOrElse { e ->
                val errIso = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                HourlyCheckResult(
                    timestampIso = errIso,
                    checkedSpotApi = false,
                    tomorrowPricesAvailable = false,
                    pushedToHeatPump = false,
                    syncedDates = emptyList(),
                    logs = listOf("[$errIso] ERROR: ${e.message}")
                )
            }
            onCycleCompleted?.invoke(result)

            val afterRun = ZonedDateTime.now(SpotPriceClient.BERLIN_ZONE)
            val nextHour = afterRun.plusHours(1).withMinute(0).withSecond(5).withNano(0)
            val sleepMillis = Duration.between(afterRun, nextHour).toMillis().coerceAtLeast(10_000L)
            delay(sleepMillis)
        }
    }
}

/**
 * Built-in Android `JobService` (`JobScheduler`) that executes every hour in the background,
 * checking for newly published Day-Ahead spot prices starting at 12:00 (12:00-15:00 CET),
 * keeping the SingleKey ID OAuth refresh token alive, pushing the updated single-window
 * Heating & Warm Water schedule back to the Bosch heat pump, and notifying the user immediately
 * if re-authentication is ever needed.
 */
class SpotScheduleJobService : JobService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        activeJob = serviceScope.launch {
            val httpClient = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build()

            val tokenStore = AndroidPrefsTokenStore(applicationContext)
            val stateStore = AndroidPrefsScheduleStateStore(applicationContext)
            val settingsStore = UserSettingsStore(applicationContext)

            val authManager = SingleKeyAuthManager(httpClient, tokenStore)
            val pointTClient = PointTApiClient(httpClient, authManager)
            val spotClient = SpotPriceClient(httpClient)
            val scheduler = SwitchProgramScheduler(settingsStore.buildScheduleConfig())

            // 1. Proactively validate/refresh SingleKey ID token so we detect credential expiry immediately
            val tokenValid = runCatching {
                authManager.getValidAccessToken()
                true
            }.getOrElse { err ->
                if (err is SingleKeyAuthException) {
                    BreastNotificationHelper.notifyCredentialIssue(
                        context = applicationContext,
                        message = err.message
                    )
                }
                false
            }

            if (!tokenValid) {
                jobFinished(params, false)
                return@launch
            }

            val daemon = AutoScheduleDaemon(
                spotClient = spotClient,
                pointTClient = pointTClient,
                scheduler = scheduler,
                stateStore = stateStore,
                dryRun = false
            )

            val success = runCatching {
                val res = daemon.runHourlyCheckCycle()
                BreastNotificationHelper.clearCredentialIssueNotification(applicationContext)
                if (res.pushedToHeatPump && res.syncedDates.isNotEmpty()) {
                    BreastNotificationHelper.notifyScheduleSynced(
                        context = applicationContext,
                        syncedDates = res.syncedDates,
                        targetTempC = scheduler.config.targetHeatingTempC,
                        runtimeHours = scheduler.config.targetOperationHoursPerDay
                    )
                }
                true
            }.getOrElse { err ->
                if (err is SingleKeyAuthException) {
                    BreastNotificationHelper.notifyCredentialIssue(
                        context = applicationContext,
                        message = err.message
                    )
                    false
                } else {
                    false
                }
            }

            jobFinished(params, !success)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        activeJob?.cancel()
        return true
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 42012
        private const val JOB_ID_ONE_SHOT = 42013
        const val ACTION_SYSTEM_CRON_TICK = "de.breast.ACTION_SYSTEM_CRON_TICK"

        fun scheduleHourlyBackgroundSync(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val component = ComponentName(context, SpotScheduleJobService::class.java)
            val jobInfo = JobInfo.Builder(JOB_ID, component)
                .setPeriodic(TimeUnit.HOURS.toMillis(1))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            scheduler.schedule(jobInfo)
            scheduleNextSystemAlarm(context)
        }

        fun triggerImmediateSyncJob(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val component = ComponentName(context, SpotScheduleJobService::class.java)
            val jobInfo = JobInfo.Builder(JOB_ID_ONE_SHOT, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(0L)
                .setOverrideDeadline(TimeUnit.SECONDS.toMillis(30))
                .build()
            scheduler.schedule(jobInfo)
        }

        fun scheduleNextSystemAlarm(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val now = ZonedDateTime.now(SpotPriceClient.BERLIN_ZONE)
            // Target :05 past the next hour (especially 12:05, 13:05, 14:05, 15:05 CET when EPEX prices publish)
            val nextTick = now.plusHours(1).withMinute(5).withSecond(0).withNano(0)
            val triggerAtMillis = nextTick.toInstant().toEpochMilli()

            val intent = Intent(context, BootCompletedReceiver::class.java).apply {
                action = ACTION_SYSTEM_CRON_TICK
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, 420, intent, flags)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, triggerAtMillis, TimeUnit.MINUTES.toMillis(5), pi)
            }
        }

        fun cancelBackgroundSync(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.cancel(JOB_ID)
            scheduler.cancel(JOB_ID_ONE_SHOT)

            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, BootCompletedReceiver::class.java).apply {
                action = ACTION_SYSTEM_CRON_TICK
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, 420, intent, flags)
            am.cancel(pi)
        }

        fun isHourlyJobScheduled(context: Context): Boolean {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            return scheduler.allPendingJobs.any { it.id == JOB_ID }
        }
    }
}

/**
 * OS-level BroadcastReceiver that handles:
 * 1. Device reboot (`BOOT_COMPLETED`) and app update (`MY_PACKAGE_REPLACED`)
 * 2. System-level `AlarmManager` cron ticks (`ACTION_SYSTEM_CRON_TICK`)
 * The app process does NOT need to stay running in memory; Android's `system_server` wakes this receiver on schedule.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val settings = UserSettingsStore(context)
        if (!settings.autoBackgroundEnabled) return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                SpotScheduleJobService.scheduleHourlyBackgroundSync(context)
            }
            SpotScheduleJobService.ACTION_SYSTEM_CRON_TICK -> {
                SpotScheduleJobService.scheduleNextSystemAlarm(context)
                SpotScheduleJobService.triggerImmediateSyncJob(context)
            }
        }
    }
}

/**
 * System notification helper for:
 * 1. High-priority credential alerts when Bosch SingleKey ID requires re-authentication.
 * 2. Background schedule sync confirmations.
 */
object BreastNotificationHelper {
    const val EXTRA_PROMPT_LOGIN = "de.breast.EXTRA_PROMPT_LOGIN"

    private const val CHANNEL_AUTH_ALERTS = "breast_auth_alerts"
    private const val CHANNEL_SYNC_STATUS = "breast_sync_status"

    private const val NOTIF_ID_AUTH_ALERT = 9001
    private const val NOTIF_ID_SYNC_STATUS = 9002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val authChannel = NotificationChannel(
            CHANNEL_AUTH_ALERTS,
            "SingleKey ID Login Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when Bosch SingleKey ID token expires or requires re-login"
            enableVibration(true)
        }

        val syncChannel = NotificationChannel(
            CHANNEL_SYNC_STATUS,
            "Daily Heat Pump Schedule Sync",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications when new EPEX Spot schedules are pushed to the heat pump"
        }

        nm.createNotificationChannel(authChannel)
        nm.createNotificationChannel(syncChannel)
    }

    fun notifyCredentialIssue(context: Context, message: String) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PROMPT_LOGIN, true)
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = PendingIntent.getActivity(context, 101, tapIntent, pendingFlags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_AUTH_ALERTS)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notification = builder
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Bosch SingleKey ID Login Needed")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText("$message\nTap to open BREAST and sign in so daily heat pump scheduling continues."))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIF_ID_AUTH_ALERT, notification)
    }

    fun clearCredentialIssueNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID_AUTH_ALERT)
    }

    fun notifyScheduleSynced(
        context: Context,
        syncedDates: List<String>,
        targetTempC: Double,
        runtimeHours: Double
    ) {
        ensureChannels(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = PendingIntent.getActivity(context, 102, tapIntent, pendingFlags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_SYNC_STATUS)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val summary = String.format(
            "Pushed %.1fh lowest-price window (%.1f°C) for %s",
            runtimeHours,
            targetTempC,
            syncedDates.joinToString(", ")
        )

        val notification = builder
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("Heat Pump Schedule Updated")
            .setContentText(summary)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIF_ID_SYNC_STATUS, notification)
    }
}
