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
import de.breast.optimizer.HolisticOptimizationConfig
import de.breast.optimizer.SwitchProgramScheduler
import de.breast.spot.SpotPriceClient
import de.breast.spot.SpotPriceSource
import de.breast.telemetry.SqliteTelemetryStore
import de.breast.telemetry.TelemetryStore
import de.breast.weather.WeatherForecastClient
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
    val lastRunLog: String? = null,
    val appliedScheduleSignatures: Map<String, String> = emptyMap(),
    val appliedScheduleNote: String? = null,
    val manualAdjustmentDetected: Boolean = false,
    val manualAdjustmentHint: String? = null,
    val lastAppliedSettingsSignature: String? = null,
    val appliedPreferenceId: String? = null,
    val todayPhysicsBreakdown: String? = null,
    val tomorrowPhysicsBreakdown: String? = null
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
    private val dryRun: Boolean = false,
    private val telemetryStore: TelemetryStore? = null,
    private val spotSources: List<SpotPriceSource> = SpotPriceSource.ALL,
    private val weatherClient: WeatherForecastClient? = null,
    private val holisticConfig: HolisticOptimizationConfig? = null,
    private val settingsStore: UserSettingsStore? = null
) {
    companion object {
        const val DAY_AHEAD_RELEASE_START_HOUR = 12
    }

    suspend fun runHourlyCheckCycle(
        now: ZonedDateTime = ZonedDateTime.now(SpotPriceClient.BERLIN_ZONE),
        forcePush: Boolean = false
    ): HourlyCheckResult {
        val logs = mutableListOf<String>()
        val nowIso = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val today = now.toLocalDate()
        val tomorrow = today.plusDays(1)
        val todayStr = today.toString()
        val tomorrowStr = tomorrow.toString()

        // 1. Online Adaptive Thermal Observer: Calibrate Hbld and cooldown rate from recent nocturnal setback
        var effectiveHolisticConfig = holisticConfig
        var liveReturnTempC: Double? = null
        if (telemetryStore != null && settingsStore != null) {
            val snapshots = telemetryStore.getRecentSnapshots(limit = 2500)
            liveReturnTempC = snapshots.lastOrNull()?.returnTempC
            val observer = de.breast.telemetry.AdaptiveThermalObserver(
                latitude = settingsStore.weatherLatitude,
                longitude = settingsStore.weatherLongitude
            )
            val calib = observer.performOnlineCalibration(snapshots, settingsStore)
            if (calib != null) {
                logs.add("[$nowIso] ${calib.logMessage}")
            }
        }

        val state = stateStore.loadState()
        val cfg = scheduler.config
        val currentSignature = "${cfg.targetHeatingTempC}_${cfg.targetOperationHoursPerDay}_${effectiveHolisticConfig?.preference?.id}_${effectiveHolisticConfig?.buildingLossKwPerK}_${effectiveHolisticConfig?.targetDailyRuntimeHours}_${effectiveHolisticConfig?.dailyBudgetEuro}_${cfg.dailyBudgetEuro}"
        val settingsChanged = state.lastAppliedSettingsSignature != currentSignature

        // If a manual adjustment was previously detected on the heat pump, do not try to bring it back unless user explicitly forces push!
        if (state.manualAdjustmentDetected && !forcePush) {
            val hint = state.manualAdjustmentHint ?: "manual adjustment detected, no further change"
            logs.add("[$nowIso] $hint (Auto-sync paused to preserve manual edits on the heat pump).")
            return HourlyCheckResult(
                timestampIso = nowIso,
                checkedSpotApi = false,
                tomorrowPricesAvailable = state.lastSyncedTomorrowDate == tomorrowStr,
                pushedToHeatPump = false,
                syncedDates = emptyList(),
                logs = logs
            )
        }

        // A calculated schedule is typically applied once when day-ahead spot market prices publish.
        // Once applied for today or tomorrow, do not automatically overwrite in the background unless forcePush is requested.
        val todayAlreadyApplied = !forcePush && (state.lastSyncedTodayDate == todayStr || state.lastSyncedTomorrowDate == todayStr)
        val needsTodaySync = forcePush || !todayAlreadyApplied

        val isDynamic = spotClient.tariffConfig.pricingModel == de.breast.spot.TariffPricingModel.DYNAMIC_SPOT
        val tomorrowPricesReady = !isDynamic || now.hour >= DAY_AHEAD_RELEASE_START_HOUR
        val tomorrowAlreadyApplied = !forcePush && (state.lastSyncedTomorrowDate == tomorrowStr)
        val needsTomorrowSync = forcePush || (tomorrowPricesReady && !tomorrowAlreadyApplied)

        if (!needsTodaySync && !needsTomorrowSync) {
            val note = state.appliedScheduleNote?.let { " [Noted: $it]" } ?: ""
            logs.add(
                "[$nowIso] Schedule already applied & noted$note (today=$todayStr, tomorrowSynced=${state.lastSyncedTomorrowDate ?: (if (isDynamic) "pending 12:00" else "ready")})."
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

        if (isDynamic) {
            logs.add("[$nowIso] Checking EPEX Spot Day-Ahead prices (sources=${spotSources.joinToString { it.id }}, hour=${now.hour}:00)...")
        } else {
            logs.add("[$nowIso] Composing prices for ${spotClient.tariffConfig.pricingModel.displayName}...")
        }
        val spotSlots = spotClient.fetchDayAheadPrices(today, tomorrow, spotSources)

        val resolvedGatewayId = explicitGatewayId ?: if (!dryRun) {
            runCatching { pointTClient.getGateways().firstOrNull()?.deviceId }.getOrNull()
        } else {
            "DRY_RUN_GATEWAY"
        }

        val store = telemetryStore
        if (resolvedGatewayId != null && !dryRun && store != null) {
            runCatching {
                val nowSec = now.toInstant().epochSecond
                val currentSpot = spotSlots.firstOrNull { slot ->
                    nowSec in slot.startEpochSeconds until slot.endEpochSeconds
                }
                val snap = pointTClient.getHeatPumpTelemetry(
                    gatewayId = resolvedGatewayId,
                    spotPriceGrossCt = currentSpot?.totalGrossCtPerKwh,
                    spotPriceNetCt = currentSpot?.spotNetCtPerKwh
                )
                store.recordSnapshot(snap)
                logs.add(
                    "[$nowIso] Telemetry logged: Outdoor=${snap.outdoorTempC ?: "?"}°C, Supply=${snap.supplyTempC ?: "?"}°C, " +
                        "Return=${snap.returnTempC ?: "?"}°C (ΔT=${String.format("%.1f", snap.deltaTempC ?: 0.0)}K), " +
                        "Mod=${snap.modulationPct?.toInt() ?: 0}%, Demand=${snap.demand ?: "idle"}"
                )
            }.onFailure { err ->
                logs.add("[$nowIso] Telemetry logging notice: ${err.message}")
            }
        }

        val todayComplete = scheduler.hasCompleteDayPrices(today, spotSlots)
        val tomorrowComplete = scheduler.hasCompleteDayPrices(tomorrow, spotSlots)

        val weatherForecast = weatherClient?.let { client ->
            runCatching { client.fetchHourlyForecast(today, 2) }.getOrDefault(emptyList())
        } ?: emptyList()

        val plansToSync = mutableListOf<DailySwitchSchedulePlan>()
        if (todayComplete && needsTodaySync) {
            val todayConfig = effectiveHolisticConfig?.copy(liveStartingReturnTempC = liveReturnTempC)
            scheduler.buildDailySwitchSchedule(today, spotSlots, weatherForecast, todayConfig)?.let { plansToSync.add(it) }
        }
        if (tomorrowComplete && (tomorrowPricesReady || forcePush || needsTomorrowSync)) {
            scheduler.buildDailySwitchSchedule(tomorrow, spotSlots, weatherForecast, effectiveHolisticConfig)?.let {
                plansToSync.add(it)
                val priceNote = if (isDynamic) "Tomorrow's ($tomorrowStr) Day-Ahead prices published!" else "Tomorrow's ($tomorrowStr) ${spotClient.tariffConfig.pricingModel.displayName} schedule compiled!"
                logs.add("[$nowIso] $priceNote Computed holistic schedule window.")
            }
        } else if (tomorrowPricesReady && !tomorrowComplete) {
            logs.add("[$nowIso] Tomorrow's ($tomorrowStr) Day-Ahead prices not yet published (12:00-15:00 window). Will retry next hour.")
        }

        val shouldPushNow = forcePush || (needsTodaySync && todayComplete) || (needsTomorrowSync && tomorrowComplete)
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

        val resolvedGatewayOrNull = explicitGatewayId ?: if (!dryRun) {
            runCatching { pointTClient.getGateways().firstOrNull()?.deviceId }.getOrNull()
        } else {
            "DRY_RUN_GATEWAY"
        }
        val isDryRunEffective = dryRun || (resolvedGatewayOrNull == null)
        val gatewayId = resolvedGatewayOrNull ?: "DRY_RUN_GATEWAY"
        if (!dryRun && resolvedGatewayOrNull == null) {
            logs.add("[$nowIso] Notice: Could not connect to Bosch gateway (SingleKey ID session expired or offline). Evaluated schedule in preview mode.")
        }

        // Check if user manually modified schedule on the heat pump controller or official Bosch app (skip if forcePush)
        if (!isDryRunEffective && !forcePush && state.appliedScheduleSignatures.isNotEmpty()) {
            val todayDayCode = SwitchProgramScheduler.toBoschDayOfWeek(today.dayOfWeek)
            val expectedSig = state.appliedScheduleSignatures[todayDayCode]
            if (expectedSig != null) {
                var manualDetected = false
                var detectionReason = ""
                runCatching {
                    val hcProgName = pointTClient.getActiveSwitchProgramName(gatewayId, "/heatingCircuits/hc1")
                    val currentHcProg = pointTClient.getSwitchProgram(gatewayId, "/heatingCircuits/hc1/switchPrograms/$hcProgName")
                    val currentSig = scheduler.computeDaySignature(currentHcProg?.switchPoints.orEmpty(), todayDayCode)
                    val hcMode = pointTClient.getResource(gatewayId, "/heatingCircuits/hc1/operationMode")?.value?.toString()?.trim('"', ' ', '\'')

                    if (currentSig.isNotBlank() && currentSig != expectedSig) {
                        manualDetected = true
                        detectionReason = "Switch points on heat pump for $todayDayCode differ ($currentSig vs $expectedSig)"
                    } else if (hcMode != null && !hcMode.equals("auto", ignoreCase = true)) {
                        manualDetected = true
                        detectionReason = "Operation mode on heat pump is '$hcMode' (expected 'auto')"
                    }
                }
                if (manualDetected) {
                    val hint = "manual adjustment detected, no further change"
                    logs.add("[$nowIso] Manual adjustment detected on heat pump ($detectionReason). Don't try to bring it back: $hint")
                    val adjustedState = state.copy(
                        manualAdjustmentDetected = true,
                        manualAdjustmentHint = hint,
                        lastRunLog = logs.joinToString("\n")
                    )
                    stateStore.saveState(adjustedState)
                    return HourlyCheckResult(
                        timestampIso = nowIso,
                        checkedSpotApi = true,
                        tomorrowPricesAvailable = tomorrowComplete,
                        pushedToHeatPump = false,
                        syncedDates = emptyList(),
                        logs = logs
                    )
                }
            }
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

        val syncResult = scheduler.syncSchedulesToHeatPumpDetailed(
            apiClient = pointTClient,
            gatewayId = gatewayId,
            plans = plansToSync,
            dryRun = isDryRunEffective
        )
        logs.addAll(syncResult.logs)

        val syncSucceeded = syncResult.isSuccess

        fun formatPlanSummary(plan: DailySwitchSchedulePlan): String {
            val win = plan.sharedWindows.firstOrNull() ?: return "No window scheduled"
            val sh = win.startMinutes / 60
            val sm = win.startMinutes % 60
            val eh = win.endMinutes / 60
            val em = win.endMinutes % 60
            val hours = plan.plannedOperationMinutes / 60.0
            val budgetNote = if (plan.budgetCutMinutes > 0) " (budget-cut -${plan.budgetCutMinutes}m)" else ""
            val prefTitle = holisticConfig?.preference?.title ?: "Standard Window"

            val spLines = plan.heatingSwitchPoints.joinToString("\n") { sp ->
                val timeHm = String.format("%02d:%02d", sp.time / 60, sp.time % 60)
                val isComfort = sp.setpoint == cfg.heatingOnSetpoint || sp.setpoint.contains("comfort", ignoreCase = true)
                val label = if (isComfort) {
                    "comfort2 @ ${plan.targetHeatingTempC}°C (HEATING ON)  •  DHW low @ 42°C"
                } else {
                    "${sp.setpoint} @ 17.0°C (SETBACK)  •  DHW off"
                }
                "  ► $timeHm  $label"
            }

            val costInfo = if (plan.estimatedDailyTotalCostEuro > plan.estimatedDailyCostEuro + 0.02) {
                String.format(
                    java.util.Locale.US,
                    "Est. %.2f € win (%.2f € 24h)",
                    plan.estimatedDailyCostEuro,
                    plan.estimatedDailyTotalCostEuro
                )
            } else {
                String.format(java.util.Locale.US, "Est. %.2f €", plan.estimatedDailyCostEuro)
            }

            val modInfo = if (plan.estimatedDailyTotalElectricityKwh > plan.estimatedElectricityKwh + 0.05) {
                String.format(
                    java.util.Locale.US,
                    " (%.1f kWh win • %.1f kWh 24h @ ~%.0f%% mod)",
                    plan.estimatedElectricityKwh,
                    plan.estimatedDailyTotalElectricityKwh,
                    plan.estimatedModulationPct
                )
            } else if (plan.estimatedElectricityKwh > 0.0) {
                String.format(java.util.Locale.US, " (%.1f kWh @ ~%.0f%% mod)", plan.estimatedElectricityKwh, plan.estimatedModulationPct)
            } else ""

            return String.format(
                "%02d:%02d – %02d:%02d  (%.1f h%s)  •  %s\nAvg %.1f ct/kWh gross  •  %s%s  •  Target %.1f °C\nTranslated Switch Points:\n%s",
                sh, sm, eh, em, hours, budgetNote, prefTitle,
                win.averageGrossCtPerKwh,
                costInfo,
                modInfo,
                plan.targetHeatingTempC,
                spLines
            )
        }

        val todayPlan = plansToSync.firstOrNull { it.date == todayStr }
        val tomorrowPlan = plansToSync.firstOrNull { it.date == tomorrowStr }

        val updatedSignatures = if (syncSucceeded) {
            state.appliedScheduleSignatures + syncResult.appliedHcSignatures
        } else {
            state.appliedScheduleSignatures
        }

        val appliedNote = if (syncSucceeded) {
            "Applied schedule on $nowIso: " + plansToSync.joinToString(" | ") { p ->
                "${p.date} (${p.dayOfWeekCode}): Target=${p.targetHeatingTempC}°C, Window=${p.sharedWindows.joinToString { it.formatWindow() }}"
            }
        } else {
            state.appliedScheduleNote
        }

        val newState = ScheduleSyncState(
            lastSyncedTodayDate = if (syncSucceeded && todayComplete) todayStr else state.lastSyncedTodayDate,
            lastSyncedTomorrowDate = if (syncSucceeded && (tomorrowPricesReady || forcePush) && tomorrowComplete) tomorrowStr else state.lastSyncedTomorrowDate,
            lastAppliedSliderTempC = if (syncSucceeded) cfg.targetHeatingTempC else state.lastAppliedSliderTempC,
            lastAppliedOperationHours = if (syncSucceeded) cfg.targetOperationHoursPerDay else state.lastAppliedOperationHours,
            lastAppliedBudgetEuro = if (syncSucceeded) cfg.dailyBudgetEuro else state.lastAppliedBudgetEuro,
            lastSyncTimestampIso = nowIso,
            todayWindowSummary = todayPlan?.let { formatPlanSummary(it) } ?: state.todayWindowSummary,
            tomorrowWindowSummary = tomorrowPlan?.let { formatPlanSummary(it) }
                ?: if (state.lastSyncedTomorrowDate == tomorrowStr) state.tomorrowWindowSummary else null,
            todayPhysicsBreakdown = todayPlan?.physicsBreakdown ?: state.todayPhysicsBreakdown,
            tomorrowPhysicsBreakdown = tomorrowPlan?.physicsBreakdown
                ?: if (state.lastSyncedTomorrowDate == tomorrowStr) state.tomorrowPhysicsBreakdown else null,
            lastRunLog = logs.joinToString("\n"),
            appliedScheduleSignatures = updatedSignatures,
            appliedScheduleNote = appliedNote,
            manualAdjustmentDetected = false,
            manualAdjustmentHint = null,
            lastAppliedSettingsSignature = currentSignature,
            appliedPreferenceId = holisticConfig?.preference?.id
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
            val spotClient = SpotPriceClient(httpClient, settingsStore.buildTariffConfig())
            val userSettings = settingsStore
            val telemetryStore = if (userSettings.telemetryCollectionEnabled) {
                SqliteTelemetryStore(applicationContext)
            } else {
                null
            }
            val recentSnap = telemetryStore?.getRecentSnapshots(limit = 1)?.lastOrNull()
            val outdoorTempC = recentSnap?.outdoorTempC
            val scheduler = SwitchProgramScheduler(settingsStore.buildScheduleConfig(averageOutdoorTempC = outdoorTempC))

            val weatherClient = WeatherForecastClient(
                httpClient = httpClient,
                latitude = settingsStore.weatherLatitude,
                longitude = settingsStore.weatherLongitude
            )
            val holisticConfig = if (settingsStore.holisticOptimizationEnabled) {
                settingsStore.buildHolisticOptimizationConfig()
            } else {
                null
            }

            // 1. Proactively validate/refresh SingleKey ID token so we detect credential expiry immediately
            val daemon = AutoScheduleDaemon(
                spotClient = spotClient,
                pointTClient = pointTClient,
                scheduler = scheduler,
                stateStore = stateStore,
                dryRun = false,
                telemetryStore = telemetryStore,
                spotSources = settingsStore.getOrderedSpotSources(),
                weatherClient = weatherClient,
                holisticConfig = holisticConfig,
                settingsStore = settingsStore
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

            BreastNotificationHelper.updateBackgroundActiveNotification(applicationContext)
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
        const val ACTION_TELEMETRY_TICK = "de.breast.ACTION_TELEMETRY_TICK"

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

            val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
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
            val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
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

        fun scheduleNextTelemetryAlarm(context: Context, intervalMinutes: Int = 15) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intervalClamped = intervalMinutes.coerceIn(1, 60)
            val triggerAtMillis = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(intervalClamped.toLong())

            val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
                action = ACTION_TELEMETRY_TICK
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, 421, intent, flags)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        }

        fun cancelTelemetryAlarm(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
                action = ACTION_TELEMETRY_TICK
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, 421, intent, flags)
            am.cancel(pi)
        }
    }
}

/**
 * OS-level BroadcastReceiver that handles device reboot (`BOOT_COMPLETED`)
 * and app update (`MY_PACKAGE_REPLACED`).
 * Exported to Android OS, strictly limited to system boot events.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        BreastNotificationHelper.updateBackgroundActiveNotification(context)

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val settings = UserSettingsStore(context)
                if (settings.autoBackgroundEnabled) {
                    SpotScheduleJobService.scheduleHourlyBackgroundSync(context)
                }
                if (settings.telemetryCollectionEnabled) {
                    SpotScheduleJobService.scheduleNextTelemetryAlarm(context, settings.telemetryIntervalMinutes)
                }
            }
        }
    }
}

/**
 * Internal non-exported BroadcastReceiver that handles System-level `AlarmManager` cron ticks:
 * 1. `ACTION_SYSTEM_CRON_TICK` (Day-ahead spot price fetch & schedule sync)
 * 2. `ACTION_TELEMETRY_TICK` (Periodic thermodynamic telemetry snapshot)
 *
 * Configured as `android:exported="false"` in AndroidManifest to prevent external apps
 * from spoofing alarms or triggering unauthorized background work.
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val settings = UserSettingsStore(context)

        if (action == SpotScheduleJobService.ACTION_TELEMETRY_TICK) {
            if (settings.telemetryCollectionEnabled) {
                SpotScheduleJobService.scheduleNextTelemetryAlarm(context, settings.telemetryIntervalMinutes)
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        val tokenStore = AndroidPrefsTokenStore(context)
                        val httpClient = OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(15, TimeUnit.SECONDS)
                            .build()
                        val authManager = SingleKeyAuthManager(httpClient, tokenStore)
                        val pointTClient = PointTApiClient(httpClient, authManager)
                        val telemetryStore = SqliteTelemetryStore(context)

                        val gw = pointTClient.getGateways().firstOrNull() ?: return@launch
                        val snap = pointTClient.getHeatPumpTelemetry(gatewayId = gw.deviceId)
                        telemetryStore.recordSnapshot(snap)
                    }
                }
            } else {
                SpotScheduleJobService.cancelTelemetryAlarm(context)
            }
            return
        }

        BreastNotificationHelper.updateBackgroundActiveNotification(context)

        if (action == SpotScheduleJobService.ACTION_SYSTEM_CRON_TICK) {
            if (settings.autoBackgroundEnabled) {
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
 * 3. Ongoing background monitoring status badge (telemetry collection & spot sync).
 */
object BreastNotificationHelper {
    const val EXTRA_PROMPT_LOGIN = "de.breast.EXTRA_PROMPT_LOGIN"

    private const val CHANNEL_AUTH_ALERTS = "breast_auth_alerts"
    private const val CHANNEL_SYNC_STATUS = "breast_sync_status"
    private const val CHANNEL_BACKGROUND_STATUS = "breast_telemetry_status_v2"

    private const val NOTIF_ID_AUTH_ALERT = 9001
    private const val NOTIF_ID_SYNC_STATUS = 9002
    private const val NOTIF_ID_BACKGROUND_STATUS = 9003

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Clean up legacy channel so Android doesn't retain old IMPORTANCE_LOW settings
        try {
            nm.deleteNotificationChannel("breast_background_status")
        } catch (_: Exception) {}

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

        val bgChannel = NotificationChannel(
            CHANNEL_BACKGROUND_STATUS,
            "Background Telemetry & Automation Badge",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Ongoing notification badge indicating BREAST telemetry and heat pump automation are actively monitoring in the background"
            setShowBadge(true)
            setSound(null, null)
            enableVibration(false)
        }

        nm.createNotificationChannel(authChannel)
        nm.createNotificationChannel(syncChannel)
        nm.createNotificationChannel(bgChannel)
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

    fun updateBackgroundActiveNotification(context: Context) {
        val settings = UserSettingsStore(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (!settings.autoBackgroundEnabled && !settings.telemetryCollectionEnabled) {
            nm.cancel(NOTIF_ID_BACKGROUND_STATUS)
            return
        }

        ensureChannels(context)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pendingIntent = PendingIntent.getActivity(context, 103, tapIntent, pendingFlags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_BACKGROUND_STATUS)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context).apply {
                setPriority(Notification.PRIORITY_DEFAULT)
            }
        }

        val telemMinutes = settings.telemetryIntervalMinutes
        val telemIntervalStr = if (telemMinutes == 1) "1 min" else if (telemMinutes == 60) "1 hour" else "$telemMinutes min"

        val title: String
        val contentText: String
        val bigText: String

        if (settings.telemetryCollectionEnabled && settings.autoBackgroundEnabled) {
            title = "BREAST • Telemetry & Automation Active"
            contentText = "Telemetry every $telemIntervalStr  •  EPEX Spot active"
            bigText = "BREAST background operations are active.\n" +
                "• Recording heat pump telemetry every $telemIntervalStr (Dev Mode)\n" +
                "• EPEX Spot dynamic schedule sync active (daily 12:00–15:00)\n" +
                "Tap to view heating curve & schedule dashboard."
        } else if (settings.telemetryCollectionEnabled) {
            title = "BREAST • Telemetry & Heating Curve Active"
            contentText = "Recording heat pump telemetry every $telemIntervalStr"
            bigText = "Telemetry collection active in background.\n" +
                "• Recording temperatures every $telemIntervalStr to reconstruct heating curve\n" +
                "Tap to view live points and curve analysis."
        } else {
            title = "BREAST • Daily Sync Active"
            contentText = "EPEX Spot automation active  •  Monitoring spot prices"
            bigText = "BREAST EPEX Spot automation is active in the background.\n" +
                "• Daily sync at 12:00 CET & hourly checks 12:00–15:00\n" +
                "Tap to open dashboard."
        }

        val notification = builder
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(Notification.BigTextStyle().bigText(bigText))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        nm.notify(NOTIF_ID_BACKGROUND_STATUS, notification)
    }

    fun clearBackgroundActiveNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID_BACKGROUND_STATUS)
    }
}
