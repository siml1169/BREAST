package de.breast.telemetry

import java.time.Instant

data class AdaptiveThermalObservationResult(
    val updatedBuildingLossKwPerK: Double,
    val updatedCoolingRateKPerHour: Double,
    val observedLossKwPerK: Double,
    val observedCoolingRateKPerHour: Double,
    val rSquared: Double,
    val blockStartIso: String,
    val blockEndIso: String,
    val cycleCount: Int,
    val isUpdated: Boolean,
    val logMessage: String
)

interface ThermalParametersStore {
    var learnedBuildingLossKwPerK: Double
    var learnedCoolingRateKPerHour: Double
    var learnedCycleCount: Int
    var lastLearnedTimestampIso: String?
    val baseBuildingLossKwPerK: Double
    val sliderTempC: Double
}

/**
 * Online adaptive thermal state observer.
 *
 * Continuously monitors post-window nocturnal return temperatures (screed bulk temperature)
 * and applies recursive parameter estimation (Exponential Moving Average) to refine
 * the building heat loss coefficient (Hbld) and screed cooldown rate (κ_cool).
 */
class AdaptiveThermalObserver(
    val floorScreedCapacityKwhPerK: Double = 5.0,
    val indoorCapacityKwhPerK: Double = 8.5,
    val learningRateEma: Double = 0.15,
    val minRSquared: Double = 0.65,
    val minDurationHours: Double = 1.5,
    val latitude: Double = 52.5200,
    val longitude: Double = 13.4050
) {
    /**
     * Inspects recent telemetry snapshots during nocturnal setback and performs
     * recursive parameter estimation on Hbld (building heat loss coefficient)
     * and screed cooling rate.
     */
    fun performOnlineCalibration(
        snapshots: List<HeatPumpTelemetrySnapshot>,
        settingsStore: ThermalParametersStore
    ): AdaptiveThermalObservationResult? {
        if (snapshots.size < 5) return null

        val estimator = SetbackHeatLossEstimator(
            floorScreedCapacityKwhPerK = floorScreedCapacityKwhPerK,
            indoorCapacityKwhPerK = indoorCapacityKwhPerK,
            defaultIndoorTempC = settingsStore.sliderTempC,
            targetSetpointTempC = settingsStore.sliderTempC,
            minDurationHours = minDurationHours,
            latitude = latitude,
            longitude = longitude,
            minRSquared = minRSquared
        )

        val result = estimator.estimateBuildingHeatLoss(snapshots) ?: return null

        val lastLearnedIso = settingsStore.lastLearnedTimestampIso
        val blockEndIso = result.timestampEndIso

        // If this nocturnal setback block has already been incorporated, do not double-count
        val isAlreadyIncorporated = if (lastLearnedIso == null) {
            false
        } else {
            runCatching {
                Instant.parse(blockEndIso) <= Instant.parse(lastLearnedIso)
            }.getOrDefault(blockEndIso <= lastLearnedIso)
        }

        if (isAlreadyIncorporated) {
            return AdaptiveThermalObservationResult(
                updatedBuildingLossKwPerK = settingsStore.learnedBuildingLossKwPerK,
                updatedCoolingRateKPerHour = settingsStore.learnedCoolingRateKPerHour,
                observedLossKwPerK = result.estimatedLossKwPerK,
                observedCoolingRateKPerHour = result.coolingRateKPerHour,
                rSquared = result.rSquared,
                blockStartIso = result.timestampStartIso,
                blockEndIso = blockEndIso,
                cycleCount = settingsStore.learnedCycleCount,
                isUpdated = false,
                logMessage = "Nocturnal block ending at $blockEndIso already incorporated into thermal model."
            )
        }

        // Apply Exponential Moving Average (EMA) parameter update
        val currentLoss = if (settingsStore.learnedCycleCount > 0) {
            settingsStore.learnedBuildingLossKwPerK
        } else {
            settingsStore.baseBuildingLossKwPerK
        }
        val currentCoolingRate = settingsStore.learnedCoolingRateKPerHour

        val obsLoss = result.estimatedLossKwPerK.coerceIn(0.060, 0.350)
        val obsRate = result.coolingRateKPerHour.coerceIn(0.04, 0.40)

        val newLoss = (1.0 - learningRateEma) * currentLoss + learningRateEma * obsLoss
        val newRate = (1.0 - learningRateEma) * currentCoolingRate + learningRateEma * obsRate
        val newCycleCount = settingsStore.learnedCycleCount + 1

        settingsStore.learnedBuildingLossKwPerK = newLoss
        settingsStore.learnedCoolingRateKPerHour = newRate
        settingsStore.learnedCycleCount = newCycleCount
        settingsStore.lastLearnedTimestampIso = blockEndIso

        val msg = String.format(
            java.util.Locale.US,
            "🔬 Adaptive Thermal Observer calibrated: Hbld = %.3f kW/K (obs: %.3f, R²: %.2f), Cooldown = %.2f K/h (Cycle #%d)",
            newLoss, obsLoss, result.rSquared, newRate, newCycleCount
        )

        return AdaptiveThermalObservationResult(
            updatedBuildingLossKwPerK = newLoss,
            updatedCoolingRateKPerHour = newRate,
            observedLossKwPerK = obsLoss,
            observedCoolingRateKPerHour = obsRate,
            rSquared = result.rSquared,
            blockStartIso = result.timestampStartIso,
            blockEndIso = blockEndIso,
            cycleCount = newCycleCount,
            isUpdated = true,
            logMessage = msg
        )
    }
}
