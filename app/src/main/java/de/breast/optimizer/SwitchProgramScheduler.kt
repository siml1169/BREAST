package de.breast.optimizer

import de.breast.api.PointTApiClient
import de.breast.api.SwitchPoint
import de.breast.spot.SpotPriceClient
import de.breast.spot.SpotPriceSlot
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.roundToInt

@Serializable
data class HeatingTemperatureSlider(
    val minTempC: Double = 15.0,
    val maxTempC: Double = 30.0,
    val stepSizeC: Double = 0.5,
    val currentValueC: Double = 21.5
) {
    val snappedTempC: Double
        get() = snap(currentValueC)

    fun snap(rawTempC: Double): Double {
        val clamped = rawTempC.coerceIn(minTempC, maxTempC)
        val steps = ((clamped - minTempC) / stepSizeC).roundToInt()
        return minTempC + (steps * stepSizeC)
    }

    fun withValue(newTempC: Double): HeatingTemperatureSlider =
        copy(currentValueC = snap(newTempC))

    val totalSteps: Int
        get() = ((maxTempC - minTempC) / stepSizeC).roundToInt()

    fun stepIndexFor(tempC: Double = snappedTempC): Int =
        ((snap(tempC) - minTempC) / stepSizeC).roundToInt().coerceIn(0, totalSteps)

    fun tempForStepIndex(stepIndex: Int): Double =
        snap(minTempC + stepIndex * stepSizeC)
}

@Serializable
data class OperationDurationSlider(
    val minHours: Double = 0.5,
    val maxHours: Double = 16.0,
    val stepHours: Double = 0.5,
    val currentHours: Double = 6.0
) {
    val snappedHours: Double
        get() = snap(currentHours)

    val snappedMinutes: Int
        get() = (snappedHours * 60.0).roundToInt()

    fun snap(rawHours: Double): Double {
        val clamped = rawHours.coerceIn(minHours, maxHours)
        val steps = ((clamped - minHours) / stepHours).roundToInt()
        return minHours + (steps * stepHours)
    }

    fun withHours(newHours: Double): OperationDurationSlider =
        copy(currentHours = snap(newHours))

    val totalSteps: Int
        get() = ((maxHours - minHours) / stepHours).roundToInt()

    fun stepIndexFor(hours: Double = snappedHours): Int =
        ((snap(hours) - minHours) / stepHours).roundToInt().coerceIn(0, totalSteps)

    fun hoursForStepIndex(stepIndex: Int): Double =
        snap(minHours + stepIndex * stepHours)
}

@Serializable
data class TimeWindow(
    val date: String,
    val dayOfWeekCode: String,
    val startMinutes: Int,
    val endMinutes: Int,
    val setpoint: String,
    val averageGrossCtPerKwh: Double,
    val averageSpotNetCtPerKwh: Double,
    val estimatedWindowCostEuro: Double = 0.0
) {
    val durationMinutes: Int get() = endMinutes - startMinutes

    fun formatWindow(): String {
        val sh = startMinutes / 60
        val sm = startMinutes % 60
        val eh = endMinutes / 60
        val em = endMinutes % 60
        return String.format(
            "%s (%s) %02d:%02d - %02d:%02d (%3d min) | Avg Spot: %5.2f ct/kWh (Gross: %5.2f ct/kWh) | Est. Cost: %.2f EUR",
            date,
            dayOfWeekCode,
            sh,
            sm,
            eh,
            em,
            durationMinutes,
            averageSpotNetCtPerKwh,
            averageGrossCtPerKwh,
            estimatedWindowCostEuro
        )
    }
}

@Serializable
data class DailySwitchSchedulePlan(
    val date: String,
    val dayOfWeekCode: String,
    val sharedWindows: List<TimeWindow>,
    val plannedOperationMinutes: Int,
    val budgetCutMinutes: Int,
    val estimatedDailyCostEuro: Double,
    val dailyBudgetEuro: Double?,
    val targetHeatingTempC: Double,
    val heatingWindows: List<TimeWindow>,
    val heatingSwitchPoints: List<SwitchPoint>,
    val dhwWindows: List<TimeWindow>,
    val dhwSwitchPoints: List<SwitchPoint>
)

