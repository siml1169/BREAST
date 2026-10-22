package de.breast

import de.breast.api.SwitchPoint
import de.breast.auth.SingleKeyAuthManager
import de.breast.optimizer.HeatingTemperatureSlider
import de.breast.optimizer.OperationDurationSlider
import de.breast.optimizer.SwitchProgramScheduler
import de.breast.optimizer.SwitchScheduleConfig
import de.breast.spot.SpotPriceClient
import de.breast.spot.SpotPriceSlot
import de.breast.spot.VattenfallTariffConfig
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BreastCoreTest {

    @Test
    fun `PKCE S256 challenge matches RFC 7636 test vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expectedChallenge, SingleKeyAuthManager.computeS256CodeChallenge(verifier))
    }

    @Test
    fun `extractAuthorizationCode handles full Bosch custom scheme redirect URL and raw code`() {
        val redirectUrl = "com.bosch.tt.dashtt.pointt://app/login?code=ory_ac_abc123-1&state=xyz987&scope=openid"
        assertEquals("ory_ac_abc123-1", SingleKeyAuthManager.extractAuthorizationCode(redirectUrl))
        assertEquals("ory_ac_abc123-1", SingleKeyAuthManager.extractAuthorizationCode("  ory_ac_abc123-1 "))
    }

    @Test
    fun `HeatingTemperatureSlider snaps continuous values to 0_5C increments within bounds`() {
        val slider = HeatingTemperatureSlider(minTempC = 15.0, maxTempC = 30.0, stepSizeC = 0.5)
        assertEquals(30, slider.totalSteps)
        assertEquals(21.5, slider.withValue(21.4).snappedTempC, 1e-6)
        assertEquals(22.0, slider.withValue(21.8).snappedTempC, 1e-6)
        assertEquals(15.0, slider.withValue(10.0).snappedTempC, 1e-6)
        assertEquals(30.0, slider.withValue(35.0).snappedTempC, 1e-6)
    }

    @Test
    fun `OperationDurationSlider snaps runtime hours to 0_5h increments within bounds`() {
        val slider = OperationDurationSlider(minHours = 0.5, maxHours = 16.0, stepHours = 0.5)
        assertEquals(31, slider.totalSteps)
        assertEquals(6.0, slider.withHours(5.9).snappedHours, 1e-6)
        assertEquals(360, slider.withHours(5.9).snappedMinutes)
        assertEquals(4.5, slider.withHours(4.4).snappedHours, 1e-6)
        assertEquals(270, slider.withHours(4.4).snappedMinutes)
        assertEquals(0.5, slider.withHours(0.1).snappedHours, 1e-6)
        assertEquals(16.0, slider.withHours(20.0).snappedHours, 1e-6)
    }

    @Test
    fun `SwitchProgramScheduler finds 1 shared contiguous lowest-price window of 6 hours with 2 setpoints per day`() {
        val date = LocalDate.of(2026, 10, 22)
        val dayStartEpoch = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.0)

        val slots = (0 until 24).map { hour ->
            val eurMwh = when (hour) {
                10, 11, 12, 13, 14, 15 -> -20.0
                else -> 150.0
            }
            SpotPriceSlot(
                startEpochSeconds = dayStartEpoch + hour * 3600L,
                endEpochSeconds = dayStartEpoch + (hour + 1) * 3600L,
                spotEurPerMwh = eurMwh,
                spotNetCtPerKwh = eurMwh / 10.0,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(eurMwh)
            )
        }

        val scheduler = SwitchProgramScheduler(
            SwitchScheduleConfig(
                windowCount = 1,
                maxSwitchPointsPerDay = 2,
                targetOperationHoursPerDay = 6.0,
                temperatureSlider = HeatingTemperatureSlider().withValue(22.0),
                dailyBudgetEuro = null,
                estimatedPowerKw = 2.0
            )
        )

        val plan = scheduler.buildDailySwitchSchedule(date, slots)
        assertNotNull(plan)
        assertEquals("Th", plan!!.dayOfWeekCode)
        assertEquals(22.0, plan.targetHeatingTempC, 1e-6)
        assertEquals(1, plan.sharedWindows.size)
        assertEquals(360, plan.plannedOperationMinutes)
        assertEquals(0, plan.budgetCutMinutes)
        assertEquals(600, plan.sharedWindows[0].startMinutes)
        assertEquals(960, plan.sharedWindows[0].endMinutes)
        assertEquals(2, plan.heatingSwitchPoints.size)
        assertEquals(2, plan.dhwSwitchPoints.size)
        assertEquals(
            plan.heatingSwitchPoints.map { it.time },
            plan.dhwSwitchPoints.map { it.time }
        )
    }

    @Test
    fun `SwitchProgramScheduler cuts single contiguous window below 6 hours when dailyBudgetEuro is exceeded`() {
        val date = LocalDate.of(2026, 10, 22)
        val dayStartEpoch = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tariff = VattenfallTariffConfig(vatRate = 0.0, regionalSurchargeGrossCtPerKwh = 0.0)

        val slots = (0 until 24).map { hour ->
            SpotPriceSlot(
                startEpochSeconds = dayStartEpoch + hour * 3600L,
                endEpochSeconds = dayStartEpoch + (hour + 1) * 3600L,
                spotEurPerMwh = 250.0,
                spotNetCtPerKwh = 25.0,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(250.0)
            )
        }

        val scheduler = SwitchProgramScheduler(
            SwitchScheduleConfig(
                windowCount = 1,
                maxSwitchPointsPerDay = 2,
                targetOperationHoursPerDay = 6.0,
                dailyBudgetEuro = 2.00,
                estimatedPowerKw = 2.0
            )
        )

        val plan = scheduler.buildDailySwitchSchedule(date, slots)
        assertNotNull(plan)
        assertEquals(240, plan!!.plannedOperationMinutes)
        assertEquals(120, plan.budgetCutMinutes)
        assertEquals(2.00, plan.estimatedDailyCostEuro, 1e-6)
        assertEquals(1, plan.sharedWindows.size)
        assertEquals(2, plan.heatingSwitchPoints.size)
        assertEquals(2, plan.dhwSwitchPoints.size)
    }

    @Test
    fun `detectOnAndOffSetpoints preserves low as ON when off is present on DHW`() {
        val scheduler = SwitchProgramScheduler()
        val dhwPoints = listOf(
            SwitchPoint("Mo", "low", 720),
            SwitchPoint("Mo", "off", 900)
        )
        assertEquals("low" to "off", scheduler.detectOnAndOffSetpoints(dhwPoints, "low", "off"))

        val hcPoints = listOf(
            SwitchPoint("Mo", "comfort2", 690),
            SwitchPoint("Mo", "eco", 1050)
        )
        assertEquals("comfort2" to "eco", scheduler.detectOnAndOffSetpoints(hcPoints, "comfort2", "eco"))
    }

    @Test
    fun `SwitchProgramScheduler accepts last 15 minutes of day and emits a single switchpoint when optimal window ends at 24_00`() {
        val date = LocalDate.of(2026, 10, 22)
        val dayStartEpoch = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.0)

        // Make 18:00-24:00 (including the last 15 minutes 23:45-24:00) the cheapest 6-hour block
        val slots = (0 until 24).map { hour ->
            val eurMwh = if (hour in 18..23) -30.0 else 120.0
            SpotPriceSlot(
                startEpochSeconds = dayStartEpoch + hour * 3600L,
                endEpochSeconds = dayStartEpoch + (hour + 1) * 3600L,
                spotEurPerMwh = eurMwh,
                spotNetCtPerKwh = eurMwh / 10.0,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(eurMwh)
            )
        }

        val scheduler = SwitchProgramScheduler(
            SwitchScheduleConfig(
                windowCount = 1,
                maxSwitchPointsPerDay = 2,
                targetOperationHoursPerDay = 6.0
            )
        )

        val plan = scheduler.buildDailySwitchSchedule(date, slots)
        assertNotNull(plan)
        assertEquals(1, plan!!.sharedWindows.size)
        assertEquals(1080, plan.sharedWindows[0].startMinutes) // 18:00
        assertEquals(1440, plan.sharedWindows[0].endMinutes)   // 24:00 (includes 23:45-24:00)
        assertEquals(360, plan.plannedOperationMinutes)
        // Only a single ON switchpoint at 18:00 (1080); no premature OFF switchpoint at 23:45!
        assertEquals(1, plan.heatingSwitchPoints.size)
        assertEquals(1080, plan.heatingSwitchPoints[0].time)
        assertEquals("comfort2", plan.heatingSwitchPoints[0].setpoint)
        assertEquals(1, plan.dhwSwitchPoints.size)
        assertEquals(1080, plan.dhwSwitchPoints[0].time)
        assertEquals("low", plan.dhwSwitchPoints[0].setpoint)
    }

    @Test
    fun `hasCompleteDayPrices detects when tomorrow Day-Ahead prices are published between 12_00 and 15_00`() {
        val today = LocalDate.of(2026, 10, 22)
        val tomorrow = today.plusDays(1)
        val todayStart = today.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tomorrowStart = tomorrow.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond

        val scheduler = SwitchProgramScheduler()

        val onlyTodaySlots = (0 until 24).map { h ->
            SpotPriceSlot(todayStart + h * 3600L, todayStart + (h + 1) * 3600L, 80.0, 8.0, 27.5)
        }
        assertTrue(scheduler.hasCompleteDayPrices(today, onlyTodaySlots))
        assertFalse(scheduler.hasCompleteDayPrices(tomorrow, onlyTodaySlots))

        val bothDaysSlots = onlyTodaySlots + (0 until 24).map { h ->
            SpotPriceSlot(tomorrowStart + h * 3600L, tomorrowStart + (h + 1) * 3600L, 60.0, 6.0, 25.1)
        }
        assertTrue(scheduler.hasCompleteDayPrices(tomorrow, bothDaysSlots))
    }
}
