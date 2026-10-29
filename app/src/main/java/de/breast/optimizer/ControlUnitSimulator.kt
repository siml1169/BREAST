package de.breast.optimizer

import de.breast.spot.SpotPriceSlot
import de.breast.weather.HourlyWeatherForecast
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/**
 * Result of forward-simulating a candidate schedule through the heat pump control unit state machine.
 */
data class ScheduleSimulationResult(
    val simulatedSlots: List<SimulatedSlotMetrics>,
    val totalElectricityKwh: Double,
    val totalCostEuro: Double,
    val windowElectricityKwh: Double = 0.0,
    val windowCostEuro: Double = 0.0,
    val activeCompressorMinutes: Int,
    val cycleStartsCount: Int,
    val averageActivePowerKw: Double,
    val averageModulationPct: Double,
    val minReturnTempC: Double,
    val maxReturnTempC: Double,
    val endReturnTempC: Double
)

data class SimulatedSlotMetrics(
    val index: Int,
    val startMinute: Int,
    val endMinute: Int,
    val isComfortSetpoint: Boolean,
    val outdoorTempC: Double,
    val targetReturnTempC: Double,
    val returnTempC: Double,
    val isCompressorActive: Boolean,
    val modulationPct: Double,
    val electricalPowerKw: Double,
    val electricityKwh: Double,
    val slotCostEuro: Double
)

/**
 * Digital Twin simulating the closed-loop operational logic of the Bosch Compress 7400i AW 9 / HPC 410 control unit.
 *
 * Reproduces the actual physical controller behavior:
 * 1. Weather-compensated heating curve target temperature T_target(T_out, setpoint)
 * 2. Return temperature hysteresis / degree-minute deficit switching logic
 * 3. Inverter PID compressor modulation vs. outdoor ambient temperature
 * 4. Hydronic radiant floor screed thermal mass and building heat loss ODE
 */
