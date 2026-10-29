package de.breast.optimizer

import kotlin.math.max
import kotlin.math.min

/**
 * Thermodynamic COP and heat cost modeling for air-to-water heat pump systems.
 *
 * Implements Carnot efficiency with compressor inverter degradation, calibrated
 * against Bosch HPC 410 flow temperature curves.
 */
class CopThermodynamicModel(
    val carnotEfficiency: Double = 0.46,
    val minCop: Double = 1.6,
    val maxCop: Double = 6.5
) {
    /**
     * Calculates the expected COP for a given ambient outdoor temperature
     * and heating flow supply temperature.
     */
    fun calculateCop(outdoorTempC: Double, supplyTempC: Double): Double {
        val tSourceK = outdoorTempC + 273.15
        val tSinkK = max(supplyTempC, outdoorTempC + 1.0) + 273.15
        val deltaT = max(tSinkK - tSourceK, 4.0)

        val carnotCop = tSinkK / deltaT
        val actualCop = carnotCop * carnotEfficiency
        return actualCop.coerceIn(minCop, maxCop)
    }

    /**
     * Estimates supply temperature for a target room setpoint and outdoor temperature
     * using the Bosch HPC 410 heating curve formula:
     * T_supply = T_target + s * (20 - T_outdoor) + parallelShift
     */
    fun estimateSupplyTempC(
        outdoorTempC: Double,
        targetRoomTempC: Double = 21.5,
        curveSlope: Double = 0.75, // typical FBH slope: ~35°C at -10°C, 20°C at 20°C
        parallelShiftK: Double = 0.0
    ): Double {
        val baseFlow = targetRoomTempC + curveSlope * (20.0 - outdoorTempC) + parallelShiftK
        return baseFlow.coerceIn(20.0, 65.0)
    }

    /**
     * Computes the effective delivered thermal cost in ct/kWh_th from electrical price:
     * Cost_th = Cost_elec / COP
     */
    fun computeDeliveredHeatCostCtPerKwh(
        electricalGrossCtPerKwh: Double,
        cop: Double
    ): Double {
        val safeCop = max(cop, 1.0)
        return electricalGrossCtPerKwh / safeCop
    }

    /**
     * Computes the thermodynamic diurnal COP alignment bonus relative to ambient temperature variations.
     * Peak solar noon (11:00 - 15:00) gives maximum Carnot efficiency.
     */
    fun computeMiddayBonusMultiplier(hourOfDay: Int): Double {
        val peakHour = 13.0
        val sigma = 2.5
        val diff = hourOfDay - peakHour
        val factor = Math.exp(-(diff * diff) / (2.0 * sigma * sigma))
        return 1.0 + 0.35 * factor
    }
}