@Serializable
data class SwitchScheduleConfig(
    val windowCount: Int = 1,
    val targetOperationHoursPerDay: Double = 6.0,
    val minOperationHoursPerDay: Double = 1.5,
    val minWindowMinutes: Int = 45,
    val minGapBetweenWindowsMinutes: Int = 180,
    val temperatureSlider: HeatingTemperatureSlider = HeatingTemperatureSlider(currentValueC = 21.5),
    val inactiveOffTempC: Double = 15.0,
    val dailyBudgetEuro: Double? = null,
    val estimatedPowerKw: Double = 2.0,
    val maxSwitchPointsPerDay: Int = 2,
    val timeRasterMinutes: Int = 15,
    val heatingOnSetpoint: String = "comfort2",
    val heatingOffSetpoint: String = "eco",
    val dhwOnSetpoint: String = "low",
    val dhwOffSetpoint: String = "off"
) {
    val targetHeatingTempC: Double get() = temperatureSlider.snappedTempC
}

class SwitchProgramScheduler(
    val config: SwitchScheduleConfig = SwitchScheduleConfig()
) {
    companion object {
        fun toBoschDayOfWeek(dayOfWeek: DayOfWeek): String = when (dayOfWeek) {
            DayOfWeek.MONDAY -> "Mo"
            DayOfWeek.TUESDAY -> "Tu"
            DayOfWeek.WEDNESDAY -> "We"
            DayOfWeek.THURSDAY -> "Th"
            DayOfWeek.FRIDAY -> "Fr"
            DayOfWeek.SATURDAY -> "Sa"
            DayOfWeek.SUNDAY -> "Su"
        }

        private val DAY_ORDER = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
    }

    private data class RasterSlot(
        val index: Int,
        val startMinutes: Int,
        val endMinutes: Int,
        val spotNetCt: Double,
        val grossCt: Double,
        val slotCostEuro: Double
    )

    fun hasCompleteDayPrices(date: LocalDate, spotSlots: List<SpotPriceSlot>): Boolean {
        val dayStartEpoch = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val dayEndEpoch = date.plusDays(1).atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond

        val coveredSeconds = spotSlots.sumOf { slot ->
            val overlapStart = maxOf(slot.startEpochSeconds, dayStartEpoch)
            val overlapEnd = minOf(slot.endEpochSeconds, dayEndEpoch)
            (overlapEnd - overlapStart).coerceAtLeast(0L)
        }
        return coveredSeconds >= 23L * 3600L
    }

    suspend fun resolveActiveHeatingOnSetpoint(
        apiClient: PointTApiClient,
        gatewayId: String,
        hcId: String = "hc1"
    ): String {
        val hcProgName = apiClient.getActiveSwitchProgramName(gatewayId, "/heatingCircuits/$hcId")
        val existingHcProg = apiClient.getSwitchProgram(gatewayId, "/heatingCircuits/$hcId/switchPrograms/$hcProgName")
        val (detectedOn, _) = detectOnAndOffSetpoints(
            existingPoints = existingHcProg?.switchPoints.orEmpty(),
            defaultOn = config.heatingOnSetpoint,
            defaultOff = config.heatingOffSetpoint
        )
        return detectedOn
    }

    suspend fun fetchHardwareTemperatureSlider(
        apiClient: PointTApiClient,
        gatewayId: String,
        hcId: String = "hc1"
    ): HeatingTemperatureSlider {
        val onLevel = resolveActiveHeatingOnSetpoint(apiClient, gatewayId, hcId)
        val node = apiClient.getResource(gatewayId, "/heatingCircuits/$hcId/temperatureLevels/$onLevel")
            ?: apiClient.getResource(gatewayId, "/heatingCircuits/$hcId/temperatureLevels/${config.heatingOnSetpoint}")
            ?: apiClient.getResource(gatewayId, "/heatingCircuits/$hcId/manualRoomSetpoint")
        val minVal = node?.minValue ?: 15.0
        val maxVal = node?.maxValue ?: 30.0
        val stepVal = node?.stepSize ?: 0.5
        val currVal = node?.value?.toString()?.toDoubleOrNull() ?: config.targetHeatingTempC
        return HeatingTemperatureSlider(
            minTempC = minVal,
            maxTempC = maxVal,
            stepSizeC = stepVal,
            currentValueC = currVal
        )
    }

    suspend fun applyTemperatureSliderValue(
        apiClient: PointTApiClient,
        gatewayId: String,
        sliderTempC: Double,
        hcId: String = "hc1"
    ): Double {
        val snapped = config.temperatureSlider.snap(sliderTempC)
        val onLevel = resolveActiveHeatingOnSetpoint(apiClient, gatewayId, hcId)
        apiClient.setHeatingTemperatureLevel(
            gatewayId = gatewayId,
            hcId = hcId,
            levelName = onLevel,
            temperatureCelsius = snapped
        )
        return snapped
    }

    fun buildDailySwitchSchedule(
        date: LocalDate,
        spotSlots: List<SpotPriceSlot>
    ): DailySwitchSchedulePlan? {
        val rasterSlots = buildDayRasterSlots(date, spotSlots)
        if (rasterSlots.isEmpty()) return null

        val dayCode = toBoschDayOfWeek(date.dayOfWeek)
        val dateStr = date.toString()
        val targetMaxMinutes = (config.targetOperationHoursPerDay * 60.0).roundToInt()

        val sharedWindows = findOptimalSharedWindowsWithBudget(dateStr, dayCode, rasterSlots)
        val plannedMinutes = sharedWindows.sumOf { it.durationMinutes }
        val budgetCutMinutes = (targetMaxMinutes - plannedMinutes).coerceAtLeast(0)
        val totalCostEuro = sharedWindows.sumOf { it.estimatedWindowCostEuro }

        val heatingWindows = sharedWindows.map { it.copy(setpoint = config.heatingOnSetpoint) }
        val dhwWindows = sharedWindows.map { it.copy(setpoint = config.dhwOnSetpoint) }

        val heatingSwitchPoints = windowsToSwitchPoints(
            dayOfWeekCode = dayCode,
            windows = heatingWindows,
            onSetpoint = config.heatingOnSetpoint,
            offSetpoint = config.heatingOffSetpoint,
            maxSwitchPoints = config.maxSwitchPointsPerDay
        )

        val dhwSwitchPoints = windowsToSwitchPoints(
            dayOfWeekCode = dayCode,
            windows = dhwWindows,
            onSetpoint = config.dhwOnSetpoint,
            offSetpoint = config.dhwOffSetpoint,
            maxSwitchPoints = config.maxSwitchPointsPerDay
        )

        return DailySwitchSchedulePlan(
            date = dateStr,
            dayOfWeekCode = dayCode,
            sharedWindows = sharedWindows,
            plannedOperationMinutes = plannedMinutes,
            budgetCutMinutes = budgetCutMinutes,
            estimatedDailyCostEuro = totalCostEuro,
            dailyBudgetEuro = config.dailyBudgetEuro,
            targetHeatingTempC = config.targetHeatingTempC,
            heatingWindows = heatingWindows,
            heatingSwitchPoints = heatingSwitchPoints,
            dhwWindows = dhwWindows,
            dhwSwitchPoints = dhwSwitchPoints
        )
    }

    private fun buildDayRasterSlots(
        date: LocalDate,
        spotSlots: List<SpotPriceSlot>
    ): List<RasterSlot> {
        val dayStartEpoch = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val dayEndEpoch = date.plusDays(1).atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond

        val relevant = spotSlots.filter { it.endEpochSeconds > dayStartEpoch && it.startEpochSeconds < dayEndEpoch }
        if (relevant.isEmpty()) return emptyList()

        val raster = config.timeRasterMinutes.coerceAtLeast(15)
        val slotHours = raster / 60.0
        val numSlots = 1440 / raster
        val result = mutableListOf<RasterSlot>()

        for (i in 0 until numSlots) {
            val startMin = i * raster
            val endMin = (i + 1) * raster
            val slotMidEpoch = dayStartEpoch + startMin * 60L + (raster * 30L)
            val matching = relevant.firstOrNull { slotMidEpoch in it.startEpochSeconds until it.endEpochSeconds }
                ?: relevant.minByOrNull { kotlin.math.abs(it.startEpochSeconds - slotMidEpoch) }
                ?: continue

            val slotEnergyKwh = config.estimatedPowerKw * slotHours
            val slotCostEuro = (matching.totalGrossCtPerKwh / 100.0) * slotEnergyKwh

            result.add(
                RasterSlot(
                    index = i,
                    startMinutes = startMin,
                    endMinutes = endMin,
                    spotNetCt = matching.spotNetCtPerKwh,
                    grossCt = matching.totalGrossCtPerKwh,
                    slotCostEuro = slotCostEuro
                )
            )
        }
        return result
    }

    private fun findOptimalSharedWindowsWithBudget(
        dateStr: String,
        dayCode: String,
        rasterSlots: List<RasterSlot>
    ): List<TimeWindow> {
        val raster = config.timeRasterMinutes.coerceAtLeast(15)
        val n = rasterSlots.size
        // Reserve the final 23:45 switchpoint slot for turning OFF so the next day never inherits ON across midnight
        val usableN = (n - 1).coerceAtLeast(1)

        val numWindows = config.windowCount.coerceIn(1, (config.maxSwitchPointsPerDay / 2).coerceAtLeast(1))
        val maxOnSlots = ((config.targetOperationHoursPerDay * 60.0) / raster).roundToInt()
            .coerceIn(numWindows, usableN)
        val minOnSlots = ((config.minOperationHoursPerDay * 60.0) / raster).roundToInt()
            .coerceIn(numWindows, maxOnSlots)
        val minWindowSlots = (config.minWindowMinutes / raster).coerceIn(1, maxOnSlots / numWindows)
        val minGapSlots = (config.minGapBetweenWindowsMinutes / raster).coerceAtLeast(1)

        val prefixGross = DoubleArray(n + 1)
        val prefixNet = DoubleArray(n + 1)
        val prefixEuro = DoubleArray(n + 1)
        for (i in 0 until n) {
            prefixGross[i + 1] = prefixGross[i] + rasterSlots[i].grossCt
            prefixNet[i + 1] = prefixNet[i] + rasterSlots[i].spotNetCt
            prefixEuro[i + 1] = prefixEuro[i] + rasterSlots[i].slotCostEuro
        }

        val budget = config.dailyBudgetEuro
        val inf = 1e12

        // Fast & exact sliding-window search for a single contiguous lowest-price window (2 switch points/day: 1 ON + 1 OFF)
        if (numWindows == 1) {
            var chosenLen = minOnSlots
            var chosenStartIdx = 0

            for (on in maxOnSlots downTo minOnSlots) {
                var bestStart = 0
                var bestEuro = inf
                for (startIdx in 0..(usableN - on)) {
                    val endIdx = startIdx + on
                    val winEuro = prefixEuro[endIdx] - prefixEuro[startIdx]
                    if (winEuro < bestEuro) {
                        bestEuro = winEuro
                        bestStart = startIdx
                    }
                }
                chosenLen = on
                chosenStartIdx = bestStart
                if (budget == null || bestEuro <= budget + 1e-9) {
                    break
                }
            }

            val endIdx = chosenStartIdx + chosenLen
            val avgGross = (prefixGross[endIdx] - prefixGross[chosenStartIdx]) / chosenLen
            val avgNet = (prefixNet[endIdx] - prefixNet[chosenStartIdx]) / chosenLen
            val winEuro = prefixEuro[endIdx] - prefixEuro[chosenStartIdx]

            return listOf(
                TimeWindow(
                    date = dateStr,
                    dayOfWeekCode = dayCode,
                    startMinutes = rasterSlots[chosenStartIdx].startMinutes,
                    endMinutes = rasterSlots[endIdx - 1].endMinutes,
                    setpoint = config.heatingOnSetpoint,
                    averageGrossCtPerKwh = avgGross,
                    averageSpotNetCtPerKwh = avgNet,
                    estimatedWindowCostEuro = winEuro
                )
            )
        }

        val dp = Array(numWindows + 1) { Array(usableN + 1) { DoubleArray(maxOnSlots + 1) { inf } } }
        val back = Array(numWindows + 1) { Array(usableN + 1) { IntArray(maxOnSlots + 1) { -1 } } }

        for (i in 0..usableN) {
            dp[0][i][0] = 0.0
        }

        for (w in 1..numWindows) {
            for (i in 1..usableN) {
                for (on in 0..maxOnSlots) {
                    if (dp[w][i - 1][on] < dp[w][i][on]) {
                        dp[w][i][on] = dp[w][i - 1][on]
                        back[w][i][on] = -1
                    }

                    val maxLen = minOf(i, on)
                    for (len in minWindowSlots..maxLen) {
                        val startIdx = i - len
                        val prevEnd = if (w == 1) startIdx else (startIdx - minGapSlots)
                        if (prevEnd < 0) continue

                        val prevCost = dp[w - 1][prevEnd][on - len]
                        if (prevCost >= inf) continue

                        val windowEuro = prefixEuro[i] - prefixEuro[startIdx]
                        val candidateEuro = prevCost + windowEuro
                        if (candidateEuro < dp[w][i][on]) {
                            dp[w][i][on] = candidateEuro
                            back[w][i][on] = startIdx
                        }
                    }
                }
            }
        }

        var chosenOnSlots = maxOnSlots
        if (budget != null) {
            var foundWithinBudget = false
            for (on in maxOnSlots downTo minOnSlots) {
                val costEuro = dp[numWindows][usableN][on]
                if (costEuro <= budget + 1e-9) {
                    chosenOnSlots = on
                    foundWithinBudget = true
                    break
                }
            }
            if (!foundWithinBudget) {
                chosenOnSlots = minOnSlots
            }
        }

        var currW = numWindows
        while (currW > 1 && dp[currW][usableN][chosenOnSlots] >= inf) {
            currW -= 1
        }
        if (dp[currW][usableN][chosenOnSlots] >= inf) {
            return emptyList()
        }

        val windows = mutableListOf<TimeWindow>()
        var currI = usableN
        var currOn = chosenOnSlots

        while (currW > 0 && currI > 0 && currOn > 0) {
            val b = back[currW][currI][currOn]
            if (b == -1) {
                currI -= 1
            } else if (b >= 0) {
                val startIdx = b
                val endIdx = currI
                val len = endIdx - startIdx
                val avgGross = (prefixGross[endIdx] - prefixGross[startIdx]) / len
                val avgNet = (prefixNet[endIdx] - prefixNet[startIdx]) / len
                val winEuro = prefixEuro[endIdx] - prefixEuro[startIdx]

                windows.add(
                    TimeWindow(
                        date = dateStr,
                        dayOfWeekCode = dayCode,
                        startMinutes = rasterSlots[startIdx].startMinutes,
                        endMinutes = rasterSlots[endIdx - 1].endMinutes,
                        setpoint = config.heatingOnSetpoint,
                        averageGrossCtPerKwh = avgGross,
                        averageSpotNetCtPerKwh = avgNet,
                        estimatedWindowCostEuro = winEuro
                    )
                )
                currOn -= len
                currI = if (currW == 1) startIdx else (startIdx - minGapSlots)
                currW -= 1
            } else {
                break
            }
        }

        return windows.sortedBy { it.startMinutes }
    }

    internal fun windowsToSwitchPoints(
        dayOfWeekCode: String,
        windows: List<TimeWindow>,
        onSetpoint: String,
        offSetpoint: String,
        maxSwitchPoints: Int,
        rasterOverrideMinutes: Int? = null,
        currentMinuteOfDay: Int? = null
    ): List<SwitchPoint> {
        val raster = (rasterOverrideMinutes ?: config.timeRasterMinutes).coerceAtLeast(15)
        val maxTime = 1440 - raster
        val points = mutableListOf<SwitchPoint>()
        val maxWindows = (maxSwitchPoints / 2).coerceAtLeast(1)

        val selectedWindows = if (windows.size > maxWindows && currentMinuteOfDay != null) {
            windows.filter { it.endMinutes > currentMinuteOfDay }
                .take(maxWindows)
                .ifEmpty { windows.takeLast(maxWindows) }
        } else {
            windows.take(maxWindows)
        }

        for (win in selectedWindows) {
            val startMin = ((win.startMinutes / raster) * raster).coerceIn(0, maxTime - raster)
            val endMin = (((win.endMinutes + raster - 1) / raster) * raster).coerceIn(startMin + raster, maxTime)
            points.add(SwitchPoint(dayOfWeek = dayOfWeekCode, setpoint = onSetpoint, time = startMin))
            points.add(SwitchPoint(dayOfWeek = dayOfWeekCode, setpoint = offSetpoint, time = endMin))
        }

        return points.sortedBy { it.time }
    }

    internal fun detectOnAndOffSetpoints(
        existingPoints: List<SwitchPoint>,
        defaultOn: String,
        defaultOff: String
    ): Pair<String, String> {
        val distinct = existingPoints.map { it.setpoint }.distinct()
        if (distinct.isEmpty()) return defaultOn to defaultOff
        // Priority order ensures "off" is chosen before "low" on DHW circuits where "low" (42°C) is the ON setpoint
        val offPriority = listOf("off", "eco", "setback", "night", "low")
        val detectedOff = offPriority.firstNotNullOfOrNull { candidate ->
            distinct.firstOrNull { it.equals(candidate, ignoreCase = true) }
        }
            ?: existingPoints.maxByOrNull { it.time }?.setpoint
            ?: defaultOff
        val detectedOn = distinct.firstOrNull { it != detectedOff }
            ?: existingPoints.minByOrNull { it.time }?.setpoint
            ?: defaultOn
        return detectedOn to detectedOff
    }

    fun mergeWithExistingWeekSchedule(
        existingSwitchPoints: List<SwitchPoint>,
        updatedDays: Map<String, List<SwitchPoint>>
    ): List<SwitchPoint> {
        val kept = existingSwitchPoints.filter { it.dayOfWeek !in updatedDays.keys }
        val combined = kept + updatedDays.values.flatten()
        return combined.sortedWith(
            compareBy<SwitchPoint>(
                { DAY_ORDER.indexOf(it.dayOfWeek).let { idx -> if (idx == -1) 99 else idx } },
                { it.time }
            )
        )
    }

    suspend fun syncSchedulesToHeatPump(
        apiClient: PointTApiClient,
        gatewayId: String,
        plans: List<DailySwitchSchedulePlan>,
        hcId: String = "hc1",
        dhwId: String = "dhw1",
        dryRun: Boolean = true,
        currentMinuteOfDay: Int? = null
    ): List<String> {
        val logs = mutableListOf<String>()
        val prefix = if (dryRun) "[DRY-RUN]" else "[LIVE-SYNC]"
        val sliderTemp = config.targetHeatingTempC

        if (dryRun) {
            val hcUpdatesByDay = plans.associate { it.dayOfWeekCode to it.heatingSwitchPoints }
            logs.add(
                "$prefix Would set Heating Target Temperature Slider on /heatingCircuits/$hcId: " +
                    "${sliderTemp}°C (range ${config.temperatureSlider.minTempC}..${config.temperatureSlider.maxTempC}°C, step ${config.temperatureSlider.stepSizeC}°C)"
            )
            logs.add("$prefix Would update /heatingCircuits/$hcId/switchPrograms/A and /dhwCircuits/$dhwId/switchPrograms/A for days ${hcUpdatesByDay.keys}:")
            plans.forEach { plan ->
                plan.heatingSwitchPoints.zip(plan.dhwSwitchPoints).forEach { (hcSp, dhwSp) ->
                    val stateLabel = if (hcSp.setpoint == config.heatingOnSetpoint) "ON (${sliderTemp}°C)" else "OFF"
                    logs.add("   ${hcSp.dayOfWeek} ${hcSp.formatTimeHm()} (t=${hcSp.time}) -> Heating=$stateLabel, WarmWater=${dhwSp.setpoint}")
                }
            }
            return logs
        }

        // 1. Read existing HC1 and DHW1 switchPrograms first so we match the hardware's exact setpoint names & raster
        val hcProgName = apiClient.getActiveSwitchProgramName(gatewayId, "/heatingCircuits/$hcId")
        val hcProgPath = "/heatingCircuits/$hcId/switchPrograms/$hcProgName"
        val existingHcProg = apiClient.getSwitchProgram(gatewayId, hcProgPath)
        val (hcOnSetpoint, hcOffSetpoint) = detectOnAndOffSetpoints(
            existingPoints = existingHcProg?.switchPoints.orEmpty(),
            defaultOn = config.heatingOnSetpoint,
            defaultOff = config.heatingOffSetpoint
        )
        val hcRaster = existingHcProg?.switchPointTimeRaster ?: config.timeRasterMinutes
        val hcMaxPerDay = minOf(
            existingHcProg?.maxNbOfSwitchPointsPerDay ?: config.maxSwitchPointsPerDay,
            config.maxSwitchPointsPerDay
        )

        val dhwProgName = apiClient.getActiveSwitchProgramName(gatewayId, "/dhwCircuits/$dhwId")
        val dhwProgPath = "/dhwCircuits/$dhwId/switchPrograms/$dhwProgName"
        val existingDhwProg = apiClient.getSwitchProgram(gatewayId, dhwProgPath)
        val (dhwOnSetpoint, dhwOffSetpoint) = detectOnAndOffSetpoints(
            existingPoints = existingDhwProg?.switchPoints.orEmpty(),
            defaultOn = config.dhwOnSetpoint,
            defaultOff = config.dhwOffSetpoint
        )
        val dhwRaster = existingDhwProg?.switchPointTimeRaster ?: config.timeRasterMinutes
        val dhwMaxPerDay = minOf(
            existingDhwProg?.maxNbOfSwitchPointsPerDay ?: config.maxSwitchPointsPerDay,
            config.maxSwitchPointsPerDay
        )

        // 2. Update ONLY the Heating ON target temperature level (never overwrite setback/eco to 15°C!)
        val prevComfortNode = apiClient.getResource(gatewayId, "/heatingCircuits/$hcId/temperatureLevels/$hcOnSetpoint")
        val prevComfortVal = prevComfortNode?.value?.toString() ?: "?"
        val comfortTempOk = apiClient.setHeatingTemperatureLevel(
            gatewayId = gatewayId,
            hcId = hcId,
            levelName = hcOnSetpoint,
            temperatureCelsius = sliderTemp
        )

        // If setback (eco) was dialed down to 15.0°C by an earlier run, restore it to 17.0°C and leave setback untouched afterwards
        val ecoNode = apiClient.getResource(gatewayId, "/heatingCircuits/$hcId/temperatureLevels/$hcOffSetpoint")
        val currentEcoC = ecoNode?.value?.toString()?.toDoubleOrNull()
        val setbackStatus = if (currentEcoC != null && currentEcoC <= 15.0) {
            val restored = apiClient.setHeatingTemperatureLevel(
                gatewayId = gatewayId,
                hcId = hcId,
                levelName = hcOffSetpoint,
                temperatureCelsius = 17.0
            )
            "restored setback $hcOffSetpoint: 15.0°C -> 17.0°C (ok=$restored)"
        } else {
            "setback $hcOffSetpoint=${currentEcoC ?: "?"}°C (untouched)"
        }

        logs.add(
            "$prefix PUT /heatingCircuits/$hcId/temperatureLevels/$hcOnSetpoint: $prevComfortVal°C -> ${sliderTemp}°C (ok=$comfortTempOk) | $setbackStatus"
        )

        // 3. Build switchPoints using hardware-detected setpoint names, time raster, and per-day switch point cap
        val hcUpdatesByDay = plans.mapIndexed { idx, plan ->
            plan.dayOfWeekCode to windowsToSwitchPoints(
                dayOfWeekCode = plan.dayOfWeekCode,
                windows = plan.sharedWindows,
                onSetpoint = hcOnSetpoint,
                offSetpoint = hcOffSetpoint,
                maxSwitchPoints = hcMaxPerDay,
                rasterOverrideMinutes = hcRaster,
                currentMinuteOfDay = if (idx == 0) currentMinuteOfDay else null
            )
        }.toMap()
        val dhwUpdatesByDay = plans.mapIndexed { idx, plan ->
            plan.dayOfWeekCode to windowsToSwitchPoints(
                dayOfWeekCode = plan.dayOfWeekCode,
                windows = plan.sharedWindows,
                onSetpoint = dhwOnSetpoint,
                offSetpoint = dhwOffSetpoint,
                maxSwitchPoints = dhwMaxPerDay,
                rasterOverrideMinutes = dhwRaster,
                currentMinuteOfDay = if (idx == 0) currentMinuteOfDay else null
            )
        }.toMap()

        val mergedHcPoints = mergeWithExistingWeekSchedule(
            existingSwitchPoints = existingHcProg?.switchPoints.orEmpty(),
            updatedDays = hcUpdatesByDay
        )
        val hcPutRes = apiClient.putSwitchProgramDetailed(
            gatewayId = gatewayId,
            programResourcePath = hcProgPath,
            switchPoints = mergedHcPoints,
            existingProgram = existingHcProg
        )
        val hcAuto = apiClient.setHeatingOperationMode(gatewayId, hcId, "auto")
        logs.add(
            "$prefix PUT $hcProgPath [setpoints=$hcOnSetpoint/$hcOffSetpoint, raster=${hcRaster}m, pts=${mergedHcPoints.size}] -> " +
                "ok=${hcPutRes.ok} (HTTP ${hcPutRes.httpCode}, fmt=${hcPutRes.usedFormat}), mode=auto($hcAuto)" +
                if (!hcPutRes.ok && hcPutRes.responseBody.isNotBlank()) "\n   Err: ${hcPutRes.responseBody}" else ""
        )

        val mergedDhwPoints = mergeWithExistingWeekSchedule(
            existingSwitchPoints = existingDhwProg?.switchPoints.orEmpty(),
            updatedDays = dhwUpdatesByDay
        )
        val dhwPutRes = apiClient.putSwitchProgramDetailed(
            gatewayId = gatewayId,
            programResourcePath = dhwProgPath,
            switchPoints = mergedDhwPoints,
            existingProgram = existingDhwProg
        )
        val dhwOwnProg = apiClient.setDhwOperationMode(gatewayId, dhwId, "ownprogram")
        logs.add(
            "$prefix PUT $dhwProgPath [setpoints=$dhwOnSetpoint/$dhwOffSetpoint, raster=${dhwRaster}m, pts=${mergedDhwPoints.size}] -> " +
                "ok=${dhwPutRes.ok} (HTTP ${dhwPutRes.httpCode}, fmt=${dhwPutRes.usedFormat}), mode=ownprogram($dhwOwnProg)" +
                if (!dhwPutRes.ok && dhwPutRes.responseBody.isNotBlank()) "\n   Err: ${dhwPutRes.responseBody}" else ""
        )

        return logs
    }
}