class ControlUnitSimulator(
    val buildingLossKwPerK: Double = 0.150,
    val screedThermalMassKwhPerK: Double = 5.0,
    val indoorThermalMassKwhPerK: Double = 8.5,
    val calibratedModAt10C: Double = 42.0,
    val calibratedModSlopePerK: Double = 5.0,
    val cutInHysteresisK: Double = 1.2,
    val cutOutHysteresisK: Double = 0.5,
    val heatingCurveSlope: Double = 0.65,
    val parallelShiftK: Double = 0.0,
    val comfortRoomTempC: Double = 21.5,
    val setbackRoomTempC: Double = 17.0,
    val nominalThermalKw: Double = 9.0,
    val circulationPumpKw: Double = 0.04,
    val standbyPowerKw: Double = 0.03,
    val parasiticBaselinePowerKw: Double = 0.050, // Generous 50W continuous baseline (secondary pump ~25W + electronics ~15W + primary pump)
    val baselineInternalGainsKw: Double = 0.35 // 350W domestic internal & solar heat gains
) {
    /**
     * Calculates the target return temperature according to the Bosch HPC 410 heating curve.
     * Flow temperature is determined by room setpoint, outdoor temperature, and curve slope.
     * In an underfloor heating system with steady water flow, target return is approx Flow - 4.5 K.
     */
    fun estimateTargetReturnTempC(outdoorTempC: Double, targetRoomTempC: Double): Double {
        val flowTemp = targetRoomTempC + heatingCurveSlope * (20.0 - outdoorTempC) + parallelShiftK
        return (flowTemp - 4.5).coerceIn(16.0, 45.0)
    }

    /**
     * Predicts steady-state compressor modulation percentage from outdoor ambient temperature.
     */
    fun predictModulationPct(outdoorTempC: Double): Double {
        val raw = calibratedModAt10C + calibratedModSlopePerK * (10.0 - outdoorTempC)
        return raw.coerceIn(20.0, 100.0)
    }

    /**
     * Converts modulation percentage to compressor electrical power draw in kW,
     * harmonized with dynamic thermodynamic reciprocal-lift COP.
     */
    fun predictElectricalPowerKw(modulationPct: Double, outdoorTempC: Double? = null): Double {
        if (modulationPct <= 0.0) return parasiticBaselinePowerKw
        val thermalKw = (modulationPct / 100.0) * nominalThermalKw
        val cop = if (outdoorTempC != null) {
            val flowTemp = comfortRoomTempC + heatingCurveSlope * (20.0 - outdoorTempC) + parallelShiftK
            val deltaLift = max(4.0, flowTemp - outdoorTempC)
            // Empirical reciprocal-lift formula: COP ~7.0 in mild autumn (16K lift) to COP ~4.1 in deep freeze (35K lift)
            (1.0 / (0.00542 * deltaLift + 0.060)).coerceIn(1.8, 8.5)
        } else {
            3.5
        }
        val compKw = thermalKw / cop
        return (compKw + parasiticBaselinePowerKw).coerceIn(0.45, 2.40)
    }

    /**
     * Runs forward closed-loop simulation across the 24-hour day for a specified comfort window.
     *
     * @param comfortStartMinute The minute of the day (0..1440) when comfort mode begins (e.g. 0 for 00:00).
     * @param comfortEndMinute The minute of the day when comfort mode ends (e.g. 435 for 07:15).
     * @param hourlyTemps 24 hourly outdoor temperatures from DWD ICON weather forecast.
     * @param slotGrossCtPerKwh Array of electricity prices for each raster slot (e.g. 96 slots).
     * @param rasterMinutes Duration of each slot in minutes (typically 15).
     * @param initialReturnTempC Starting screed return temperature at midnight (derived from recent telemetry or warm baseline).
     * @param targetDailyThermalEnergyNeedKwh Daily thermal energy requirement. When satisfied, compressor achieves equilibrium and shuts off.
     */
    fun simulateSchedule(
        comfortStartMinute: Int,
        comfortEndMinute: Int,
        hourlyTemps: DoubleArray,
        slotGrossCtPerKwh: DoubleArray,
        rasterMinutes: Int = 15,
        initialReturnTempC: Double? = null,
        targetDailyThermalEnergyNeedKwh: Double? = null
    ): ScheduleSimulationResult {
        val slotsPerDay = 1440 / rasterMinutes
        val rasterH = rasterMinutes / 60.0
        val simulatedSlots = ArrayList<SimulatedSlotMetrics>(slotsPerDay)

        val midnightOutdoorT = if (hourlyTemps.isNotEmpty()) hourlyTemps[0] else 8.0
        val targetComfortReturn = estimateTargetReturnTempC(midnightOutdoorT, comfortRoomTempC)
        val defaultInitialReturn = if (comfortStartMinute == 0) {
            targetComfortReturn - (cutInHysteresisK * 0.4)
        } else {
            estimateTargetReturnTempC(midnightOutdoorT, setbackRoomTempC)
        }
        var currentReturnT = initialReturnTempC ?: defaultInitialReturn
        var compressorActive = false
        var cycleStartsCount = 0
        var totalElectricityKwh = 0.0
        var totalCostEuro = 0.0
        var windowElectricityKwh = 0.0
        var windowCostEuro = 0.0
        var activeMinutes = 0
        var sumActiveMod = 0.0
        var sumActivePower = 0.0
        var activeSlotCount = 0
        var accumulatedThermalKwh = 0.0

        var minReturnT = currentReturnT
        var maxReturnT = currentReturnT

        for (i in 0 until slotsPerDay) {
            val startMin = i * rasterMinutes
            val endMin = startMin + rasterMinutes
            val hourOfDay = (startMin / 60).coerceIn(0, 23)
            val outdoorT = if (hourOfDay < hourlyTemps.size) hourlyTemps[hourOfDay] else 8.0
            val grossCt = if (i < slotGrossCtPerKwh.size) slotGrossCtPerKwh[i] else 30.0

            val isComfort = startMin >= comfortStartMinute && endMin <= comfortEndMinute
            val currentTargetRoom = if (isComfort) comfortRoomTempC else setbackRoomTempC
            val targetReturnT = estimateTargetReturnTempC(outdoorT, currentTargetRoom)

            val needMoreHeat = targetDailyThermalEnergyNeedKwh == null || accumulatedThermalKwh < targetDailyThermalEnergyNeedKwh
            val thermalSatisfied = targetDailyThermalEnergyNeedKwh != null && accumulatedThermalKwh >= targetDailyThermalEnergyNeedKwh

            // Closed-loop control unit hysteresis switching logic
            if (!compressorActive) {
                // Compressor starts when in comfort window and return temp cools below cut-in threshold
                // and daily thermal energy demand has not yet been satisfied
                if (isComfort && currentReturnT <= (targetReturnT - cutInHysteresisK) && needMoreHeat) {
                    compressorActive = true
                    cycleStartsCount++
                }
            } else {
                // Compressor stops when reaching target in setback, or reaching upper cut-out in comfort,
                // OR when the total daily thermal energy need has been completely fulfilled
                if (!isComfort && currentReturnT >= targetReturnT) {
                    compressorActive = false
                } else if (isComfort && (currentReturnT >= (targetReturnT + cutOutHysteresisK) || thermalSatisfied)) {
                    compressorActive = false
                }
            }

            // Inverter modulation & power computation
            val modPct: Double
            val thermalKw: Double
            val elecPowerKw: Double

            if (compressorActive) {
                modPct = predictModulationPct(outdoorT)
                thermalKw = (modPct / 100.0) * nominalThermalKw
                elecPowerKw = predictElectricalPowerKw(modPct, outdoorT)
                activeMinutes += rasterMinutes
                sumActiveMod += modPct
                sumActivePower += elecPowerKw
                activeSlotCount++
                accumulatedThermalKwh += thermalKw * rasterH
            } else {
                modPct = 0.0
                thermalKw = 0.0
                elecPowerKw = if (isComfort) parasiticBaselinePowerKw else standbyPowerKw
            }

            val slotKwh = elecPowerKw * rasterH
            val slotCostEuro = slotKwh * (grossCt / 100.0)
            totalElectricityKwh += slotKwh
            totalCostEuro += slotCostEuro

            if (isComfort) {
                windowElectricityKwh += slotKwh
                windowCostEuro += slotCostEuro
            }

            // 2R2C State-Space Coupled Capacitance & Building Heat Loss ODE
            val netBuildingLossKw = max(0.0, buildingLossKwPerK * max(0.0, currentTargetRoom - outdoorT) - baselineInternalGainsKw)
            val deltaFloor = currentReturnT - currentTargetRoom
            val alpha = when {
                deltaFloor >= 1.0 -> 0.0
                deltaFloor <= 0.0 -> 1.0
                else -> 1.0 - deltaFloor
            }
            val effectiveCapacityKwh = screedThermalMassKwhPerK + alpha * indoorThermalMassKwhPerK
            val netThermalKw = thermalKw - netBuildingLossKw
            val deltaReturnT = (netThermalKw / max(0.5, effectiveCapacityKwh)) * rasterH
            currentReturnT += deltaReturnT

            if (currentReturnT < minReturnT) minReturnT = currentReturnT
            if (currentReturnT > maxReturnT) maxReturnT = currentReturnT

            simulatedSlots.add(
                SimulatedSlotMetrics(
                    index = i,
                    startMinute = startMin,
                    endMinute = endMin,
                    isComfortSetpoint = isComfort,
                    outdoorTempC = outdoorT,
                    targetReturnTempC = targetReturnT,
                    returnTempC = currentReturnT,
                    isCompressorActive = compressorActive,
                    modulationPct = modPct,
                    electricalPowerKw = elecPowerKw,
                    electricityKwh = slotKwh,
                    slotCostEuro = slotCostEuro
                )
            )
        }

        val avgActiveMod = if (activeSlotCount > 0) sumActiveMod / activeSlotCount else 0.0
        val avgActivePower = if (activeSlotCount > 0) sumActivePower / activeSlotCount else standbyPowerKw

        return ScheduleSimulationResult(
            simulatedSlots = simulatedSlots,
            totalElectricityKwh = totalElectricityKwh,
            totalCostEuro = totalCostEuro,
            windowElectricityKwh = windowElectricityKwh,
            windowCostEuro = windowCostEuro,
            activeCompressorMinutes = activeMinutes,
            cycleStartsCount = cycleStartsCount,
            averageActivePowerKw = avgActivePower,
            averageModulationPct = avgActiveMod,
            minReturnTempC = minReturnT,
            maxReturnTempC = maxReturnT,
            endReturnTempC = currentReturnT
        )
    }
}
