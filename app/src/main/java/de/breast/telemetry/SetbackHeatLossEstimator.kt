package de.breast.telemetry

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Astronomical solar time calculator implementing NOAA solar position equations.
 * Used to isolate pure nocturnal periods (free of solar insolation and exterior envelope thermal lag).
 */
object SolarTimeCalculator {
    /**
     * Calculates UTC sunrise and sunset instants for a given date and geographic coordinates.
     */
    fun calculateSunriseSunset(
        date: LocalDate,
        latitude: Double,
        longitude: Double
    ): Pair<Instant, Instant>? {
        val doy = date.dayOfYear
        val gamma = 2.0 * Math.PI / 365.0 * (doy - 1)
        val eqtime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) - 0.014615 * cos(2.0 * gamma) - 0.040849 * sin(2.0 * gamma))
        val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2.0 * gamma) + 0.000907 * sin(2.0 * gamma) - 0.002697 * cos(3.0 * gamma) + 0.00148 * sin(3.0 * gamma)

        val phi = Math.toRadians(latitude)
        val zenith = Math.toRadians(90.833) // Standard solar disk radius + refraction

        val cosOmega = (cos(zenith) - sin(phi) * sin(decl)) / (cos(phi) * cos(decl))
        if (cosOmega > 1.0 || cosOmega < -1.0) {
            return null // Polar day or night
        }
        val omegaDeg = Math.toDegrees(acos(cosOmega))

        val solarNoonUtcMin = 720.0 - 4.0 * longitude - eqtime
        val sunriseUtcMin = solarNoonUtcMin - 4.0 * omegaDeg
        val sunsetUtcMin = solarNoonUtcMin + 4.0 * omegaDeg

        val startOfDayUtc = date.atStartOfDay(ZoneOffset.UTC).toInstant()
        val sunrise = startOfDayUtc.plusSeconds((sunriseUtcMin * 60.0).roundToLong())
        val sunset = startOfDayUtc.plusSeconds((sunsetUtcMin * 60.0).roundToLong())

        return Pair(sunrise, sunset)
    }

    /**
     * Determines whether an instant is strictly within the nocturnal cooling period:
     * at least [minHoursAfterSunset] hours after sunset (ensuring daytime solar radiation
     * and building envelope thermal transient have completely dissipated) and before morning sunrise.
     */
    fun isNocturnal(
        instant: Instant,
        latitude: Double,
        longitude: Double,
        minHoursAfterSunset: Double = 2.0
    ): Boolean {
        val date = instant.atZone(ZoneOffset.UTC).toLocalDate()
        val todaySun = calculateSunriseSunset(date, latitude, longitude) ?: return false
        val (sunriseToday, sunsetToday) = todaySun

        val eveningThreshold = sunsetToday.plusSeconds((minHoursAfterSunset * 3600.0).roundToLong())

        // 1. Evening of 'date': after sunset + minHoursAfterSunset and before sunrise tomorrow
        if (instant >= eveningThreshold) {
            val tomorrowSun = calculateSunriseSunset(date.plusDays(1), latitude, longitude)
            val sunriseTomorrow = tomorrowSun?.first ?: sunsetToday.plusSeconds(12 * 3600)
            return instant < sunriseTomorrow
        }

        // 2. Early morning of 'date': before today's sunrise and after yesterday's sunset + minHoursAfterSunset
        if (instant < sunriseToday) {
            val yesterdaySun = calculateSunriseSunset(date.minusDays(1), latitude, longitude)
            val sunsetYesterday = yesterdaySun?.second ?: sunriseToday.minusSeconds(12 * 3600)
            val yesterdayThreshold = sunsetYesterday.plusSeconds((minHoursAfterSunset * 3600.0).roundToLong())
            return instant >= yesterdayThreshold
        }

        return false
    }
}

data class SetbackEstimationResult(
    val sampleCount: Int,
    val durationHours: Double,
    val coolingRateKPerHour: Double,
    val avgOutdoorTempC: Double,
    val avgIndoorTempC: Double,
    val estimatedLossKwPerK: Double, // e.g. 0.136
    val estimatedLossWPerK: Int,     // e.g. 136
    val timestampStartIso: String,
    val timestampEndIso: String,
    val rSquared: Double = 0.95,
    val temperatureDropK: Double = 0.5,
    val blockCount: Int = 1,
    val weightedLossWPerK: Int? = null,
    val effectiveCapacityKwhPerK: Double = 4.8,
    val allBlocks: List<SetbackEstimationResult> = emptyList(),
    val historicalPriorIncorporated: Boolean = false,
    val historicalPriorWeight: Double = 0.0
) {
    /**
     * Formats start and end times into local time (e.g. "18:57–20:22 CET")
     * matching the user's physical clock rather than UTC.
     */
    fun formatLocalTimeSpan(): String {
        return try {
            val zone = ZoneId.systemDefault()
            val dtf = DateTimeFormatter.ofPattern("HH:mm")
            val startZ = Instant.parse(timestampStartIso).atZone(zone)
            val endZ = Instant.parse(timestampEndIso).atZone(zone)
            val zoneName = zone.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            "${dtf.format(startZ)}–${dtf.format(endZ)} $zoneName"
        } catch (e: Exception) {
            "${timestampStartIso.substring(11, 16)}–${timestampEndIso.substring(11, 16)} UTC"
        }
    }
}

