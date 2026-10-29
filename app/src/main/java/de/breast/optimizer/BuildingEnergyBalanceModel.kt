package de.breast.optimizer

import kotlin.math.max
import kotlin.math.min

/**
 * Building thermal energy balance and underfloor heating screed thermal mass model.
 *
 * Replaces fixed time budgets (e.g. strict 8h limits) with a dynamic physical energy demand
 * based on heat transfer coefficients, ambient temperature, and screed buffering.
 */
class BuildingEnergyBalanceModel(
    /**
     * Overall building heat loss coefficient in kW/K (e.g. 0.150 kW/K = 150 W/K).
     * For ΔT = 15K (e.g. 21°C inside, 6°C outside), heat loss is 0.150 * 15 = 2.25 kW.
     */
    val buildingLossCoefficientKwPerK: Double = 0.150,

    /**
     * Average thermal output capacity of the heat pump in kW (e.g. 4.5 kW for Bosch 5-7 kW unit at nominal speed).
     */
    val heatPumpNominalThermalPowerKw: Double = 4.5,

    /**
     * Thermal capacity of the radiant underfloor heating screed in kWh/K.
     * For 120 m² floor with 7cm screed, C ~ 5.0 kWh/K.
     */
    val floorScreedThermalCapacityKwhPerK: Double = 5.0,

    /**
     * Maximum allowed pre-heat temperature elevation in Kelvin above comfort setpoint
     * during 0-cent / negative price or peak-COP solar hours.
     */
    val maxThermalBufferingK: Double = 1.2,

    /**
     * Baseline domestic internal heat gains in kW (appliances, occupants, diffuse base solar).
     */
    val baselineInternalGainsKw: Double = 0.0
) {
    /**
     * Calculates the instantaneous thermal heat loss rate of the building in kW.
     */
    fun calculateLossRateKw(
        indoorTempC: Double,
        outdoorTempC: Double
    ): Double {
        val deltaT = max(0.0, indoorTempC - outdoorTempC)
        return deltaT * buildingLossCoefficientKwPerK
    }

    /**
     * Calculates the total required thermal energy in kWh for a 24-hour day
     * given an array of 24 hourly outdoor temperatures, accounting for scheduled comfort vs. night setback
     * using the 2R2C State-Space mass coupling (daytime active loss net of internal gains + nocturnal reheat recovery).
     */
    fun calculateDailyThermalEnergyNeedKwh(
        targetRoomTempC: Double,
        hourlyOutdoorTempsC: List<Double>,
        hourlySolarGainsKw: List<Double>? = null,
        scheduledComfortHours: Double? = null,
        setbackRoomTempC: Double = 17.0,
        internalGainsKw: Double = baselineInternalGainsKw,
        coolingRateKPerHour: Double = 0.20,
        liveStartingReturnTempC: Double? = null,
        targetComfortReturnTempC: Double? = null
    ): Double {
        val count = min(24, hourlyOutdoorTempsC.size)
        if (count == 0) return 0.0

        return if (scheduledComfortHours != null && scheduledComfortHours < 24.0) {
            // 2R2C State-Space Dynamic Model:
            // 1. Daytime Active Comfort Heat Loss (net of passive solar & internal gains)
            val comfortHours = scheduledComfortHours.coerceIn(1.0, 24.0)
            val avgOutdoorT = hourlyOutdoorTempsC.take(count).average()
            val daytimeLossRateKw = max(0.0, calculateLossRateKw(targetRoomTempC, avgOutdoorT) - internalGainsKw)
            val daytimeThermalNeedKwh = daytimeLossRateKw * comfortHours

            // 2. Nocturnal Setback Reheat Recovery:
            // If live starting return temperature is measured, calculate the exact real deficit to target comfort return.
            // Otherwise, project decay using the learned nocturnal cooling rate.
            val screedTempDropK = if (liveStartingReturnTempC != null && targetComfortReturnTempC != null) {
                max(0.0, targetComfortReturnTempC - liveStartingReturnTempC).coerceIn(0.0, 2.5)
            } else {
                val offHours = 24.0 - comfortHours
                min(1.5, offHours * coolingRateKPerHour * 0.35)
            }
            val recoveryThermalNeedKwh = floorScreedThermalCapacityKwhPerK * screedTempDropK

            daytimeThermalNeedKwh + recoveryThermalNeedKwh
        } else {
            // Unconstrained 24h continuous baseline (used when scheduledComfortHours == null)
            var totalKwh = 0.0
            for (h in 0 until count) {
                val lossKw = calculateLossRateKw(targetRoomTempC, hourlyOutdoorTempsC[h])
                val solarKw = hourlySolarGainsKw?.getOrNull(h) ?: 0.0
                val netLossKw = max(0.0, lossKw - solarKw - internalGainsKw)
                totalKwh += netLossKw * 1.0 // 1 hour
            }
            totalKwh
        }
    }

    /**
     * Computes the dynamically required nominal operating duration in hours
     * to satisfy the building's thermal heat loss for the day.
     */
    fun calculateNominalRequiredHours(
        dailyThermalEnergyNeedKwh: Double
    ): Double {
        if (heatPumpNominalThermalPowerKw <= 0.0) return 6.0
        val rawHours = dailyThermalEnergyNeedKwh / heatPumpNominalThermalPowerKw
        // Bound between 1.0h (mild autumn/spring) and 18.0h (deep sub-zero winter)
        return rawHours.coerceIn(1.0, 18.0)
    }

    /**
     * Computes maximum allowed operating hours when cheap / negative prices exist,
     * allowing the radiant floor to be thermally pre-charged up to maxThermalBufferingK.
     */
    fun calculateMaxBufferOperatingHours(
        nominalHours: Double,
        hasZeroOrNegativePrices: Boolean
    ): Double {
        if (!hasZeroOrNegativePrices) return nominalHours

        val maxBufferEnergyKwh = floorScreedThermalCapacityKwhPerK * maxThermalBufferingK
        val extraBufferHours = maxBufferEnergyKwh / heatPumpNominalThermalPowerKw
        // Allow expanding nominal runtime by up to ~extraBufferHours
        return min(24.0, nominalHours + extraBufferHours)
    }
}
