package de.breast.telemetry

import kotlin.math.max
import kotlin.math.min

/**
 * Operating point projection showing predicted compressor modulation and electrical power
 * at a specific outdoor ambient temperature.
 */
data class OperatingPointProjection(
    val outdoorTempC: Double,
    val predictedModulationPct: Double,
    val predictedPowerKw: Double,
    val isHardwareClamped: Boolean,
    val scenarioLabel: String
)

/**
 * Result of empirical ground-truth analysis of heat pump compressor modulation.
 */
data class EmpiricalModulationProfile(
    val totalHeatingSamples: Int,
    val steadyStateSamples: Int,
    val detectedRunsCount: Int,
    val avgSteadyModulationPct: Double,
    val avgSteadyOutdoorTempC: Double,
    val avgSteadyDeltaTC: Double,
    val inferredSteadyPowerKw: Double,
    val warmupModulationOverheadFactor: Double,
    val modulationAt10C: Double,
    val modulationSlopePerK: Double,
    val minObservedModulationPct: Double,
    val maxObservedModulationPct: Double,
    val evaluationSummary: String,
    val matchedSetpointTempC: Double? = null,
    val rSquared: Double = 0.90,
    val minObservedOutdoorTempC: Double? = null,
    val maxObservedOutdoorTempC: Double? = null,
    val avgWarmupModulationPct: Double? = null,
    val warmupSamplesCount: Int = 0,
    val historicalPriorIncorporated: Boolean = false,
    val historicalPriorWeight: Double = 0.0
) {
    /**
     * Predicts steady-state modulation percentage for a given outdoor ambient temperature
     * based on empirical regression from historical telemetry.
     */
    fun predictModulationPct(outdoorTempC: Double): Double {
        val raw = modulationAt10C + modulationSlopePerK * (10.0 - outdoorTempC)
        return raw.coerceIn(28.0, 100.0) // Bosch hardware minimum floor is 28.0%
    }

    /**
     * Converts predicted modulation percentage to compressor electrical power draw in kW,
     * harmonized with dynamic thermodynamic temperature-lift COP and parasitic circulation load (~0.05 kW).
     */
    fun predictElectricalPowerKw(outdoorTempC: Double): Double {
        val mod = predictModulationPct(outdoorTempC)
        val thermalKw = (mod / 100.0) * 9.0
        val flowTemp = 21.5 + 0.65 * (20.0 - outdoorTempC)
        val deltaLift = max(4.0, flowTemp - outdoorTempC)
        val cop = (1.0 / (0.00542 * deltaLift + 0.060)).coerceIn(1.8, 8.5)
        val compKw = thermalKw / cop
        return (compKw + 0.05).coerceIn(0.45, 2.40)
    }

    /**
     * Generates a multi-point winter operating corridor projection table
     * across key outdoor temperature benchmarks.
     */
    fun getOperatingProjections(): List<OperatingPointProjection> {
        val scenarios = listOf(
            Pair(15.0, "Mild Autumn (+15°C)"),
            Pair(10.0, "Pivot Baseline (+10°C)"),
            Pair(5.0, "Chilly (+5°C)"),
            Pair(0.0, "Freezing (0°C)"),
            Pair(-5.0, "Frost (-5°C)")
        )
        return scenarios.map { (t, label) ->
            val rawMod = modulationAt10C + modulationSlopePerK * (10.0 - t)
            val clampedMod = rawMod.coerceIn(28.0, 100.0)
            val pKw = predictElectricalPowerKw(t)
            OperatingPointProjection(
                outdoorTempC = t,
                predictedModulationPct = clampedMod,
                predictedPowerKw = pKw,
                isHardwareClamped = rawMod < 28.0,
                scenarioLabel = label
            )
        }
    }
}

/**
 * Extracts empirical ground-truth modulation behavior from historical telemetry snapshots.
 *
 * Replaces pure theoretical assumptions with real observed PID inverter modulation curves
 * from the Bosch Compress 7400i AW 9 / HPC 410.
 */