class SetbackHeatLossEstimator(
    val floorScreedCapacityKwhPerK: Double = 4.8,
    val indoorCapacityKwhPerK: Double = 8.5,
    val defaultIndoorTempC: Double = 21.5,
    val targetSetpointTempC: Double? = null,
    val minDurationHours: Double = 1.0,
    val skipInitialTransitionMinutes: Int = 15,
    val latitude: Double = 52.5200,
    val longitude: Double = 13.4050,
    val minHoursAfterSunset: Double = 2.0,
    val enforceNocturnalOnly: Boolean = true,
    val minCoolingRateKPerHour: Double = 0.04,
    val minTemperatureDropK: Double = 0.15,
    val minRSquared: Double = 0.70
) {
    /**
     * Finds nocturnal setback period(s) from telemetry snapshots and estimates building heat loss
     * using the 2R2C State-Space Dynamic Thermal Mass Observer (Model 3).
     *
     * 1. Data strictly starts at least 2 hours after sunset (nocturnal window, free of solar insolation).
     * 2. The heat pump is strictly in setback/eco operating period (setpoint <= 18.5°C, compressor OFF, demand idle).
     * 3. Seamlessly bridges nocturnal blocks across calendar midnight (e.g. 19:00 -> 04:15 next morning).
     * 4. Evaluates continuous multi-point linear regression and dynamic participating capacity C_eff
     *    (distinguishing active screed buffer discharge from whole-building coupled decay).
     */
    fun estimateBuildingHeatLoss(
        snapshots: List<HeatPumpTelemetrySnapshot>,
        priorHeatLossWPerK: Int? = null,
        priorWeight: Double = 0.0
    ): SetbackEstimationResult? {
        if (snapshots.size < 3) return null
        val sorted = snapshots.sortedBy { it.epochSeconds }

        val candidateBlocks = mutableListOf<List<HeatPumpTelemetrySnapshot>>()
        var currentBlock = mutableListOf<HeatPumpTelemetrySnapshot>()

        val targetComfort = targetSetpointTempC ?: defaultIndoorTempC

        for (snap in sorted) {
            val sup = snap.supplyTempC
            val ret = snap.returnTempC
            val out = snap.outdoorTempC
            val mod = snap.modulationPct ?: 0.0

            if (sup == null || ret == null || out == null) continue

            // 1. Strict Nocturnal Filter: Only values starting 2 hours after sunset until sunrise.
            if (enforceNocturnalOnly) {
                val instant = Instant.ofEpochSecond(snap.epochSeconds)
                val isNocturnal = SolarTimeCalculator.isNocturnal(instant, latitude, longitude, minHoursAfterSunset)
                if (!isNocturnal) {
                    if (currentBlock.size >= 3) {
                        candidateBlocks.add(currentBlock)
                    }
                    currentBlock = mutableListOf()
                    continue
                }
            }

            // Gap check: if there is a gap > 2 hours between snapshots, end current block
            if (currentBlock.isNotEmpty() && (snap.epochSeconds - currentBlock.last().epochSeconds > 7200L)) {
                if (currentBlock.size >= 3) {
                    candidateBlocks.add(currentBlock)
                }
                currentBlock = mutableListOf()
            }

            // 2. Strict Setback Mode Condition:
            // - Scheduled setpoint must be setback level (eco, e.g. <= 18.5C or <= targetComfort - 2.0K)
            // - Compressor is OFF (modulation < 1.0%)
            // - Space heating and DHW demands are OFF
            // - Circulation loop return & supply equalized (within 1.5K)
            val isSetbackSetpoint = snap.setpointTempC == null ||
                snap.setpointTempC <= (targetComfort - 2.0) ||
                snap.setpointTempC <= 18.5

            val isSetback = isSetbackSetpoint &&
                !snap.isHeatingActive &&
                !snap.isDhwActive &&
                mod < 1.0 &&
                kotlin.math.abs(sup - ret) <= 1.5

            if (isSetback) {
                currentBlock.add(snap)
            } else {
                if (currentBlock.size >= 3) {
                    candidateBlocks.add(currentBlock)
                }
                currentBlock = mutableListOf()
            }
        }
        if (currentBlock.size >= 3) {
            candidateBlocks.add(currentBlock)
        }

        if (candidateBlocks.isEmpty()) return null

        val validBlocks = mutableListOf<SetbackEstimationResult>()

        for (block in candidateBlocks) {
            val startEpoch = block.first().epochSeconds
            val endEpoch = block.last().epochSeconds
            val totalDurationH = (endEpoch - startEpoch) / 3600.0

            if (totalDurationH < minDurationHours || block.size < 4) continue

            val initialRet = block.first().returnTempC ?: continue
            val lastRet = block.last().returnTempC ?: continue
            val dropK = initialRet - lastRet

            if (dropK < minTemperatureDropK) continue

            // Linear regression of return temperature vs time (in hours) across all N samples
            var sumT = 0.0
            var sumY = 0.0
            var sumTT = 0.0
            var sumTY = 0.0
            val n = block.size.toDouble()

            for (s in block) {
                val tHours = (s.epochSeconds - startEpoch) / 3600.0
                val yTemp = s.returnTempC!!
                sumT += tHours
                sumY += yTemp
                sumTT += tHours * tHours
                sumTY += tHours * yTemp
            }

            val denom = n * sumTT - sumT * sumT
            if (kotlin.math.abs(denom) < 1e-6) continue

            val slope = (n * sumTY - sumT * sumY) / denom
            val coolingRateKPerHour = -slope // positive cooling rate
            val intercept = (sumY - slope * sumT) / n

            // Calculate R^2 (coefficient of determination)
            val yMean = sumY / n
            var ssTot = 0.0
            var ssRes = 0.0
            for (s in block) {
                val tHours = (s.epochSeconds - startEpoch) / 3600.0
                val yActual = s.returnTempC!!
                val yPred = slope * tHours + intercept
                ssTot += (yActual - yMean).pow(2)
                ssRes += (yActual - yPred).pow(2)
            }
            val r2 = if (ssTot > 1e-6) (1.0 - (ssRes / ssTot)).coerceIn(0.0, 1.0) else 0.0

            if (coolingRateKPerHour < minCoolingRateKPerHour || coolingRateKPerHour > 2.0) continue
            if (r2 < minRSquared) continue

            val avgOutdoor = block.map { it.outdoorTempC!! }.average()
            val avgReturn = block.map { it.returnTempC!! }.average()

            // Model 3: 2R2C State-Space Dynamic Capacitance
            // Differentiates active screed buffer release from whole-building coupled decay:
            val deltaFloor = avgReturn - targetComfort
            val alpha = when {
                deltaFloor >= 1.0 -> 0.0
                deltaFloor <= 0.0 -> 1.0
                else -> 1.0 - deltaFloor
            }
            val effectiveCapacityKwh = floorScreedCapacityKwhPerK + alpha * indoorCapacityKwhPerK
            val indoorTemp = if (avgReturn > targetComfort) targetComfort else avgReturn
            val deltaT = max(2.0, indoorTemp - avgOutdoor)

            val thermalDischargeKw = effectiveCapacityKwh * coolingRateKPerHour
            val hBldKwPerK = thermalDischargeKw / deltaT
            val hBldWPerK = (hBldKwPerK * 1000.0).roundToInt()

            // Filter physically plausible building heat loss values (50 W/K to 400 W/K for residential homes)
            if (hBldWPerK in 50..400) {
                validBlocks.add(
                    SetbackEstimationResult(
                        sampleCount = block.size,
                        durationHours = totalDurationH,
                        coolingRateKPerHour = coolingRateKPerHour,
                        avgOutdoorTempC = avgOutdoor,
                        avgIndoorTempC = targetComfort,
                        estimatedLossKwPerK = hBldKwPerK,
                        estimatedLossWPerK = hBldWPerK,
                        timestampStartIso = block.first().timestampIso,
                        timestampEndIso = block.last().timestampIso,
                        rSquared = r2,
                        temperatureDropK = dropK,
                        effectiveCapacityKwhPerK = effectiveCapacityKwh
                    )
                )
            }
        }

        if (validBlocks.isEmpty()) return null

        // Select the most recent valid block as primary result
        val mostRecent = validBlocks.maxByOrNull { it.timestampEndIso } ?: validBlocks.last()

        // Calculate R^2 * durationHours weighted average across all valid nocturnal blocks, incorporating 25k historical anchor
        val totalBlockWeight = validBlocks.sumOf { it.rSquared * it.durationHours }
        val hasPrior = priorHeatLossWPerK != null && priorWeight > 0.0
        val weightedLossW = if (hasPrior) {
            val totalCombinedWeight = totalBlockWeight + priorWeight
            val blockContribution = validBlocks.sumOf { it.estimatedLossWPerK * it.rSquared * it.durationHours }
            val priorContribution = priorHeatLossWPerK!! * priorWeight
            ((blockContribution + priorContribution) / totalCombinedWeight).roundToInt()
        } else if (totalBlockWeight > 0.0) {
            (validBlocks.sumOf { it.estimatedLossWPerK * it.rSquared * it.durationHours } / totalBlockWeight).roundToInt()
        } else {
            mostRecent.estimatedLossWPerK
        }

        return mostRecent.copy(
            blockCount = validBlocks.size,
            weightedLossWPerK = weightedLossW,
            allBlocks = validBlocks,
            historicalPriorIncorporated = hasPrior,
            historicalPriorWeight = if (hasPrior) priorWeight else 0.0
        )
    }
}
