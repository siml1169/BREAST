package de.breast.telemetry

import de.breast.api.PointTApiClient
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/**
 * Persistence contract for adaptive electrical power parameters.
 */
interface PowerParametersStore {
    var estimatedPowerKw: Double
    var learnedPowerCycleCount: Int
    var lastLearnedPowerDate: String?
    val parasiticBaselinePowerKw: Double
}

/**
 * Result of an online electrical power observation and calibration step.
 */
data class PowerObservationResult(
    val date: String,
    val activeHeatingHours: Double,
    val recordedEnergyKwh: Double,
    val averagePowerKw: Double,
    val averageModulationPct: Double,
    val inferredReferencePowerKw: Double,
    val previousEstimatedPowerKw: Double,
    val updatedEstimatedPowerKw: Double,
    val cycleCount: Int,
    val isUpdated: Boolean,
    val logMessage: String
)

/**
 * Online adaptive power draw observer.
 *
 * Continuously learns the true heat pump electrical power characteristics by correlating
 * the recorded electrical energy consumption (E) over active runtime duration (Δt)
 * with the time-weighted compressor modulation percentage (M).
 *
 * Physics principle:
 *   Average Electrical Power: P_meas = E_rec / Δt_active
 *
 * Inverter power relationship:
 *   P_elec(M) = P_parasitic + P_compRef * (0.35 + 0.65 * (M / 50.0))
 *   P_compRef_obs = (P_meas - P_parasitic) / (0.35 + 0.65 * (M / 50.0))
 *
 * Parameter refinement via recursive Exponential Moving Average (EMA):
 *   P_compRef_(k+1) = (1 - α) * P_compRef_(k) + α * P_compRef_obs
 */
