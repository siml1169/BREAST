package de.breast.telemetry

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

data class EmpiricalCurveResult(
    val sampleCount: Int,
    val empiricalSlope: Double,
    val baseTempAt20C: Double,
    val flowTempAt0C: Double,
    val designTempAtMinus10C: Double,
    val rSquared: Double,
    val avgHeatingDeltaT: Double,
    val hourlyCoolingRateC: Double?,
    val evaluationSummary: String,
    val steadyStateSampleCount: Int = sampleCount,
    val transientSampleCount: Int = 0,
    val estimatedWarmupMinutes: Double? = null
)

data class HourlyTelemetryProfile(
    val hourOfDay: Int,
    val avgOutdoorTempC: Double?,
    val avgSupplyTempC: Double?,
    val avgReturnTempC: Double?,
    val avgDeltaTC: Double?,
    val avgModulationPct: Double?,
    val heatingActivePct: Double,
    val sampleCount: Int
)

class HeatingCurveAnalyzer {

    companion object {
        /** Minimum elapsed heating runtime in minutes before heat pump loop and buffer tank reach thermal steady-state */
        const val WARMUP_THRESHOLD_MINUTES = 20.0
    }

    data class ClassifiedSnapshot(
        val snapshot: HeatPumpTelemetrySnapshot,
        val isSteadyState: Boolean,
        val runElapsedMinutes: Double
    )

    fun classifySnapshots(snapshots: List<HeatPumpTelemetrySnapshot>): List<ClassifiedSnapshot> {
        val sorted = snapshots.sortedBy { it.epochSeconds }
        val result = mutableListOf<ClassifiedSnapshot>()
        var runStartEpoch: Long? = null
        var lastHeatingEpoch: Long? = null

        for (snap in sorted) {
            if (snap.isHeatingActive) {
                if (runStartEpoch == null || (lastHeatingEpoch != null && snap.epochSeconds - lastHeatingEpoch > 1800L)) {
                    runStartEpoch = snap.epochSeconds
                }
                lastHeatingEpoch = snap.epochSeconds
                val elapsedMinutes = (snap.epochSeconds - runStartEpoch) / 60.0
                val isSteady = elapsedMinutes >= WARMUP_THRESHOLD_MINUTES
                result.add(ClassifiedSnapshot(snap, isSteady, elapsedMinutes))
            } else {
                runStartEpoch = null
                lastHeatingEpoch = null
                result.add(ClassifiedSnapshot(snap, isSteadyState = false, runElapsedMinutes = 0.0))
            }
        }
        return result
    }

    fun analyzeHeatingCurve(
        snapshots: List<HeatPumpTelemetrySnapshot>,
        targetSetpointTempC: Double? = null,
        setpointToleranceK: Double = 1.0,
        latitude: Double = 52.5200,
        longitude: Double = 13.4050
    ): EmpiricalCurveResult? {
        val classified = classifySnapshots(snapshots)
        val validHeating = classified.filter { c ->
            val s = c.snapshot
            s.outdoorTempC != null &&
                s.supplyTempC != null &&
                (s.outdoorTempC in -30.0..35.0) &&
                (s.supplyTempC in 18.0..55.0) &&
                (targetSetpointTempC == null || s.setpointTempC == null || kotlin.math.abs(s.setpointTempC - targetSetpointTempC) <= setpointToleranceK)
        }

        if (validHeating.size < 5) {
            return null
        }

        val steadyState = validHeating.filter { it.isSteadyState }
        // If we have at least 5 steady-state points, use purely steady-state to eliminate warm-up skew!
        // Otherwise fall back to valid heating points.
        val pointsToFit = if (steadyState.size >= 5) steadyState.map { it.snapshot } else validHeating.map { it.snapshot }
        val transientCount = validHeating.size - steadyState.size

        val n = pointsToFit.size
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumX2 = 0.0
        var sumY2 = 0.0
        var totalDeltaT = 0.0
        var deltaTCount = 0

        for (p in pointsToFit) {
            val x = p.outdoorTempC!!
            val y = p.supplyTempC!!
            sumX += x
            sumY += y
            sumXY += x * y
            sumX2 += x * x
            sumY2 += y * y

            p.deltaTempC?.let {
                totalDeltaT += it
                deltaTCount++
            }
        }

        val denominator = (n * sumX2 - sumX * sumX)
        if (abs(denominator) < 1e-6) {
            return null
        }

        val m = (n * sumXY - sumX * sumY) / denominator
        val b = (sumY - m * sumX) / n

        // R^2 calculation
        val yMean = sumY / n
        var ssTot = 0.0
        var ssRes = 0.0
        for (p in pointsToFit) {
            val x = p.outdoorTempC!!
            val y = p.supplyTempC!!
            val yPred = m * x + b
            ssTot += (y - yMean).pow(2)
            ssRes += (y - yPred).pow(2)
        }
        val r2 = if (ssTot > 1e-6) (1.0 - (ssRes / ssTot)).coerceIn(0.0, 1.0) else 0.0

        // In standard German heating curve terminology:
        // Steigung = -(T_flow(-10) - T_flow(20)) / 30 = -m
        val empiricalSlope = -m
        val flowAt20 = m * 20.0 + b
        val flowAt0 = b
        val flowAtMinus10 = m * (-10.0) + b
        val avgDeltaT = if (deltaTCount > 0) totalDeltaT / deltaTCount else 0.0

        val coolingRate = estimateHourlyCoolingRate(snapshots, targetSetpointTempC, latitude, longitude)
        val warmupEstimate = estimateWarmupMinutes(classified)

        val evaluation = when {
            empiricalSlope in 0.35..0.55 ->
                "Optimal Bosch underfloor curve (Slope %.2f, Flow @0°C: %.1f°C). Very good heat pump efficiency.".format(empiricalSlope, flowAt0)
            empiricalSlope < 0.35 ->
                "Flatter curve (Slope %.2f). High thermal efficiency, well-insulated building.".format(empiricalSlope)
            else ->
                "Steeper curve (Slope %.2f, Flow @-10°C: %.1f°C). Consider lowering heating curve slope to boost seasonal COP.".format(empiricalSlope, flowAtMinus10)
        }

        return EmpiricalCurveResult(
            sampleCount = validHeating.size,
            empiricalSlope = empiricalSlope,
            baseTempAt20C = flowAt20,
            flowTempAt0C = flowAt0,
            designTempAtMinus10C = flowAtMinus10,
            rSquared = r2,
            avgHeatingDeltaT = avgDeltaT,
            hourlyCoolingRateC = coolingRate,
            evaluationSummary = evaluation,
            steadyStateSampleCount = steadyState.size,
            transientSampleCount = transientCount,
            estimatedWarmupMinutes = warmupEstimate
        )
    }

