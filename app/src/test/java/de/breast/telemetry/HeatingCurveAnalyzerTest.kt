package de.breast.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatingCurveAnalyzerTest {

    private val analyzer = HeatingCurveAnalyzer()

    @Test
    fun `analyzeHeatingCurve returns null when fewer than 5 valid heating snapshots exist`() {
        val snapshots = listOf(
            HeatPumpTelemetrySnapshot(
                timestampIso = "2026-10-24T12:00:00Z",
                epochSeconds = 1792771200L,
                outdoorTempC = 10.0,
                supplyTempC = 28.0,
                returnTempC = 24.0,
                condenserTempC = 30.0,
                setpointTempC = 21.0,
                modulationPct = 35.0,
                demand = "ch",
                chStatus = "on"
            )
        )
        assertNull(analyzer.analyzeHeatingCurve(snapshots))
    }

    @Test
    fun `analyzeHeatingCurve correctly fits linear slope and estimates flow temperatures`() {
        // Ideal floor curve: T_flow = 24.0 + 0.45 * (20 - T_outdoor) = 33.0 - 0.45 * T_outdoor
        // At T_out = 15°C -> T_flow = 26.25°C
        // At T_out = 10°C -> T_flow = 28.50°C
        // At T_out = 5°C  -> T_flow = 30.75°C
        // At T_out = 0°C  -> T_flow = 33.00°C
        // At T_out = -5°C -> T_flow = 35.25°C
        val snapshots = listOf(
            createHeatingSnapshot("2026-10-24T10:00:00Z", 1000L, outdoor = 15.0, supply = 26.25, ret = 22.5),
            createHeatingSnapshot("2026-10-24T11:00:00Z", 2000L, outdoor = 10.0, supply = 28.50, ret = 24.5),
            createHeatingSnapshot("2026-10-24T12:00:00Z", 3000L, outdoor = 5.0, supply = 30.75, ret = 26.5),
            createHeatingSnapshot("2026-10-24T13:00:00Z", 4000L, outdoor = 0.0, supply = 33.00, ret = 28.5),
            createHeatingSnapshot("2026-10-24T14:00:00Z", 5000L, outdoor = -5.0, supply = 35.25, ret = 30.5)
        )

        val result = analyzer.analyzeHeatingCurve(snapshots)
        assertNotNull(result)
        assertEquals(5, result!!.sampleCount)
        assertEquals(0.45, result.empiricalSlope, 0.01)
        assertEquals(24.0, result.baseTempAt20C, 0.05)
        assertEquals(33.0, result.flowTempAt0C, 0.05)
        assertEquals(37.5, result.designTempAtMinus10C, 0.05)
        assertEquals(1.0, result.rSquared, 0.01)
        assertTrue(result.evaluationSummary.contains("Optimal Bosch underfloor curve"))
    }

    @Test
    fun `analyzeHeatingCurve filters out initial cold-start warmup transients when steady state data exists`() {
        val baseEpoch = 100000L
        // Cold start ramp: heat pump starts at t=0, cold water 20°C, 22°C, 24°C during first 15 mins
        val rampPoints = listOf(
            createHeatingSnapshot("2026-10-24T10:00:00Z", baseEpoch + 0L, outdoor = 0.0, supply = 20.0, ret = 19.0),
            createHeatingSnapshot("2026-10-24T10:05:00Z", baseEpoch + 300L, outdoor = 0.0, supply = 22.0, ret = 20.0),
            createHeatingSnapshot("2026-10-24T10:10:00Z", baseEpoch + 600L, outdoor = 0.0, supply = 24.0, ret = 21.0)
        )
        // Steady-state points (>= 20 min): true equilibrium on curve T_flow = 33.0 - 0.45 * T_out
        val steadyPoints = listOf(
            createHeatingSnapshot("2026-10-24T10:25:00Z", baseEpoch + 1500L, outdoor = 15.0, supply = 26.25, ret = 22.5),
            createHeatingSnapshot("2026-10-24T10:45:00Z", baseEpoch + 2700L, outdoor = 10.0, supply = 28.50, ret = 24.5),
            createHeatingSnapshot("2026-10-24T11:05:00Z", baseEpoch + 3900L, outdoor = 5.0, supply = 30.75, ret = 26.5),
            createHeatingSnapshot("2026-10-24T11:25:00Z", baseEpoch + 5100L, outdoor = 0.0, supply = 33.00, ret = 28.5),
            createHeatingSnapshot("2026-10-24T11:45:00Z", baseEpoch + 6300L, outdoor = -5.0, supply = 35.25, ret = 30.5)
        )

        val result = analyzer.analyzeHeatingCurve(rampPoints + steadyPoints)
        assertNotNull(result)
        assertEquals(8, result!!.sampleCount)
        assertEquals(5, result.steadyStateSampleCount)
        assertEquals(3, result.transientSampleCount)
        // Verified: The slope and intercept are strictly fitted on the steady-state points,
        // so the 20°C/22°C/24°C ramp points did NOT drag down the flow at 0°C (remains 33.0°C)!
        assertEquals(0.45, result.empiricalSlope, 0.01)
        assertEquals(33.0, result.flowTempAt0C, 0.05)
        assertEquals(1.0, result.rSquared, 0.01)
    }

    @Test
    fun `build24hHourlyProfile correctly buckets snapshots and computes averages`() {
        val s1 = createHeatingSnapshot("2026-10-24T12:15:00Z", 1000L, outdoor = 12.0, supply = 28.0, ret = 24.0)
        val s2 = createHeatingSnapshot("2026-10-24T12:45:00Z", 2000L, outdoor = 14.0, supply = 28.0, ret = 24.0)
        val s3 = createHeatingSnapshot("2026-10-24T15:00:00Z", 3000L, outdoor = 10.0, supply = 29.0, ret = 25.0)

        val profile = analyzer.build24hHourlyProfile(listOf(s1, s2, s3))
        assertEquals(24, profile.size)

        val hour12 = profile[12]
        assertEquals(2, hour12.sampleCount)
        assertEquals(13.0, hour12.avgOutdoorTempC!!, 0.01)
        assertEquals(28.0, hour12.avgSupplyTempC!!, 0.01)
        assertEquals(4.0, hour12.avgDeltaTC!!, 0.01)
        assertEquals(100.0, hour12.heatingActivePct, 0.01)

        val hour15 = profile[15]
        assertEquals(1, hour15.sampleCount)
        assertEquals(10.0, hour15.avgOutdoorTempC!!, 0.01)

        val hour0 = profile[0]
        assertEquals(0, hour0.sampleCount)
        assertNull(hour0.avgOutdoorTempC)
    }

    private fun createHeatingSnapshot(
        timeIso: String,
        epoch: Long,
        outdoor: Double,
        supply: Double,
        ret: Double
    ): HeatPumpTelemetrySnapshot {
        return HeatPumpTelemetrySnapshot(
            timestampIso = timeIso,
            epochSeconds = epoch,
            outdoorTempC = outdoor,
            supplyTempC = supply,
            returnTempC = ret,
            condenserTempC = supply + 2.0,
            setpointTempC = 21.0,
            modulationPct = 40.0,
            demand = "ch",
            chStatus = "on"
        )
    }
}