class EmpiricalModulationAnalyzer(
    val warmupThresholdMinutes: Double = 20.0,
    val minHeatingSamples: Int = 5,
    val runGapThresholdSeconds: Long = 1800L
) {
    /**
     * Analyzes historical snapshots to build the empirical modulation model.
     *
     * @param targetSetpointTempC When specified, filters for snapshots matching this target
     *                            comfort temperature (± setpointToleranceK), excluding outliers
     *                            or unintended high-setpoint runs. If null, automatically selects
     *                            the predominant comfort setpoint mode in the dataset.
     */
    fun analyzeModulationProfile(
        snapshots: List<HeatPumpTelemetrySnapshot>,
        targetSetpointTempC: Double? = null,
        setpointToleranceK: Double = 0.6,
        priorModulationAt10C: Double? = null,
        priorModulationSlopePerK: Double? = null,
        priorWeight: Double = 0.0
    ): EmpiricalModulationProfile? {
        // Determine effective target setpoint to filter out anomalous runs (e.g. 25.5°C when normal is 21.5°C)
        val effectiveTarget = targetSetpointTempC ?: run {
            val comfortSetpoints = snapshots
                .filter { it.isHeatingActive && (it.setpointTempC ?: 0.0) >= 18.0 }
                .mapNotNull { it.setpointTempC }
            if (comfortSetpoints.isNotEmpty()) {
                comfortSetpoints.groupBy { kotlin.math.round(it * 2.0) / 2.0 }
                    .maxByOrNull { it.value.size }?.key
            } else null
        }

        // Filter snapshots where heating was actively running with reported modulation
        val allHeating = snapshots.filter { s ->
            s.outdoorTempC != null &&
                s.supplyTempC != null &&
                s.returnTempC != null &&
                s.modulationPct != null &&
                s.modulationPct > 5.0 &&
                (s.isHeatingActive || s.chStatus?.equals("on", ignoreCase = true) == true)
        }.sortedBy { it.epochSeconds }

        if (allHeating.size < minHeatingSamples) {
            return null
        }

        // Apply setpoint consistency filter if a target is determined
        val heatingSnapshots = if (effectiveTarget != null) {
            val matching = allHeating.filter { s ->
                s.setpointTempC == null || kotlin.math.abs(s.setpointTempC - effectiveTarget) <= setpointToleranceK
            }
            if (matching.size >= minHeatingSamples) matching else allHeating
        } else {
            allHeating
        }

        if (heatingSnapshots.size < minHeatingSamples) {
            return null
        }

        // Segment into contiguous heating runs
        val runs = mutableListOf<MutableList<HeatPumpTelemetrySnapshot>>()
        var currentRun = mutableListOf<HeatPumpTelemetrySnapshot>()

        for (snap in heatingSnapshots) {
            if (currentRun.isEmpty()) {
                currentRun.add(snap)
            } else {
                val gap = snap.epochSeconds - currentRun.last().epochSeconds
                if (gap > runGapThresholdSeconds) {
                    runs.add(currentRun)
                    currentRun = mutableListOf(snap)
                } else {
                    currentRun.add(snap)
                }
            }
        }
        if (currentRun.isNotEmpty()) {
            runs.add(currentRun)
        }

        val steadyStateSamples = mutableListOf<HeatPumpTelemetrySnapshot>()
        val warmupSamples = mutableListOf<HeatPumpTelemetrySnapshot>()

        for (run in runs) {
            val runStartEpoch = run.first().epochSeconds
            for (snap in run) {
                val elapsedMin = (snap.epochSeconds - runStartEpoch) / 60.0
                if (elapsedMin >= warmupThresholdMinutes) {
                    steadyStateSamples.add(snap)
                } else {
                    warmupSamples.add(snap)
                }
            }
        }

        // Prefer steady-state points; fall back to all points if steady-state is scarce
        val fittingPoints = if (steadyStateSamples.size >= minHeatingSamples) steadyStateSamples else heatingSnapshots
        val n = fittingPoints.size

        var sumT = 0.0
        var sumMod = 0.0
        var sumTMod = 0.0
        var sumTT = 0.0
        var totalDeltaT = 0.0

        for (p in fittingPoints) {
            val tOut = p.outdoorTempC!!
            val mod = p.modulationPct!!
            val dt = (p.supplyTempC ?: 0.0) - (p.returnTempC ?: 0.0)

            sumT += tOut
            sumMod += mod
            sumTMod += tOut * mod
            sumTT += tOut * tOut
            totalDeltaT += max(0.0, dt)
        }

        val denom = n * sumTT - sumT * sumT
        val slope: Double
        val intercept: Double

        if (kotlin.math.abs(denom) > 1e-4) {
            slope = (n * sumTMod - sumT * sumMod) / denom
            intercept = (sumMod - slope * sumT) / n
        } else {
            // Outdoor temperature variation was near zero; use average flat modulation
            slope = -5.0 // standard physical heuristic: ~5% increase per 1°C colder
            val avgM = sumMod / n
            val avgT = sumT / n
            intercept = avgM - slope * avgT
        }

        // Clamp slope to physically realistic inverter behavior (-1.0 to -12.0 %/K)
        val clampedSlope = slope.coerceIn(-15.0, -1.0)
        val rawModAt10C = (clampedSlope * 10.0 + intercept).coerceIn(20.0, 95.0)
        val rawSlopePerK = -clampedSlope

        // Incorporate 25k Historical Anchor (Prior Baseline) if provided
        val hasPrior = priorModulationAt10C != null && priorModulationSlopePerK != null && priorWeight > 0.0
        val effectiveModAt10C: Double
        val effectiveSlopePerK: Double

        if (hasPrior) {
            val wPrior = priorWeight
            val wNew = n
            val wTotal = wPrior + wNew
            effectiveModAt10C = ((priorModulationAt10C!! * wPrior + rawModAt10C * wNew) / wTotal).coerceIn(20.0, 95.0)
            effectiveSlopePerK = ((priorModulationSlopePerK!! * wPrior + rawSlopePerK * wNew) / wTotal).coerceIn(1.0, 15.0)
        } else {
            effectiveModAt10C = rawModAt10C
            effectiveSlopePerK = rawSlopePerK
        }

        val modAt10C = effectiveModAt10C
        val slopePerK = effectiveSlopePerK

        // Calculate R^2 for modulation regression
        val yMean = sumMod / n
        var ssTot = 0.0
        var ssRes = 0.0
        for (p in fittingPoints) {
            val tOut = p.outdoorTempC!!
            val yActual = p.modulationPct!!
            val yPred = clampedSlope * tOut + intercept
            ssTot += (yActual - yMean) * (yActual - yMean)
            ssRes += (yActual - yPred) * (yActual - yPred)
        }
        val r2 = if (ssTot > 1e-4) (1.0 - (ssRes / ssTot)).coerceIn(0.0, 1.0) else 0.92

        val avgSteadyMod = fittingPoints.mapNotNull { it.modulationPct }.average()
        val avgSteadyOut = fittingPoints.mapNotNull { it.outdoorTempC }.average()
        val avgSteadyDelta = totalDeltaT / n

        val minOut = fittingPoints.mapNotNull { it.outdoorTempC }.minOrNull() ?: avgSteadyOut
        val maxOut = fittingPoints.mapNotNull { it.outdoorTempC }.maxOrNull() ?: avgSteadyOut

        val avgWarmupMod = if (warmupSamples.isNotEmpty()) {
            warmupSamples.mapNotNull { it.modulationPct }.average()
        } else avgSteadyMod

        val warmupOverhead = if (avgSteadyMod > 5.0) (avgWarmupMod / avgSteadyMod).coerceIn(1.0, 2.0) else 1.2
        val steadyDeltaLift = max(4.0, (effectiveTarget ?: 21.5) + 0.65 * (20.0 - avgSteadyOut) - avgSteadyOut)
        val steadyCop = (1.0 / (0.00542 * steadyDeltaLift + 0.060)).coerceIn(1.8, 8.5)
        val inferredPower = (((avgSteadyMod / 100.0) * 9.0) / steadyCop + 0.05).coerceIn(0.45, 2.40)

        val minMod = heatingSnapshots.mapNotNull { it.modulationPct }.minOrNull() ?: 20.0
        val maxMod = heatingSnapshots.mapNotNull { it.modulationPct }.maxOrNull() ?: 100.0

        val summary = String.format(
            java.util.Locale.US,
            "Analyzed %d heating points (%d steady-state across %d runs%s%s). Steady modulation ~%.1f%% at %.1f°C (ΔT=%.1fK, inferred power ~%.2f kW). Model: Mod(T)=%.1f%% + %.1f%%/K*(10°C - Tout).",
            heatingSnapshots.size,
            fittingPoints.size,
            runs.size,
            if (effectiveTarget != null) String.format(java.util.Locale.US, ", target %.1f°C", effectiveTarget) else "",
            if (hasPrior) String.format(java.util.Locale.US, ", with 25k historical anchor W=%.0f", priorWeight) else "",
            avgSteadyMod,
            avgSteadyOut,
            avgSteadyDelta,
            inferredPower,
            modAt10C,
            slopePerK
        )

        return EmpiricalModulationProfile(
            totalHeatingSamples = heatingSnapshots.size,
            steadyStateSamples = fittingPoints.size,
            detectedRunsCount = runs.size,
            avgSteadyModulationPct = avgSteadyMod,
            avgSteadyOutdoorTempC = avgSteadyOut,
            avgSteadyDeltaTC = avgSteadyDelta,
            inferredSteadyPowerKw = inferredPower,
            warmupModulationOverheadFactor = warmupOverhead,
            modulationAt10C = modAt10C,
            modulationSlopePerK = slopePerK,
            minObservedModulationPct = minMod,
            maxObservedModulationPct = maxMod,
            evaluationSummary = summary,
            matchedSetpointTempC = effectiveTarget,
            rSquared = r2,
            minObservedOutdoorTempC = minOut,
            maxObservedOutdoorTempC = maxOut,
            avgWarmupModulationPct = avgWarmupMod,
            warmupSamplesCount = warmupSamples.size,
            historicalPriorIncorporated = hasPrior,
            historicalPriorWeight = if (hasPrior) priorWeight else 0.0
        )
    }
}
