package de.breast.optimizer

import de.breast.api.SwitchPoint
import de.breast.spot.SpotPriceClient
import de.breast.spot.SpotPriceSlot
import de.breast.weather.HourlyWeatherForecast
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

enum class OptimizationPreference(
    val id: String,
    val title: String,
    val subtitle: String,
    val weightComfort: Double,
    val weightBudget: Double,
    val weightEfficiency: Double,
    val minExpansionRatio: Double,
    val maxExpansionRatio: Double,
    val maxOffHours: Double
) {
    COMFORT(
        id = "comfort",
        title = "Comfort Priority",
        subtitle = "60% Comfort, 20% Budget, 20% Efficiency — Up to +20% runtime to preserve floor warmth",
        weightComfort = 0.60,
        weightBudget = 0.20,
        weightEfficiency = 0.20,
        minExpansionRatio = 0.0,
        maxExpansionRatio = 0.20,
        maxOffHours = 8.0
    ),
    BUDGET(
        id = "budget",
        title = "Budget Priority",
        subtitle = "Strict runtime adherence (+0% extra) — Minimizes cost at lowest spot price window",
        weightComfort = 0.15,
        weightBudget = 0.70,
        weightEfficiency = 0.15,
        minExpansionRatio = 0.0,
        maxExpansionRatio = 0.00,
        maxOffHours = 12.0
    ),
    EFFICIENCY(
        id = "efficiency",
        title = "Efficiency Priority",
        subtitle = "Maximum bang for buck (COP/Price) — Expands up to +35% during cheap/warm solar hours",
        weightComfort = 0.15,
        weightBudget = 0.35,
        weightEfficiency = 0.50,
        minExpansionRatio = 0.0,
        maxExpansionRatio = 0.35,
        maxOffHours = 9.0
    ),
    BALANCED(
        id = "balanced",
        title = "Balanced",
        subtitle = "33% Comfort, 33% Budget, 33% Efficiency — Up to +25% runtime for midday solar alignment",
        weightComfort = 0.334,
        weightBudget = 0.333,
        weightEfficiency = 0.333,
        minExpansionRatio = 0.0,
        maxExpansionRatio = 0.25,
        maxOffHours = 9.0
    );

    /**
     * Calculates the extra runtime allowance dynamically as a function of the set runtime,
     * harmonized to the scheduler's discrete time quantizer (e.g. 0.5h slider step or 0.25h slot raster).
     */
    fun calculateHarmonizedExtraHours(targetHours: Double, quantizerHours: Double = 0.5): Double {
        if (maxExpansionRatio <= 0.0 || targetHours <= 0.0 || quantizerHours <= 0.0) return 0.0
        val rawExtra = targetHours * maxExpansionRatio
        val steps = (rawExtra / quantizerHours).roundToInt()
        return (steps * quantizerHours).coerceAtLeast(quantizerHours)
    }

    val minDeltaHours: Double get() = 0.0
    val maxDeltaHours: Double get() = calculateHarmonizedExtraHours(8.0, 0.5)

    companion object {
        fun fromId(id: String?): OptimizationPreference {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: BALANCED
        }
    }
}

data class HolisticOptimizationConfig(
    val targetHeatingTempC: Double = 21.5,
    val targetDailyRuntimeHours: Double = 6.0,
    val estimatedPowerKw: Double = 0.7,
    val buildingLossKwPerK: Double = 0.150,
    val heatPumpNominalThermalKw: Double = 4.5,
    val heatPumpMinThermalKw: Double = 1.8,
    val heatPumpMaxThermalKw: Double = 9.0,
    val circulationPumpKw: Double = 0.04,
    val parasiticBaselinePowerKw: Double = 0.050, // 50W continuous baseline rule of thumb (circulation pump + electronics)
    val customMeasuredParasiticLoadKw: Double? = null,
    val minElecPowerKw: Double = 0.45,
    val maxElecPowerKw: Double = 2.40,
    val autoPowerModulationEnabled: Boolean = true,
    val useEmpiricalGroundTruthModulation: Boolean = true,
    val calibratedModulationAt10C: Double = 42.0,
    val calibratedModulationSlopePerK: Double = 5.0,
    val floorScreedCapacityKwhPerK: Double = 5.0,
    val indoorThermalMassKwhPerK: Double = 8.5,
    val maxThermalBufferingK: Double = 1.2,
    val baselineInternalGainsKw: Double = 0.35, // 350W domestic internal & solar gains
    val minContinuousRunMinutes: Int = 90, // Amortizes compressor startup ramp
    val maxSwitchPointsPerDay: Int = 2,
    val timeRasterMinutes: Int = 15,
    val durationQuantizerHours: Double = 0.5, // Harmonizes extra duration to discrete scheduler steps (default 0.5h = 30 min)
    val heatingOnSetpoint: String = "comfort2",
    val heatingOffSetpoint: String = "eco",
    val dhwOnSetpoint: String = "low",
    val dhwOffSetpoint: String = "off",
    val dailyBudgetEuro: Double? = null,
    val zeroPriceThresholdNetCtPerKwh: Double = 0.5,
    val preference: OptimizationPreference = OptimizationPreference.BALANCED,
    val storageTransmissionEfficiency: Double = 0.88,
    val grossBaseFloorCtPerKwh: Double = 25.40,
    val coolingRateKPerHour: Double = 0.20,
    val liveStartingReturnTempC: Double? = null,
    val adaptiveCycleCount: Int = 0
)

data class ScoredRasterSlot(
    val index: Int,
    val startMinutes: Int,
    val endMinutes: Int,
    val outdoorTempC: Double,
    val supplyTempC: Double,
    val cop: Double,
    val spotNetCtPerKwh: Double,
    val totalGrossCtPerKwh: Double,
    val deliveredHeatCostCtPerKwh: Double,
    val effectiveCompositeScore: Double,
    val slotCostEuro: Double
)

private data class CandidateWindow(
    val startIdx: Int,
    val length: Int,
    val durationHours: Double,
    val deltaHours: Double,
    val euroCost: Double,
    val electricityKwh: Double,
    val avgPowerKw: Double,
    val modulationPct: Double,
    val rawBudget: Double,
    val rawEfficiency: Double,
    val rawComfort: Double,
    var score: Double = 0.0
)