class AdaptivePowerObserver(
    val learningRateEma: Double = 0.15,
    val minActiveHours: Double = 0.75,
    val minRecordedEnergyKwh: Double = 0.20,
    val minAverageModulationPct: Double = 15.0,
    val minAllowedPowerKw: Double = 0.35,
    val maxAllowedPowerKw: Double = 2.40
) {
    /**
     * Ingests a completed space heating cycle with measured energy, duration, and modulation,
     * updating the calibrated reference electrical power via recursive EMA.
     */
    fun observeCycle(
        date: String,
        activeHeatingHours: Double,
        recordedEnergyKwh: Double,
        averageModulationPct: Double,
        store: PowerParametersStore,
        applyToStore: Boolean = true
    ): PowerObservationResult? {
        if (activeHeatingHours < minActiveHours || recordedEnergyKwh < minRecordedEnergyKwh || averageModulationPct < minAverageModulationPct) {
            return null
        }

        val measuredAvgPowerKw = recordedEnergyKwh / activeHeatingHours
        val parasitic = store.parasiticBaselinePowerKw.coerceIn(0.010, 0.150)

        // Invert our physical inverter power model:
        // P_elec(M) = P_parasitic + P_compRef * (0.35 + 0.65 * (M / 50.0))
        val modFactor = max(0.35, 0.35 + 0.65 * (averageModulationPct / 50.0))
        val measuredCompPowerKw = max(0.10, measuredAvgPowerKw - parasitic)
        val inferredCompRefKw = (measuredCompPowerKw / modFactor).coerceIn(0.20, 2.00)
        val inferredTotalRefKw = inferredCompRefKw + parasitic

        val prevPower = store.estimatedPowerKw
        val prevCompRef = max(0.20, prevPower - parasitic)

        // Recursive Exponential Moving Average (EMA) update
        val newCompRef = (1.0 - learningRateEma) * prevCompRef + learningRateEma * inferredCompRefKw
        val newTotalPower = (newCompRef + parasitic).coerceIn(minAllowedPowerKw, maxAllowedPowerKw)
        val isAlreadyIncorporated = (store.lastLearnedPowerDate == date)
        val targetCycleCount = if (isAlreadyIncorporated) store.learnedPowerCycleCount else store.learnedPowerCycleCount + 1

        if (applyToStore && !isAlreadyIncorporated) {
            store.estimatedPowerKw = newTotalPower
            store.learnedPowerCycleCount = targetCycleCount
            store.lastLearnedPowerDate = date
        }

        val logMsg = String.format(
            java.util.Locale.US,
            "Adaptive Power Calibration (%s): E=%.2f kWh, t=%.1fh -> P_meas=%.2f kW @ %.0f%% mod. Inferred P_ref=%.2f kW. Updated estimatedPowerKw: %.2f -> %.2f kW (EMA α=%.2f, cycle #%d)",
            date,
            recordedEnergyKwh,
            activeHeatingHours,
            measuredAvgPowerKw,
            averageModulationPct,
            inferredTotalRefKw,
            prevPower,
            if (isAlreadyIncorporated && !applyToStore) prevPower else newTotalPower,
            learningRateEma,
            targetCycleCount
        )

        return PowerObservationResult(
            date = date,
            activeHeatingHours = activeHeatingHours,
            recordedEnergyKwh = recordedEnergyKwh,
            averagePowerKw = measuredAvgPowerKw,
            averageModulationPct = averageModulationPct,
            inferredReferencePowerKw = inferredTotalRefKw,
            previousEstimatedPowerKw = prevPower,
            updatedEstimatedPowerKw = newTotalPower,
            cycleCount = targetCycleCount,
            isUpdated = !isAlreadyIncorporated && applyToStore,
            logMessage = logMsg
        )
    }

    /**
     * Inspects historical telemetry snapshots and queries the Bosch recording API
     * (/recordings/heatSources/emon/ch/compressor) to perform online calibration for a given date.
     */
    suspend fun performOnlineCalibration(
        targetDate: LocalDate,
        snapshots: List<HeatPumpTelemetrySnapshot>,
        pointTClient: PointTApiClient? = null,
        gatewayId: String? = null,
        store: PowerParametersStore,
        applyToStore: Boolean = true
    ): PowerObservationResult? {
        val dateStr = targetDate.toString()
        if (store.lastLearnedPowerDate == dateStr && applyToStore) {
            return null
        }

        // 1. Fetch compressor electrical energy recording for target date
        val totalEnergyKwh = if (pointTClient != null && !gatewayId.isNullOrBlank()) {
            val recordingNode = runCatching {
                pointTClient.fetchRecording(
                    gatewayId = gatewayId,
                    resourcePath = "/recordings/heatSources/emon/ch/compressor",
                    interval = dateStr
                )
            }.getOrNull()
            recordingNode?.recording?.mapNotNull { it.y }?.sum() ?: 0.0
        } else {
            0.0
        }

        // 2. Filter telemetry snapshots for space heating during target date
        val dateSnaps = snapshots.filter { s ->
            s.timestampIso.startsWith(dateStr) &&
                s.modulationPct != null &&
                s.modulationPct > 5.0 &&
                s.isHeatingActive &&
                !s.isDhwActive
        }.sortedBy { it.epochSeconds }

        if (dateSnaps.isEmpty()) return null

        val avgMod = dateSnaps.mapNotNull { it.modulationPct }.average()

        // Calculate active heating duration
        var activeSeconds = 0L
        for (i in dateSnaps.indices) {
            val prevEpoch = if (i > 0) dateSnaps[i - 1].epochSeconds else null
            val currEpoch = dateSnaps[i].epochSeconds
            if (prevEpoch != null && (currEpoch - prevEpoch) in 60..1800) {
                activeSeconds += (currEpoch - prevEpoch)
            } else {
                activeSeconds += 900L // 15 min default for isolated or boundary sample
            }
        }
        val activeHours = max(0.5, activeSeconds / 3600.0)

        val effectiveEnergyKwh = if (totalEnergyKwh >= minRecordedEnergyKwh) {
            totalEnergyKwh
        } else {
            // If eMon is not available (e.g. gateway offline / mock), derive an initial
            // observation from active runtime using current estimated power scaled to modulation
            val modFactor = max(0.35, 0.35 + 0.65 * (avgMod / 50.0))
            (store.estimatedPowerKw * modFactor * activeHours).coerceAtLeast(minRecordedEnergyKwh)
        }

        return observeCycle(
            date = dateStr,
            activeHeatingHours = activeHours,
            recordedEnergyKwh = effectiveEnergyKwh,
            averageModulationPct = avgMod,
            store = store,
            applyToStore = applyToStore
        )
    }
}