    private fun estimateWarmupMinutes(classified: List<ClassifiedSnapshot>): Double? {
        val warmupRuns = mutableListOf<Double>()
        var currentRunTransients = mutableListOf<ClassifiedSnapshot>()
        var reachedSteady = false

        for (c in classified) {
            if (c.snapshot.isHeatingActive) {
                if (!c.isSteadyState) {
                    currentRunTransients.add(c)
                } else if (!reachedSteady) {
                    reachedSteady = true
                    val rampTime = currentRunTransients.lastOrNull()?.runElapsedMinutes ?: WARMUP_THRESHOLD_MINUTES
                    warmupRuns.add(rampTime)
                }
            } else {
                currentRunTransients.clear()
                reachedSteady = false
            }
        }

        return if (warmupRuns.isNotEmpty()) warmupRuns.average() else null
    }

    fun build24hHourlyProfile(
        snapshots: List<HeatPumpTelemetrySnapshot>
    ): List<HourlyTelemetryProfile> {
        val hourlyBuckets = Array(24) { mutableListOf<HeatPumpTelemetrySnapshot>() }

        for (snap in snapshots) {
            val hour = try {
                java.time.ZonedDateTime.parse(snap.timestampIso).hour
            } catch (e: Exception) {
                ((snap.epochSeconds % 86400L) / 3600L).toInt().coerceIn(0, 23)
            }
            hourlyBuckets[hour].add(snap)
        }

        return (0 until 24).map { h ->
            val list = hourlyBuckets[h]
            if (list.isEmpty()) {
                HourlyTelemetryProfile(
                    hourOfDay = h,
                    avgOutdoorTempC = null,
                    avgSupplyTempC = null,
                    avgReturnTempC = null,
                    avgDeltaTC = null,
                    avgModulationPct = null,
                    heatingActivePct = 0.0,
                    sampleCount = 0
                )
            } else {
                val outdoorAvg = list.mapNotNull { it.outdoorTempC }.averageOrNull()
                val supplyAvg = list.mapNotNull { it.supplyTempC }.averageOrNull()
                val returnAvg = list.mapNotNull { it.returnTempC }.averageOrNull()
                val deltaTAvg = list.mapNotNull { it.deltaTempC }.averageOrNull()
                val modAvg = list.mapNotNull { it.modulationPct }.averageOrNull()
                val activeCount = list.count { it.isHeatingActive }
                val activePct = (activeCount.toDouble() / list.size) * 100.0

                HourlyTelemetryProfile(
                    hourOfDay = h,
                    avgOutdoorTempC = outdoorAvg,
                    avgSupplyTempC = supplyAvg,
                    avgReturnTempC = returnAvg,
                    avgDeltaTC = deltaTAvg,
                    avgModulationPct = modAvg,
                    heatingActivePct = activePct,
                    sampleCount = list.size
                )
            }
        }
    }

    private fun estimateHourlyCoolingRate(
        snapshots: List<HeatPumpTelemetrySnapshot>,
        targetSetpointTempC: Double?,
        latitude: Double,
        longitude: Double
    ): Double? {
        val estimator = SetbackHeatLossEstimator(
            defaultIndoorTempC = targetSetpointTempC ?: 21.5,
            targetSetpointTempC = targetSetpointTempC,
            latitude = latitude,
            longitude = longitude,
            minDurationHours = 1.0,
            enforceNocturnalOnly = true
        )
        val setback = estimator.estimateBuildingHeatLoss(snapshots)
        return setback?.coolingRateKPerHour
    }

    private fun List<Double>.averageOrNull(): Double? =
        if (isEmpty()) null else average()
}