private data class WindowEvalResult(
    val windowCostEuro: Double,
    val dailyCostEuro: Double,
    val windowElectricityKwh: Double,
    val dailyElectricityKwh: Double,
    val avgPowerKw: Double,
    val modulationPct: Double
) {
    val euroCost: Double get() = dailyCostEuro
    val electricityKwh: Double get() = windowElectricityKwh
}

class HolisticScheduleOptimizer(
    val config: HolisticOptimizationConfig = HolisticOptimizationConfig(),
    val copModel: CopThermodynamicModel = CopThermodynamicModel(),
    val energyModel: BuildingEnergyBalanceModel = BuildingEnergyBalanceModel(
        buildingLossCoefficientKwPerK = config.buildingLossKwPerK,
        heatPumpNominalThermalPowerKw = config.heatPumpNominalThermalKw,
        floorScreedThermalCapacityKwhPerK = config.floorScreedCapacityKwhPerK,
        maxThermalBufferingK = config.maxThermalBufferingK,
        baselineInternalGainsKw = config.baselineInternalGainsKw
    )
) {
    companion object {
        val BERLIN_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
    }

    fun buildSchedule(
        date: LocalDate,
        spotSlots: List<SpotPriceSlot>,
        weatherForecast: List<HourlyWeatherForecast> = emptyList()
    ): DailySwitchSchedulePlan? {
        val dayStartEpoch = date.atStartOfDay(BERLIN_ZONE).toInstant().epochSecond
        val dayEndEpoch = date.plusDays(1).atStartOfDay(BERLIN_ZONE).toInstant().epochSecond

        // 1. Filter and build raster slots for this day
        val daySpots = spotSlots.filter { slot ->
            slot.endEpochSeconds > dayStartEpoch && slot.startEpochSeconds < dayEndEpoch
        }
        if (daySpots.isEmpty()) return null

        val rasterMinutes = config.timeRasterMinutes.coerceIn(5, 60)
        val slotsPerDay = 1440 / rasterMinutes

        // Map hourly weather to lookup array
        val hourlyTemps = DoubleArray(24) { h ->
            val matching = weatherForecast.firstOrNull { wf ->
                runCatching {
                    val ldt = java.time.LocalDateTime.parse(wf.dateTime)
                    ldt.toLocalDate() == date && ldt.hour == h
                }.getOrDefault(false)
            }
            matching?.outdoorTempC ?: 8.0 // default 8°C if missing
        }

        // 2. Score each raster slot using thermodynamic COP and effective heat cost
        val scoredSlots = mutableListOf<ScoredRasterSlot>()
        for (i in 0 until slotsPerDay) {
            val startMin = i * rasterMinutes
            val endMin = startMin + rasterMinutes
            val slotStartEpoch = dayStartEpoch + startMin * 60L
            val slotEndEpoch = dayStartEpoch + endMin * 60L
            val hourOfDay = (startMin / 60).coerceIn(0, 23)

            // Find overlapping spot price slot
            val matchedSpot = daySpots.firstOrNull { s ->
                val overlapStart = max(s.startEpochSeconds, slotStartEpoch)
                val overlapEnd = min(s.endEpochSeconds, slotEndEpoch)
                (overlapEnd - overlapStart) > 0
            } ?: daySpots.minByOrNull { kotlin.math.abs(it.startEpochSeconds - slotStartEpoch) }
            ?: continue

            val outdoorT = hourlyTemps[hourOfDay]
            val supplyT = copModel.estimateSupplyTempC(
                outdoorTempC = outdoorT,
                targetRoomTempC = config.targetHeatingTempC
            )
            val cop = copModel.calculateCop(outdoorT, supplyT)
            val heatCostCt = copModel.computeDeliveredHeatCostCtPerKwh(matchedSpot.totalGrossCtPerKwh, cop)
            val middayBonus = copModel.computeMiddayBonusMultiplier(hourOfDay)

            // Composite score: lower is better. Midday Carnot advantage reduces effective score
            val score = heatCostCt / middayBonus

            // Electrical cost of this slot (using calibrated electrical power draw if set, otherwise thermalPower / COP)
            val elecPowerKw = if (config.estimatedPowerKw > 0.1) config.estimatedPowerKw else (config.heatPumpNominalThermalKw / cop)
            val slotCostEuro = (elecPowerKw * (rasterMinutes / 60.0)) * (matchedSpot.totalGrossCtPerKwh / 100.0)

            scoredSlots.add(
                ScoredRasterSlot(
                    index = i,
                    startMinutes = startMin,
                    endMinutes = endMin,
                    outdoorTempC = outdoorT,
                    supplyTempC = supplyT,
                    cop = cop,
                    spotNetCtPerKwh = matchedSpot.spotNetCtPerKwh,
                    totalGrossCtPerKwh = matchedSpot.totalGrossCtPerKwh,
                    deliveredHeatCostCtPerKwh = heatCostCt,
                    effectiveCompositeScore = score,
                    slotCostEuro = slotCostEuro
                )
            )
        }

        if (scoredSlots.isEmpty()) return null

        // 3. Closed-Loop Digital Twin & Dynamic Thermal Energy Requirement
        val setbackContinuousPowerKw = config.customMeasuredParasiticLoadKw ?: (config.circulationPumpKw + 0.005)
        val controlSimulator = if (config.useEmpiricalGroundTruthModulation) {
            ControlUnitSimulator(
                buildingLossKwPerK = config.buildingLossKwPerK,
                screedThermalMassKwhPerK = config.floorScreedCapacityKwhPerK,
                indoorThermalMassKwhPerK = config.indoorThermalMassKwhPerK,
                calibratedModAt10C = config.calibratedModulationAt10C,
                calibratedModSlopePerK = config.calibratedModulationSlopePerK,
                cutInHysteresisK = 1.2,
                cutOutHysteresisK = 0.5,
                heatingCurveSlope = 0.65,
                comfortRoomTempC = config.targetHeatingTempC,
                setbackRoomTempC = 17.0,
                nominalThermalKw = config.heatPumpMaxThermalKw,
                circulationPumpKw = config.circulationPumpKw,
                standbyPowerKw = setbackContinuousPowerKw,
                parasiticBaselinePowerKw = config.customMeasuredParasiticLoadKw ?: config.parasiticBaselinePowerKw,
                baselineInternalGainsKw = config.baselineInternalGainsKw
            )
        } else null

        val targetComfortReturn = controlSimulator?.estimateTargetReturnTempC(
            outdoorTempC = hourlyTemps.firstOrNull() ?: 8.0,
            targetRoomTempC = config.targetHeatingTempC
        ) ?: ((config.targetHeatingTempC + 0.65 * (20.0 - (hourlyTemps.firstOrNull() ?: 8.0)) - 4.5).coerceIn(16.0, 45.0))

        val dailyEnergyNeedKwh = energyModel.calculateDailyThermalEnergyNeedKwh(
            targetRoomTempC = config.targetHeatingTempC,
            hourlyOutdoorTempsC = hourlyTemps.toList(),
            scheduledComfortHours = config.targetDailyRuntimeHours,
            coolingRateKPerHour = config.coolingRateKPerHour,
            liveStartingReturnTempC = config.liveStartingReturnTempC,
            targetComfortReturnTempC = targetComfortReturn
        )
        val physicalNominalHours = energyModel.calculateNominalRequiredHours(dailyEnergyNeedKwh)
        val userTargetHours = config.targetDailyRuntimeHours

        // Dynamic extra hours as a function of the set runtime, harmonized with scheduler quantifier
        val extraHours = config.preference.calculateHarmonizedExtraHours(
            targetHours = userTargetHours,
            quantizerHours = config.durationQuantizerHours
        )
        val hasZeroPrices = scoredSlots.any { it.spotNetCtPerKwh <= config.zeroPriceThresholdNetCtPerKwh }

        // Displaceable evening peak load (hours 17..21)
        val eveningHeatNeedKwh = (17..21).sumOf { h ->
            energyModel.calculateLossRateKw(config.targetHeatingTempC, hourlyTemps[h])
        }
        val maxArbitrageBufferHours = min(
            extraHours,
            eveningHeatNeedKwh / max(1.0, config.heatPumpNominalThermalKw)
        )

        val rasterH = rasterMinutes / 60.0
        val usableN = scoredSlots.size - 1 // Leave room for explicit 23:45 OFF point
        val minContinuousSlots = (config.minContinuousRunMinutes / rasterMinutes).coerceAtLeast(1)

        // Define the normal duration corridor:
        // Floor is firmly anchored to user target runtime (+0.0h / non-negative corridor).
        // Ceiling expands proportionally to user target runtime, harmonized with scheduler quantifier.
        val baseMinH = userTargetHours
        val baseMaxH = userTargetHours + extraHours

        val normalMinSlots = max(minContinuousSlots, (baseMinH / rasterH).roundToInt()).coerceIn(1, usableN)
        val normalMaxSlots = min(usableN, (baseMaxH / rasterH).roundToInt()).coerceAtLeast(normalMinSlots)

        val prefixScore = DoubleArray(scoredSlots.size + 1)
        val prefixGross = DoubleArray(scoredSlots.size + 1)
        val prefixNet = DoubleArray(scoredSlots.size + 1)
        val prefixFixedEuro = DoubleArray(scoredSlots.size + 1)
        val prefixHeatCostCoeff = DoubleArray(scoredSlots.size + 1)
        val prefixPumpCost = DoubleArray(scoredSlots.size + 1)
        val prefixInvCop = DoubleArray(scoredSlots.size + 1)
        val prefixOutdoorT = DoubleArray(scoredSlots.size + 1)

        for (i in scoredSlots.indices) {
            prefixScore[i + 1] = prefixScore[i] + scoredSlots[i].effectiveCompositeScore
            prefixGross[i + 1] = prefixGross[i] + scoredSlots[i].totalGrossCtPerKwh
            prefixNet[i + 1] = prefixNet[i] + scoredSlots[i].spotNetCtPerKwh
            prefixFixedEuro[i + 1] = prefixFixedEuro[i] + scoredSlots[i].slotCostEuro

            val grossPerKwh = scoredSlots[i].totalGrossCtPerKwh / 100.0
            val cop = max(1.0, scoredSlots[i].cop)
            prefixHeatCostCoeff[i + 1] = prefixHeatCostCoeff[i] + (grossPerKwh / cop) * rasterH
            prefixPumpCost[i + 1] = prefixPumpCost[i] + (config.circulationPumpKw * grossPerKwh) * rasterH
            prefixInvCop[i + 1] = prefixInvCop[i] + (1.0 / cop)
            prefixOutdoorT[i + 1] = prefixOutdoorT[i] + scoredSlots[i].outdoorTempC
        }

        val slotGrossCt = DoubleArray(scoredSlots.size) { scoredSlots[it].totalGrossCtPerKwh }

        // 4. Multi-Criteria Candidate Window Evaluation with Two-Stage Budget Verification
        val lagSlots = (2.5 / rasterH).roundToInt() // 2.5h screed diffusion delay

        fun evaluateSlotCountRange(slotRange: IntRange, filterBudget: Boolean = true): List<CandidateWindow> {
            val result = mutableListOf<CandidateWindow>()
            for (on in slotRange) {
                val durH = on * rasterH
                val deltaH = durH - userTargetHours

                for (startIdx in 0..(usableN - on)) {
                    val endIdx = startIdx + on
                    val metrics = computeWindowMetrics(
                        startIdx = startIdx,
                        endIdx = endIdx,
                        dailyEnergyNeedKwh = dailyEnergyNeedKwh,
                        prefixHeatCostCoeff = prefixHeatCostCoeff,
                        prefixPumpCost = prefixPumpCost,
                        prefixInvCop = prefixInvCop,
                        prefixFixedEuro = prefixFixedEuro,
                        prefixOutdoorT = prefixOutdoorT,
                        rasterH = rasterH,
                        hourlyTemps = hourlyTemps,
                        slotGrossCt = slotGrossCt,
                        rasterMinutes = rasterMinutes,
                        controlSimulator = controlSimulator
                    )

                    if (filterBudget && config.dailyBudgetEuro != null && metrics.euroCost > config.dailyBudgetEuro + 1e-6) {
                        continue // exceeds budget
                    }

                    // A. Budget Utility (Evaluates average electricity price per kWh + evening peak arbitrage waste penalty)
                    val avgGrossCtPerKwh = (prefixGross[endIdx] - prefixGross[startIdx]) / on
                    val wasteHours = max(0.0, deltaH - maxArbitrageBufferHours)
                    val wastePenalty = wasteHours * (config.grossBaseFloorCtPerKwh / 100.0) * 10.0
                    val rawBudget = avgGrossCtPerKwh + wastePenalty

                    // B. Efficiency Utility (Carnot specific consumption + cold-restart rebound penalty + longer daytime duration bonus)
                    val effSum = prefixScore[endIdx] - prefixScore[startIdx]
                    val avgEffScore = effSum / on
                    val reboundPenalty = if (deltaH < -0.5) 0.05 * (deltaH * deltaH) else 0.0
                    val longerDurationBonus = if (config.preference == OptimizationPreference.EFFICIENCY) {
                        // In efficiency mode: prefer longer daytime window (up to +2.0h above target) at low inverter modulation.
                        -0.05 * max(0.0, durH - userTargetHours)
                    } else 0.0
                    val rawEfficiency = avgEffScore + reboundPenalty + longerDurationBonus

                    // C. Comfort Utility (Screed diffusion delay ~2.5h + runtime deficit penalty + off-gap cooldown)
                    val emitStart = startIdx + lagSlots
                    val emitEnd = endIdx + lagSlots
                    var activeOverlap = 0
                    for (s in emitStart until emitEnd) {
                        val slotMod = s % slotsPerDay
                        val minuteOfDay = slotMod * rasterMinutes
                        // Active occupancy: morning (06:30-08:30) and evening (17:00-22:30)
                        if (minuteOfDay in 390..510 || minuteOfDay in 1020..1350) {
                            activeOverlap++
                        }
                    }
                    val activeFrac = activeOverlap.toDouble() / on.toDouble()
                    val runtimeDeficit = max(0.0, userTargetHours - durH)
                    val underheatPenalty = if (runtimeDeficit > 0.0) 2.5 * runtimeDeficit.pow(1.6) else 0.0
                    val offHours = 24.0 - durH
                    val cooldownPenalty = if (offHours > config.preference.maxOffHours) {
                        0.5 * (offHours - config.preference.maxOffHours).pow(1.5)
                    } else 0.0
                    val rawComfort = (1.0 - activeFrac) + underheatPenalty + cooldownPenalty

                    result.add(
                        CandidateWindow(
                            startIdx = startIdx,
                            length = on,
                            durationHours = durH,
                            deltaHours = deltaH,
                            euroCost = metrics.euroCost,
                            electricityKwh = metrics.electricityKwh,
                            avgPowerKw = metrics.avgPowerKw,
                            modulationPct = metrics.modulationPct,
                            rawBudget = rawBudget,
                            rawEfficiency = rawEfficiency,
                            rawComfort = rawComfort
                        )
                    )
                }
            }
            return result
        }

        // Stage 1: Evaluate candidate windows strictly within the normal target corridor
        val candidates = evaluateSlotCountRange(normalMinSlots..normalMaxSlots, filterBudget = true).toMutableList()
        var isBudgetForcedCut = false

        val chosenWindow = if (candidates.isNotEmpty()) {
            val minBud = candidates.minOf { it.rawBudget }
            val maxBud = candidates.maxOf { it.rawBudget }
            val rangeBud = max(1e-4, maxBud - minBud)

            val minEff = candidates.minOf { it.rawEfficiency }
            val maxEff = candidates.maxOf { it.rawEfficiency }
            val rangeEff = max(1e-4, maxEff - minEff)

            val minComf = candidates.minOf { it.rawComfort }
            val maxComf = candidates.maxOf { it.rawComfort }
            val rangeComf = max(1e-4, maxComf - minComf)

            val pref = config.preference
            for (c in candidates) {
                val normBud = (c.rawBudget - minBud) / rangeBud
                val normEff = (c.rawEfficiency - minEff) / rangeEff
                val normComf = (c.rawComfort - minComf) / rangeComf
                c.score = pref.weightBudget * normBud + pref.weightEfficiency * normEff + pref.weightComfort * normComf
            }

            candidates.minByOrNull { it.score } ?: candidates.first()
        } else if (config.dailyBudgetEuro != null) {
            // Stage 2: Budget ceiling breached by every window in target corridor.
            // Pick the best unconstrained candidate from the normal corridor and apply Marginal Cost Edge Shaving!
            isBudgetForcedCut = true
            val unconstrainedCandidates = evaluateSlotCountRange(normalMinSlots..normalMaxSlots, filterBudget = false)
            val baseCandidate = if (unconstrainedCandidates.isNotEmpty()) {
                val minBud = unconstrainedCandidates.minOf { it.rawBudget }
                val maxBud = unconstrainedCandidates.maxOf { it.rawBudget }
                val rangeBud = max(1e-4, maxBud - minBud)

                val minEff = unconstrainedCandidates.minOf { it.rawEfficiency }
                val maxEff = unconstrainedCandidates.maxOf { it.rawEfficiency }
                val rangeEff = max(1e-4, maxEff - minEff)

                val minComf = unconstrainedCandidates.minOf { it.rawComfort }
                val maxComf = unconstrainedCandidates.maxOf { it.rawComfort }
                val rangeComf = max(1e-4, maxComf - minComf)

                val pref = config.preference
                for (c in unconstrainedCandidates) {
                    val normBud = (c.rawBudget - minBud) / rangeBud
                    val normEff = (c.rawEfficiency - minEff) / rangeEff
                    val normComf = (c.rawComfort - minComf) / rangeComf
                    c.score = pref.weightBudget * normBud + pref.weightEfficiency * normEff + pref.weightComfort * normComf
                }
                unconstrainedCandidates.minByOrNull { it.score } ?: unconstrainedCandidates.first()
            } else null

            var curStart = baseCandidate?.startIdx ?: 0
            var curEnd = if (baseCandidate != null) baseCandidate.startIdx + baseCandidate.length else minContinuousSlots
            var curMetrics = computeWindowMetrics(
                startIdx = curStart,
                endIdx = curEnd,
                dailyEnergyNeedKwh = dailyEnergyNeedKwh,
                prefixHeatCostCoeff = prefixHeatCostCoeff,
                prefixPumpCost = prefixPumpCost,
                prefixInvCop = prefixInvCop,
                prefixFixedEuro = prefixFixedEuro,
                prefixOutdoorT = prefixOutdoorT,
                rasterH = rasterH,
                hourlyTemps = hourlyTemps,
                slotGrossCt = slotGrossCt,
                rasterMinutes = rasterMinutes,
                controlSimulator = controlSimulator
            )

            // MARGINAL COST EDGE SHAVING:
            // Compare delivered heat cost c_heat = c_gross / cop at start slot vs end slot
            // Iteratively trim the slot with higher delivered heat cost until cost <= budget or length == minContinuousSlots
            while (curMetrics.euroCost > config.dailyBudgetEuro + 1e-6 && (curEnd - curStart) > minContinuousSlots) {
                val startSlot = scoredSlots[curStart]
                val endSlot = scoredSlots[curEnd - 1]

                val startHeatCost = startSlot.deliveredHeatCostCtPerKwh
                val endHeatCost = endSlot.deliveredHeatCostCtPerKwh

                if (startHeatCost >= endHeatCost) {
                    curStart++ // Shave start edge (delay start)
                } else {
                    curEnd-- // Shave end edge (shut off earlier)
                }

                curMetrics = computeWindowMetrics(
                    startIdx = curStart,
                    endIdx = curEnd,
                    dailyEnergyNeedKwh = dailyEnergyNeedKwh,
                    prefixHeatCostCoeff = prefixHeatCostCoeff,
                    prefixPumpCost = prefixPumpCost,
                    prefixInvCop = prefixInvCop,
                    prefixFixedEuro = prefixFixedEuro,
                    prefixOutdoorT = prefixOutdoorT,
                    rasterH = rasterH,
                    hourlyTemps = hourlyTemps,
                    slotGrossCt = slotGrossCt,
                    rasterMinutes = rasterMinutes,
                    controlSimulator = controlSimulator
                )
            }

            // Fallback: If even after edge shaving down to minContinuousSlots, the cost still exceeds budget,
            // scan all possible minContinuousSlots windows across the day to find the absolute cheapest one
            if (curMetrics.euroCost > config.dailyBudgetEuro + 1e-6) {
                var bestStart = 0
                var bestCost = Double.MAX_VALUE
                var bestM = curMetrics
                for (s in 0..(usableN - minContinuousSlots)) {
                    val m = computeWindowMetrics(
                        startIdx = s,
                        endIdx = s + minContinuousSlots,
                        dailyEnergyNeedKwh = dailyEnergyNeedKwh,
                        prefixHeatCostCoeff = prefixHeatCostCoeff,
                        prefixPumpCost = prefixPumpCost,
                        prefixInvCop = prefixInvCop,
                        prefixFixedEuro = prefixFixedEuro,
                        prefixOutdoorT = prefixOutdoorT,
                        rasterH = rasterH,
                        hourlyTemps = hourlyTemps,
                        slotGrossCt = slotGrossCt,
                        rasterMinutes = rasterMinutes,
                        controlSimulator = controlSimulator
                    )
                    if (m.euroCost < bestCost - 1e-9) {
                        bestCost = m.euroCost
                        bestStart = s
                        bestM = m
                    }
                }
                curStart = bestStart
                curEnd = bestStart + minContinuousSlots
                curMetrics = bestM
            }

            val shavedLen = curEnd - curStart
            val durH = shavedLen * rasterH
            CandidateWindow(
                startIdx = curStart,
                length = shavedLen,
                durationHours = durH,
                deltaHours = durH - userTargetHours,
                euroCost = curMetrics.euroCost,
                electricityKwh = curMetrics.electricityKwh,
                avgPowerKw = curMetrics.avgPowerKw,
                modulationPct = curMetrics.modulationPct,
                rawBudget = curMetrics.euroCost,
                rawEfficiency = 0.0,
                rawComfort = 1.0
            )
        } else {
            // Unreachable fallback for safety
            CandidateWindow(
                startIdx = 0,
                length = minContinuousSlots,
                durationHours = minContinuousSlots * rasterH,
                deltaHours = (minContinuousSlots * rasterH) - userTargetHours,
                euroCost = 0.0,
                electricityKwh = 0.0,
                avgPowerKw = 0.0,
                modulationPct = 0.0,
                rawBudget = 0.0,
                rawEfficiency = 0.0,
                rawComfort = 1.0
            )
        }

        var startIdx = chosenWindow.startIdx
        var endIdx = startIdx + chosenWindow.length

        // Zero / Negative price extension up to the arbitrage limit (strictly bounded by daily budget if set)
        if (hasZeroPrices) {
            val maxAllowedSlots = ((userTargetHours + extraHours + maxArbitrageBufferHours) / rasterH).roundToInt().coerceAtLeast(chosenWindow.length)
            while (endIdx < usableN && (endIdx - startIdx) < maxAllowedSlots && scoredSlots[endIdx].spotNetCtPerKwh <= config.zeroPriceThresholdNetCtPerKwh) {
                val nextMetrics = computeWindowMetrics(
                    startIdx = startIdx,
                    endIdx = endIdx + 1,
                    dailyEnergyNeedKwh = dailyEnergyNeedKwh,
                    prefixHeatCostCoeff = prefixHeatCostCoeff,
                    prefixPumpCost = prefixPumpCost,
                    prefixInvCop = prefixInvCop,
                    prefixFixedEuro = prefixFixedEuro,
                    prefixOutdoorT = prefixOutdoorT,
                    rasterH = rasterH,
                    hourlyTemps = hourlyTemps,
                    slotGrossCt = slotGrossCt,
                    rasterMinutes = rasterMinutes,
                    controlSimulator = controlSimulator
                )
                if (config.dailyBudgetEuro != null && nextMetrics.euroCost > config.dailyBudgetEuro + 1e-6) break
                endIdx++
            }
            while (startIdx > 0 && (endIdx - startIdx) < maxAllowedSlots && scoredSlots[startIdx - 1].spotNetCtPerKwh <= config.zeroPriceThresholdNetCtPerKwh) {
                val nextMetrics = computeWindowMetrics(
                    startIdx = startIdx - 1,
                    endIdx = endIdx,
                    dailyEnergyNeedKwh = dailyEnergyNeedKwh,
                    prefixHeatCostCoeff = prefixHeatCostCoeff,
                    prefixPumpCost = prefixPumpCost,
                    prefixInvCop = prefixInvCop,
                    prefixFixedEuro = prefixFixedEuro,
                    prefixOutdoorT = prefixOutdoorT,
                    rasterH = rasterH,
                    hourlyTemps = hourlyTemps,
                    slotGrossCt = slotGrossCt,
                    rasterMinutes = rasterMinutes,
                    controlSimulator = controlSimulator
                )
                if (config.dailyBudgetEuro != null && nextMetrics.euroCost > config.dailyBudgetEuro + 1e-6) break
                startIdx--
            }
        }

        val finalMetrics = computeWindowMetrics(
            startIdx = startIdx,
            endIdx = endIdx,
            dailyEnergyNeedKwh = dailyEnergyNeedKwh,
            prefixHeatCostCoeff = prefixHeatCostCoeff,
            prefixPumpCost = prefixPumpCost,
            prefixInvCop = prefixInvCop,
            prefixFixedEuro = prefixFixedEuro,
            prefixOutdoorT = prefixOutdoorT,
            rasterH = rasterH,
            hourlyTemps = hourlyTemps,
            slotGrossCt = slotGrossCt,
            rasterMinutes = rasterMinutes,
            controlSimulator = controlSimulator
        )

        val chosenLen = endIdx - startIdx
        val avgGross = (prefixGross[endIdx] - prefixGross[startIdx]) / chosenLen
        val avgNet = (prefixNet[endIdx] - prefixNet[startIdx]) / chosenLen

        val dayCode = toBoschDayOfWeek(date.dayOfWeek)
        val dateStr = date.toString()

        val sharedWindow = TimeWindow(
            date = dateStr,
            dayOfWeekCode = dayCode,
            startMinutes = scoredSlots[startIdx].startMinutes,
            endMinutes = scoredSlots[endIdx - 1].endMinutes,
            setpoint = config.heatingOnSetpoint,
            averageGrossCtPerKwh = avgGross,
            averageSpotNetCtPerKwh = avgNet,
            estimatedWindowCostEuro = finalMetrics.windowCostEuro,
            estimatedElectricityKwh = finalMetrics.windowElectricityKwh,
            estimatedAveragePowerKw = finalMetrics.avgPowerKw,
            estimatedModulationPct = finalMetrics.modulationPct
        )

        val plannedMinutes = sharedWindow.durationMinutes
        val baselineReferenceMinutes = (userTargetHours * 60.0).roundToInt()
        val budgetCutMinutes = if (isBudgetForcedCut) {
            max(0, baselineReferenceMinutes - plannedMinutes)
        } else {
            0
        }

        val heatingWindows = listOf(sharedWindow.copy(setpoint = config.heatingOnSetpoint))
        val dhwWindows = listOf(sharedWindow.copy(setpoint = config.dhwOnSetpoint))

        val heatingSwitchPoints = windowsToSwitchPoints(
            dayOfWeekCode = dayCode,
            windows = heatingWindows,
            onSetpoint = config.heatingOnSetpoint,
            offSetpoint = config.heatingOffSetpoint
        )

        val dhwSwitchPoints = windowsToSwitchPoints(
            dayOfWeekCode = dayCode,
            windows = dhwWindows,
            onSetpoint = config.dhwOnSetpoint,
            offSetpoint = config.dhwOffSetpoint
        )

        val avgOutdoorT = hourlyTemps.take(24).average()
        val minOutdoorT = hourlyTemps.take(24).minOrNull() ?: avgOutdoorT
        val maxOutdoorT = hourlyTemps.take(24).maxOrNull() ?: avgOutdoorT
        val winOutdoorT = (prefixOutdoorT[endIdx] - prefixOutdoorT[startIdx]) / chosenLen
        val hours = plannedMinutes / 60.0
        val daytimeLossRateKw = kotlin.math.max(0.0, (config.targetHeatingTempC - avgOutdoorT) * config.buildingLossKwPerK - config.baselineInternalGainsKw)
        val daytimeThermalNeedKwh = daytimeLossRateKw * hours
        val recoveryThermalNeedKwh = kotlin.math.max(0.0, dailyEnergyNeedKwh - daytimeThermalNeedKwh)
        val flowTemp = config.targetHeatingTempC + (controlSimulator?.heatingCurveSlope ?: 0.65) * (20.0 - winOutdoorT)
        val liftK = kotlin.math.max(4.0, flowTemp - winOutdoorT)
        val cop = (1.0 / (0.00542 * liftK + 0.060)).coerceIn(1.8, 8.5)

        val breakdown = buildString {
            appendLine("1. Weather Conditions (DWD ICON Forecast):")
            appendLine(String.format(java.util.Locale.US, "   • 24h Average Outdoor Temp: %.1f°C (Min %.1f°C, Max %.1f°C)", avgOutdoorT, minOutdoorT, maxOutdoorT))
            appendLine(String.format(java.util.Locale.US, "   • Window Operating Ambient Temp: %.1f°C", winOutdoorT))
            appendLine(String.format(java.util.Locale.US, "   • Indoor - Outdoor Delta T: %.1f K", config.targetHeatingTempC - avgOutdoorT))
            appendLine()
            val adaptiveNote = if (config.adaptiveCycleCount > 0) {
                String.format(java.util.Locale.US, " (Adaptive online learned from %d nocturnal cycles)", config.adaptiveCycleCount)
            } else {
                " (Calibrated baseline)"
            }
            appendLine("2. Thermal Demand (2R2C Coupled State-Space):")
            appendLine(String.format(java.util.Locale.US, "   • Building Loss Coeff (Hbld): %.3f kW/K%s", config.buildingLossKwPerK, adaptiveNote))
            appendLine(String.format(java.util.Locale.US, "   • Screed Cooldown Rate (κ): %.2f K/h", config.coolingRateKPerHour))
            appendLine(String.format(java.util.Locale.US, "   • Domestic & Solar Gains Offset: %.2f kW", config.baselineInternalGainsKw))
            if (config.liveStartingReturnTempC != null) {
                appendLine(String.format(java.util.Locale.US, "   • Live Measured Screed Return: %.1f°C (Target return: %.1f°C)", config.liveStartingReturnTempC, targetComfortReturn))
            }
            appendLine(String.format(java.util.Locale.US, "   • Active Daytime Net Heat Loss: %.2f kWh_th (%.2f kW × %.1fh)", daytimeThermalNeedKwh, daytimeLossRateKw, hours))
            appendLine(String.format(java.util.Locale.US, "   • Nocturnal Reheat Mass Recovery: %.2f kWh_th (Cscreed = %.1f kWh/K)", recoveryThermalNeedKwh, config.floorScreedCapacityKwhPerK))
            appendLine(String.format(java.util.Locale.US, "   • Total Daily Thermal Demand: %.2f kWh_th", dailyEnergyNeedKwh))
            appendLine()
            val offHours = kotlin.math.max(0.0, 24.0 - hours)
            val setbackElecKwh = kotlin.math.max(0.0, finalMetrics.dailyElectricityKwh - finalMetrics.windowElectricityKwh)
            val setbackCostEuro = kotlin.math.max(0.0, finalMetrics.dailyCostEuro - finalMetrics.windowCostEuro)
            val setbackPowerW = if (offHours > 0.05) ((setbackElecKwh / offHours) * 1000.0).roundToInt() else 45

            appendLine("3. Inverter Modulation & Thermodynamics:")
            appendLine(String.format(java.util.Locale.US, "   • Target Flow Temp: %.1f°C (Carnot Lift: %.1f K)", flowTemp, liftK))
            appendLine(String.format(java.util.Locale.US, "   • Thermodynamic Reciprocal COP: %.2f", cop))
            appendLine(String.format(java.util.Locale.US, "   • Bosch Inverter Speed: ~%.0f%% modulation", finalMetrics.modulationPct))
            appendLine(String.format(java.util.Locale.US, "   • Active Electrical Draw: %.2f kW", finalMetrics.avgPowerKw))
            appendLine(String.format(java.util.Locale.US, "   • Active Window Electrical Consumption: %.1f kWh (%.1fh)", finalMetrics.windowElectricityKwh, hours))
            appendLine(String.format(java.util.Locale.US, "   • Setback Parasitic & Circulation Draw: %.2f kWh (%.1fh @ %dW)", setbackElecKwh, offHours, setbackPowerW))
            appendLine(String.format(java.util.Locale.US, "   • 24-Hour Total Electrical Consumption: %.1f kWh", finalMetrics.dailyElectricityKwh))
            appendLine()
            appendLine("4. Tariff & Cost:")
            appendLine(String.format(java.util.Locale.US, "   • EPEX Spot Average (Window): %.1f ct/kWh gross (%.1f ct/kWh net)", avgGross, avgNet))
            appendLine(String.format(java.util.Locale.US, "   • Active Window Operating Cost: %.2f €", finalMetrics.windowCostEuro))
            appendLine(String.format(java.util.Locale.US, "   • Setback Standby & Circulation Cost: %.2f €", setbackCostEuro))
            appendLine(String.format(java.util.Locale.US, "   • 24-Hour Total Electricity Cost: %.2f €", finalMetrics.dailyCostEuro))
            appendLine()
            appendLine("5. Dynamic Runtime Corridor (Harmonized Quantizer):")
            appendLine(String.format(java.util.Locale.US, "   • Target Daily Runtime (Slider): %.1f h", userTargetHours))
            val extraPct = (config.preference.maxExpansionRatio * 100.0).roundToInt()
            val quantizerMin = (config.durationQuantizerHours * 60.0).roundToInt()
            appendLine(String.format(java.util.Locale.US, "   • Preference Mode: %s (+%d%% expansion allowance)", config.preference.title, extraPct))
            appendLine(String.format(java.util.Locale.US, "   • Scheduler Quantizer Step: %.2f h (%d min)", config.durationQuantizerHours, quantizerMin))
            appendLine(String.format(java.util.Locale.US, "   • Harmonized Extra Corridor Allowance: +%.1f h (Corridor: %.1fh .. %.1fh)", extraHours, baseMinH, baseMaxH))
            appendLine(String.format(java.util.Locale.US, "   • Search Slots Evaluated: %d .. %d raster slots (%d-min raster)", normalMinSlots, normalMaxSlots, rasterMinutes))
            val deltaHPlanned = hours - userTargetHours
            val sign = if (deltaHPlanned >= 0.0) "+" else ""
            appendLine(String.format(java.util.Locale.US, "   • Applied Schedule Duration: %.1f h (%s%.1f h vs target slider)", hours, sign, deltaHPlanned))
        }

        val setbackElecKwh = kotlin.math.max(0.0, finalMetrics.dailyElectricityKwh - finalMetrics.windowElectricityKwh)
        val setbackCostEuro = kotlin.math.max(0.0, finalMetrics.dailyCostEuro - finalMetrics.windowCostEuro)

        return DailySwitchSchedulePlan(
            date = dateStr,
            dayOfWeekCode = dayCode,
            sharedWindows = listOf(sharedWindow),
            plannedOperationMinutes = plannedMinutes,
            budgetCutMinutes = budgetCutMinutes,
            estimatedDailyCostEuro = finalMetrics.windowCostEuro,
            dailyBudgetEuro = config.dailyBudgetEuro,
            targetHeatingTempC = config.targetHeatingTempC,
            heatingWindows = heatingWindows,
            heatingSwitchPoints = heatingSwitchPoints,
            dhwWindows = dhwWindows,
            dhwSwitchPoints = dhwSwitchPoints,
            estimatedElectricityKwh = finalMetrics.windowElectricityKwh,
            estimatedDailyTotalElectricityKwh = finalMetrics.dailyElectricityKwh,
            estimatedDailyTotalCostEuro = finalMetrics.dailyCostEuro,
            estimatedSetbackElectricityKwh = setbackElecKwh,
            estimatedSetbackCostEuro = setbackCostEuro,
            estimatedAveragePowerKw = finalMetrics.avgPowerKw,
            estimatedModulationPct = finalMetrics.modulationPct,
            physicsBreakdown = breakdown
        )
    }

    private fun computeWindowMetrics(
        startIdx: Int,
        endIdx: Int,
        dailyEnergyNeedKwh: Double,
        prefixHeatCostCoeff: DoubleArray,
        prefixPumpCost: DoubleArray,
        prefixInvCop: DoubleArray,
        prefixFixedEuro: DoubleArray,
        prefixOutdoorT: DoubleArray,
        rasterH: Double,
        hourlyTemps: DoubleArray,
        slotGrossCt: DoubleArray,
        rasterMinutes: Int,
        controlSimulator: ControlUnitSimulator?
    ): WindowEvalResult {
        val on = endIdx - startIdx
        val durH = on * rasterH
        if (durH <= 0.0) return WindowEvalResult(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        val winOutdoorT = (prefixOutdoorT[endIdx] - prefixOutdoorT[startIdx]) / on

        return if (config.autoPowerModulationEnabled) {
            if (config.useEmpiricalGroundTruthModulation && controlSimulator != null) {
                val sim = controlSimulator.simulateSchedule(
                    comfortStartMinute = startIdx * rasterMinutes,
                    comfortEndMinute = endIdx * rasterMinutes,
                    hourlyTemps = hourlyTemps,
                    slotGrossCtPerKwh = slotGrossCt,
                    rasterMinutes = rasterMinutes,
                    targetDailyThermalEnergyNeedKwh = dailyEnergyNeedKwh
                )
                WindowEvalResult(
                    windowCostEuro = sim.windowCostEuro,
                    dailyCostEuro = sim.totalCostEuro,
                    windowElectricityKwh = sim.windowElectricityKwh,
                    dailyElectricityKwh = sim.totalElectricityKwh,
                    avgPowerKw = sim.averageActivePowerKw,
                    modulationPct = sim.averageModulationPct
                )
            } else {
                val targetThermalKw = dailyEnergyNeedKwh / durH
                val physicalThermalKw = targetThermalKw.coerceIn(config.heatPumpMinThermalKw, config.heatPumpMaxThermalKw)
                val physicalModPct = (physicalThermalKw / config.heatPumpMaxThermalKw * 100.0).coerceIn(20.0, 100.0)
                val modThermalKw = (physicalModPct / 100.0) * config.heatPumpMaxThermalKw

                val deltaHeatCoeff = prefixHeatCostCoeff[endIdx] - prefixHeatCostCoeff[startIdx]
                val deltaPumpCost = prefixPumpCost[endIdx] - prefixPumpCost[startIdx]
                val euroCost = modThermalKw * deltaHeatCoeff + deltaPumpCost

                val deltaInvCop = prefixInvCop[endIdx] - prefixInvCop[startIdx]
                val rawElecKwh = (modThermalKw * deltaInvCop * rasterH) + (config.circulationPumpKw * durH)
                val clampedElecKwh = rawElecKwh.coerceIn(config.minElecPowerKw * durH, config.maxElecPowerKw * durH)
                val avgPowerKw = clampedElecKwh / durH

                val offH = kotlin.math.max(0.0, 24.0 - durH)
                val setbackContinuousPowerKw = config.customMeasuredParasiticLoadKw ?: (config.circulationPumpKw + 0.005)
                val setbackElecKwh = setbackContinuousPowerKw * offH
                val avgGrossCt = if (slotGrossCt.isNotEmpty()) slotGrossCt.average() else 30.0
                val setbackCostEuro = setbackElecKwh * (avgGrossCt / 100.0)

                WindowEvalResult(
                    windowCostEuro = euroCost,
                    dailyCostEuro = euroCost + setbackCostEuro,
                    windowElectricityKwh = clampedElecKwh,
                    dailyElectricityKwh = clampedElecKwh + setbackElecKwh,
                    avgPowerKw = avgPowerKw,
                    modulationPct = physicalModPct
                )
            }
        } else {
            val euroCost = prefixFixedEuro[endIdx] - prefixFixedEuro[startIdx]
            val kwh = config.estimatedPowerKw * durH
            val offH = kotlin.math.max(0.0, 24.0 - durH)
            val setbackContinuousPowerKw = config.customMeasuredParasiticLoadKw ?: (config.circulationPumpKw + 0.005)
            val setbackElecKwh = setbackContinuousPowerKw * offH
            val avgGrossCt = if (slotGrossCt.isNotEmpty()) slotGrossCt.average() else 30.0
            val setbackCostEuro = setbackElecKwh * (avgGrossCt / 100.0)
            WindowEvalResult(
                windowCostEuro = euroCost,
                dailyCostEuro = euroCost + setbackCostEuro,
                windowElectricityKwh = kwh,
                dailyElectricityKwh = kwh + setbackElecKwh,
                avgPowerKw = config.estimatedPowerKw,
                modulationPct = 0.0
            )
        }
    }

    private fun windowsToSwitchPoints(
        dayOfWeekCode: String,
        windows: List<TimeWindow>,
        onSetpoint: String,
        offSetpoint: String
    ): List<SwitchPoint> {
        val points = mutableListOf<SwitchPoint>()
        for (w in windows) {
            points.add(SwitchPoint(dayOfWeekCode, onSetpoint, w.startMinutes))
            if (w.endMinutes < 1440) {
                points.add(SwitchPoint(dayOfWeekCode, offSetpoint, w.endMinutes))
            } else {
                points.add(SwitchPoint(dayOfWeekCode, offSetpoint, 1425)) // 23:45 safety cutoff
            }
        }
        return points.sortedBy { it.time }
    }

    private fun toBoschDayOfWeek(dow: java.time.DayOfWeek): String = when (dow) {
        java.time.DayOfWeek.MONDAY -> "Mo"
        java.time.DayOfWeek.TUESDAY -> "Tu"
        java.time.DayOfWeek.WEDNESDAY -> "We"
        java.time.DayOfWeek.THURSDAY -> "Th"
        java.time.DayOfWeek.FRIDAY -> "Fr"
        java.time.DayOfWeek.SATURDAY -> "Sa"
        java.time.DayOfWeek.SUNDAY -> "Su"
    }
}
