package de.breast

import de.breast.api.PointTApiClient
import de.breast.api.SwitchPoint
import de.breast.auth.OAuthTokens
import de.breast.auth.SingleKeyAuthManager
import de.breast.auth.TokenStore
import de.breast.daemon.AutoScheduleDaemon
import de.breast.daemon.ScheduleStateStore
import de.breast.daemon.ScheduleSyncState
import de.breast.optimizer.BuildingEnergyBalanceModel
import de.breast.optimizer.ControlUnitSimulator
import de.breast.optimizer.CopThermodynamicModel
import de.breast.optimizer.HolisticOptimizationConfig
import de.breast.optimizer.HolisticScheduleOptimizer
import de.breast.optimizer.OptimizationPreference
import de.breast.optimizer.HeatingTemperatureSlider
import de.breast.optimizer.OperationDurationSlider
import de.breast.optimizer.SwitchProgramScheduler
import de.breast.optimizer.SwitchScheduleConfig
import de.breast.telemetry.EmpiricalModulationAnalyzer
import de.breast.telemetry.EmpiricalModulationProfile
import de.breast.telemetry.HeatPumpTelemetrySnapshot
import de.breast.telemetry.HeatingCurveAnalyzer
import de.breast.telemetry.SetbackHeatLossEstimator
import de.breast.weather.HourlyWeatherForecast
import de.breast.weather.WeatherForecastClient
import de.breast.spot.SpotPriceClient
import de.breast.spot.SpotPriceSlot
import de.breast.spot.SpotPriceSource
import de.breast.spot.VattenfallTariffConfig
import de.breast.spot.ElectricityTariffConfig
import de.breast.spot.TariffPricingModel
import de.breast.spot.TimeOfUseTimetable
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

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
    fun `SwitchProgramScheduler places explicit OFF switchpoint at 23_45 so next day never inherits ON state`() {
        val date = LocalDate.of(2026, 10, 22)
        val dayStartEpoch = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.0)

        // Make evening hours (17:00-24:00) the cheapest block
        val slots = (0 until 24).map { hour ->
            val eurMwh = if (hour in 17..23) -30.0 else 120.0
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
        assertEquals(1020, plan.sharedWindows[0].startMinutes) // 17:00
        assertEquals(1380, plan.sharedWindows[0].endMinutes)   // 23:00
        assertEquals(360, plan.plannedOperationMinutes)
        assertEquals(2, plan.heatingSwitchPoints.size)
        assertEquals(1020, plan.heatingSwitchPoints[0].time)
        assertEquals("comfort2", plan.heatingSwitchPoints[0].setpoint)
        assertEquals(1380, plan.heatingSwitchPoints[1].time)
        assertEquals("eco", plan.heatingSwitchPoints[1].setpoint)
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

    @Test
    fun `SwitchProgramScheduler expands window into contiguous zero or negative price slots`() {
        val date = LocalDate.of(2026, 10, 25)
        val dayStartEpoch = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.0)

        // Simulating today: 0.0 EUR/MWh from 06:00 to 16:00 (10 hours), then expensive 80 EUR/MWh at 16:00-20:00
        val slots = (0 until 24).map { hour ->
            val eurMwh = if (hour in 6..15) 0.0 else if (hour in 16..20) 80.0 else 30.0
            SpotPriceSlot(
                startEpochSeconds = dayStartEpoch + hour * 3600L,
                endEpochSeconds = dayStartEpoch + (hour + 1) * 3600L,
                spotEurPerMwh = eurMwh,
                spotNetCtPerKwh = eurMwh / 10.0,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(eurMwh)
            )
        }

        // Even though targetOperationHours is configured for 6 hours:
        val scheduler = SwitchProgramScheduler(
            SwitchScheduleConfig(
                windowCount = 1,
                maxSwitchPointsPerDay = 2,
                targetOperationHoursPerDay = 6.0,
                zeroPriceExtensionEnabled = true,
                zeroPriceThresholdNetCtPerKwh = 0.5
            )
        )

        val plan = scheduler.buildDailySwitchSchedule(date, slots)
        assertNotNull(plan)
        assertEquals(1, plan!!.sharedWindows.size)
        // Seamlessly absorbs the entire 0-cent block from 06:00 to 16:00 (600 minutes)!
        assertEquals(360, plan.sharedWindows[0].startMinutes) // 06:00
        assertEquals(960, plan.sharedWindows[0].endMinutes)   // 16:00
        assertEquals(600, plan.plannedOperationMinutes)       // 10 hours instead of cutting off at 6 hours!
    }

    @Test
    fun `SwitchProgramScheduler strictly adheres to targetOperationHoursPerDay when zero extension and weather adaptation are disabled`() {
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.50)
        val date = LocalDate.of(2026, 10, 26)
        val dayStartEpoch = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond

        val slots = (0 until 24).map { h: Int ->
            val eurMwh = if (h in 10..18) 0.0 else 120.0
            SpotPriceSlot(
                startEpochSeconds = dayStartEpoch + h * 3600L,
                endEpochSeconds = dayStartEpoch + (h + 1) * 3600L,
                spotEurPerMwh = eurMwh,
                spotNetCtPerKwh = eurMwh / 10.0,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(eurMwh)
            )
        }

        // Target daily runtime = 6.0 hours (static mode: zeroPriceExtension = false, autoWeatherAdaptive = false)
        val scheduler = SwitchProgramScheduler(
            SwitchScheduleConfig(
                windowCount = 1,
                maxSwitchPointsPerDay = 2,
                targetOperationHoursPerDay = 6.0,
                zeroPriceExtensionEnabled = false,
                autoWeatherAdaptiveHoursEnabled = false,
                averageOutdoorTempC = -5.0 // Even if -5°C freezing outside!
            )
        )

        val plan = scheduler.buildDailySwitchSchedule(date, slots)
        assertNotNull(plan)
        assertEquals(1, plan!!.sharedWindows.size)
        // Strictly adheres to exactly 6.0 hours (360 minutes), never distorted by 0-cent prices or weather
        assertEquals(360, plan.plannedOperationMinutes)
        assertEquals(360, plan.sharedWindows[0].durationMinutes)
    }

    @Test
    fun `computeDaySignature generates canonical sorted signature for day switch points`() {
        val scheduler = SwitchProgramScheduler()
        val points = listOf(
            SwitchPoint("Mo", "eco", 1080),
            SwitchPoint("Mo", "comfort2", 600),
            SwitchPoint("Tu", "comfort2", 720)
        )
        val sigMo = scheduler.computeDaySignature(points, "Mo")
        assertEquals("600:comfort2;1080:eco", sigMo)

        val sigTu = scheduler.computeDaySignature(points, "Tu")
        assertEquals("720:comfort2", sigTu)

        val sigWe = scheduler.computeDaySignature(points, "We")
        assertEquals("", sigWe)
    }

    @Test
    fun `AutoScheduleDaemon halts auto-sync and leaves heat pump untouched when manualAdjustmentDetected is active`() = runBlocking {
        val fakeStore = object : ScheduleStateStore {
            var state = ScheduleSyncState(
                lastSyncedTodayDate = "2026-10-25",
                lastAppliedSliderTempC = 21.5,
                manualAdjustmentDetected = true,
                manualAdjustmentHint = "manual adjustment detected, no further change"
            )
            override suspend fun loadState(): ScheduleSyncState = state
            override suspend fun saveState(state: ScheduleSyncState) { this.state = state }
        }

        val fakeTokenStore = object : TokenStore {
            override suspend fun loadTokens(): OAuthTokens? = null
            override suspend fun saveTokens(tokens: OAuthTokens) {}
            override suspend fun clearTokens() {}
            override suspend fun savePendingPkceVerifier(verifier: String) {}
            override suspend fun loadPendingPkceVerifier(): String? = null
        }

        val client = OkHttpClient()
        val auth = SingleKeyAuthManager(client, fakeTokenStore)
        val pointT = PointTApiClient(client, auth)
        val spot = SpotPriceClient(client)

        val daemon = AutoScheduleDaemon(
            spotClient = spot,
            pointTClient = pointT,
            scheduler = SwitchProgramScheduler(),
            stateStore = fakeStore,
            dryRun = true
        )

        val result = daemon.runHourlyCheckCycle(ZonedDateTime.now(SpotPriceClient.BERLIN_ZONE))
        assertFalse(result.pushedToHeatPump)
        assertFalse(result.checkedSpotApi)
        assertTrue(result.logs.any { it.contains("manual adjustment detected, no further change") })
    }

    @Test
    fun `AutoScheduleDaemon does not automatically overwrite today schedule in background once already applied`() = runBlocking {
        val today = LocalDate.now(SpotPriceClient.BERLIN_ZONE).toString()
        val fakeStore = object : ScheduleStateStore {
            var state = ScheduleSyncState(
                lastSyncedTodayDate = today,
                lastAppliedSliderTempC = 21.5,
                appliedScheduleNote = "Calculated schedule applied for $today: Target=21.5°C"
            )
            override suspend fun loadState(): ScheduleSyncState = state
            override suspend fun saveState(state: ScheduleSyncState) { this.state = state }
        }

        val fakeTokenStore = object : TokenStore {
            override suspend fun loadTokens(): OAuthTokens? = null
            override suspend fun saveTokens(tokens: OAuthTokens) {}
            override suspend fun clearTokens() {}
            override suspend fun savePendingPkceVerifier(verifier: String) {}
            override suspend fun loadPendingPkceVerifier(): String? = null
        }

        val client = OkHttpClient()
        val auth = SingleKeyAuthManager(client, fakeTokenStore)
        val pointT = PointTApiClient(client, auth)
        val spot = SpotPriceClient(client)

        // User changed setpoint to 25.5°C:
        val daemon = AutoScheduleDaemon(
            spotClient = spot,
            pointTClient = pointT,
            scheduler = SwitchProgramScheduler(
                SwitchScheduleConfig(temperatureSlider = HeatingTemperatureSlider().withValue(25.5))
            ),
            stateStore = fakeStore,
            dryRun = true
        )

        // Running before afternoon day-ahead release:
        val now = LocalDate.parse(today).atTime(10, 0).atZone(SpotPriceClient.BERLIN_ZONE)
        val result = daemon.runHourlyCheckCycle(now)
        assertFalse(result.pushedToHeatPump)
        assertFalse(result.checkedSpotApi)
        assertTrue(result.logs.any { it.contains("Schedule already applied & noted") })
    }

    @Test
    fun `SpotPriceClient hasCompleteDayPrices correctly detects complete 24h coverage`() {
        val client = SpotPriceClient(OkHttpClient())
        val date = LocalDate.of(2026, 10, 26)
        val dayStart = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond

        val incompleteSlots = (0 until 12).map { h ->
            SpotPriceSlot(dayStart + h * 3600L, dayStart + (h + 1) * 3600L, 50.0, 5.0, 24.0)
        }
        assertFalse(client.hasCompleteDayPrices(date, incompleteSlots))

        val completeSlots = (0 until 24).map { h ->
            SpotPriceSlot(dayStart + h * 3600L, dayStart + (h + 1) * 3600L, 50.0, 5.0, 24.0)
        }
        assertTrue(client.hasCompleteDayPrices(date, completeSlots))
    }

    @Test
    fun `SpotPriceClient parseVattenfallSpotPricesJson parses 15-minute slots and computes gross tariff`() {
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.50)
        val client = SpotPriceClient(OkHttpClient(), tariff)

        val jsonFixture = """
            {
                "StatusCode": "OK",
                "Result": {
                    "DatumVon": "2026-10-26",
                    "DatumBis": "2026-10-26",
                    "Tage": [
                        {
                            "Datum": "2026-10-26",
                            "DurchschnittspreisNetto": 11.657,
                            "PreiseNetto": {
                                "0000": 2.57,
                                "0015": 2.925,
                                "0030": 1.999,
                                "0045": 2.062
                            }
                        }
                    ]
                }
            }
        """.trimIndent()

        val slots = client.parseVattenfallSpotPricesJson(jsonFixture)
        assertEquals(4, slots.size)

        // Slot 0: 00:00 -> 00:15
        val slot0 = slots[0]
        assertEquals(2.57, slot0.spotNetCtPerKwh, 1e-4)
        assertEquals(25.7, slot0.spotEurPerMwh, 1e-4)
        // Gross: 2.57 * 1.19 + 18.50 = 3.0583 + 18.50 = 21.5583 ct/kWh
        val expectedGross0 = 2.57 * 1.19 + 18.50
        assertEquals(expectedGross0, slot0.totalGrossCtPerKwh, 1e-4)
        assertEquals(900L, slot0.endEpochSeconds - slot0.startEpochSeconds)

        // Slot 1: 00:15 -> 00:30
        val slot1 = slots[1]
        assertEquals(2.925, slot1.spotNetCtPerKwh, 1e-4)
        assertEquals(slot0.endEpochSeconds, slot1.startEpochSeconds)
    }

    @Test
    fun `SpotPriceSource fromId resolves IDs correctly`() {
        assertEquals(SpotPriceSource.VATTENFALL, SpotPriceSource.fromId("vattenfall"))
        assertEquals(SpotPriceSource.ENERGY_CHARTS, SpotPriceSource.fromId("energy_charts"))
        assertEquals(SpotPriceSource.AWATTAR, SpotPriceSource.fromId("awattar"))
        assertEquals(null, SpotPriceSource.fromId("unknown_provider"))
    }

    @Test
    fun `SpotPriceClient fetchDayAheadPrices activates fallback when primary source is incomplete`() = runBlocking {
        val today = LocalDate.of(2026, 10, 25)
        val tomorrow = LocalDate.of(2026, 10, 26)
        val todayEpoch = today.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tomorrowEpoch = tomorrow.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond

        // Today slots (24 hours)
        val todaySlots = (0 until 24).map { h ->
            SpotPriceSlot(todayEpoch + h * 3600L, todayEpoch + (h + 1) * 3600L, 10.0, 1.0, 19.69)
        }
        // Tomorrow slots (24 hours)
        val tomorrowSlots = (0 until 24).map { h ->
            SpotPriceSlot(tomorrowEpoch + h * 3600L, tomorrowEpoch + (h + 1) * 3600L, 50.0, 5.0, 24.45)
        }

        // Test client: Energy-Charts only returns today; aWATTar returns both today and tomorrow
        var energyChartsCalled = false
        var awattarCalled = false

        val testClient = object : SpotPriceClient(OkHttpClient()) {
            override fun fetchFromEnergyCharts(startDate: LocalDate, endDate: LocalDate): List<SpotPriceSlot> {
                energyChartsCalled = true
                return todaySlots // Incomplete: missing tomorrow!
            }

            override fun fetchFromAwattar(startDate: LocalDate, endDate: LocalDate): List<SpotPriceSlot> {
                awattarCalled = true
                return todaySlots + tomorrowSlots // Complete!
            }

            override fun fetchFromVattenfall(startDate: LocalDate): List<SpotPriceSlot> {
                error("Should not be called if disabled or lower in cascade")
            }
        }

        // Energy-Charts is Primary, aWATTar is Fallback 1
        val sources = listOf(SpotPriceSource.ENERGY_CHARTS, SpotPriceSource.AWATTAR)
        val result = testClient.fetchDayAheadPrices(today, tomorrow, sources)

        assertTrue("Primary source (Energy-Charts) was queried first", energyChartsCalled)
        assertTrue("Fallback source (aWATTar) was activated when primary was incomplete", awattarCalled)
        assertEquals(48, result.size)
        assertTrue(testClient.hasCompleteDayPrices(today, result))
        assertTrue(testClient.hasCompleteDayPrices(tomorrow, result))
    }

    @Test
    fun `CopThermodynamicModel calculates realistic COP and scales with outdoor temperature`() {
        val model = CopThermodynamicModel(carnotEfficiency = 0.46)

        // At cold winter temp (0°C outdoor, 35°C supply)
        val copWinter = model.calculateCop(outdoorTempC = 0.0, supplyTempC = 35.0)
        // At mild spring temp (15°C outdoor, 28°C supply)
        val copSpring = model.calculateCop(outdoorTempC = 15.0, supplyTempC = 28.0)

        assertTrue("Spring COP ($copSpring) must be substantially higher than Winter COP ($copWinter)", copSpring > copWinter)
        assertTrue("Winter COP should be realistic (~3.0 - 4.5)", copWinter in 3.0..4.5)
        assertTrue("Spring COP should be higher (~5.0 - 6.5)", copSpring in 5.0..6.5)

        // Delivered heat cost: 20 ct/kWh electrical / COP 4.0 = 5.0 ct/kWh_th
        val heatCost = model.computeDeliveredHeatCostCtPerKwh(electricalGrossCtPerKwh = 20.0, cop = 4.0)
        assertEquals(5.0, heatCost, 1e-4)

        // Midday bonus: Peak around 13:00 gives ~1.35x multiplier; 02:00 gives 1.0x
        val middayBonus = model.computeMiddayBonusMultiplier(13)
        val nightBonus = model.computeMiddayBonusMultiplier(2)
        assertEquals(1.35, middayBonus, 1e-2)
        assertEquals(1.0, nightBonus, 1e-2)
    }

    @Test
    fun `BuildingEnergyBalanceModel computes dynamic heating demand and screed buffering`() {
        val model = BuildingEnergyBalanceModel(
            buildingLossCoefficientKwPerK = 0.150, // 150 W/K
            heatPumpNominalThermalPowerKw = 4.5,
            floorScreedThermalCapacityKwhPerK = 5.0,
            maxThermalBufferingK = 1.2
        )

        // Heat loss rate at Tin = 21.5°C, Tout = 5.0°C -> deltaT = 16.5K
        // Loss = 16.5 * 0.150 = 2.475 kW
        val lossKw = model.calculateLossRateKw(21.5, 5.0)
        assertEquals(2.475, lossKw, 1e-3)

        // Over 24h at constant 5°C: 24 * 2.475 = 59.4 kWh
        val temps = List(24) { 5.0 }
        val dailyKwh = model.calculateDailyThermalEnergyNeedKwh(21.5, temps)
        assertEquals(59.4, dailyKwh, 1e-2)

        // Nominal hours at 4.5 kW thermal output: 59.4 / 4.5 = 13.2h
        val nominalHours = model.calculateNominalRequiredHours(dailyKwh)
        assertEquals(13.2, nominalHours, 1e-2)

        // Max buffering: screed capacity 5.0 kWh/K * 1.2K = 6.0 kWh buffer
        // Extra runtime = 6.0 / 4.5 = 1.333h
        val normalHours = model.calculateMaxBufferOperatingHours(nominalHours, hasZeroOrNegativePrices = false)
        val expandedHours = model.calculateMaxBufferOperatingHours(nominalHours, hasZeroOrNegativePrices = true)

        assertEquals(13.2, normalHours, 1e-2)
        assertEquals(13.2 + (6.0 / 4.5), expandedHours, 1e-2)
    }

    @Test
    fun `HolisticScheduleOptimizer expands schedule on 0-cent or negative price day without premature cutoff`() {
        val date = LocalDate.of(2026, 10, 25)
        val dayStartEpoch = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.0)

        // Today scenario: 10:00 to 16:00 (6 hours) has negative spot prices (-20 EUR/MWh = -2 ct/kWh net)
        // Other hours are 150 EUR/MWh (15 ct/kWh net)
        val slots = (0 until 24).map { hour ->
            val eurMwh = if (hour in 10..15) -20.0 else 150.0
            SpotPriceSlot(
                startEpochSeconds = dayStartEpoch + hour * 3600L,
                endEpochSeconds = dayStartEpoch + (hour + 1) * 3600L,
                spotEurPerMwh = eurMwh,
                spotNetCtPerKwh = eurMwh / 10.0,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(eurMwh)
            )
        }

        val optimizer = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                minContinuousRunMinutes = 90,
                zeroPriceThresholdNetCtPerKwh = 0.5
            )
        )

        val plan = optimizer.buildSchedule(date, slots)
        assertNotNull(plan)

        // The schedule should cover the entire 10:00 to 16:00 zero/negative price block
        val window = plan!!.heatingWindows.first()
        assertTrue("Window should start at or before 10:00 (600 min)", window.startMinutes <= 600)
        assertTrue("Window should extend at least until 16:00 (960 min)", window.endMinutes >= 960)
        assertTrue("Duration should be at least 6 hours (360 min)", window.durationMinutes >= 360)
    }

    @Test
    fun `HolisticScheduleOptimizer prioritizes midday Carnot window over colder nighttime window`() {
        val date = LocalDate.of(2026, 10, 25)
        val dayStartEpoch = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond

        // Night (00:00-08:00) has slightly cheaper electricity (19 ct gross) but cold temp (0°C)
        // Midday (11:00-15:00) has slightly higher electricity (21 ct gross) but warm temp (14°C)
        // Rest of day has 30 ct gross
        val slots = (0 until 24).map { hour ->
            val gross = when (hour) {
                in 1..5 -> 19.0 // Cold night: COP ~3.0 -> Heat cost ~6.3 ct
                in 11..14 -> 21.0 // Warm noon: COP ~5.0 -> Heat cost ~4.2 ct (cheaper heat!)
                else -> 30.0
            }
            val net = (gross - 18.0) / 1.19
            SpotPriceSlot(
                startEpochSeconds = dayStartEpoch + hour * 3600L,
                endEpochSeconds = dayStartEpoch + (hour + 1) * 3600L,
                spotEurPerMwh = net * 10.0,
                spotNetCtPerKwh = net,
                totalGrossCtPerKwh = gross
            )
        }

        val weatherForecast = (0 until 24).map { hour ->
            val temp = when (hour) {
                in 0..7 -> 0.0
                in 11..15 -> 14.0
                else -> 6.0
            }
            val hourStr = String.format(java.util.Locale.US, "%02d:00", hour)
            HourlyWeatherForecast(
                dateTime = "2026-10-25T$hourStr",
                outdoorTempC = temp
            )
        }

        val optimizer = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                buildingLossKwPerK = 0.050, // Well-insulated home requiring ~4h heating
                minContinuousRunMinutes = 90
            )
        )

        val plan = optimizer.buildSchedule(date, slots, weatherForecast)
        assertNotNull(plan)

        val window = plan!!.heatingWindows.first()
        // Midday thermodynamic advantage should choose noon window (11:00 - 15:00) over cold night (01:00 - 05:00)
        assertTrue("Window should start around midday (between 10:00 and 13:00, actual startMin=${window.startMinutes})", window.startMinutes in 600..780)
    }

    @Test
    fun `WeatherForecastClient generates synthetic diurnal curve with cold morning and warm afternoon`() {
        val client = WeatherForecastClient(OkHttpClient())
        val date = LocalDate.of(2026, 10, 25)
        val forecast = client.generateSyntheticDiurnalForecast(date, days = 1, baseOutdoorTempC = 8.0, diurnalSwingK = 6.0)

        assertEquals(24, forecast.size)
        val hour6 = forecast[6].outdoorTempC
        val hour14 = forecast[14].outdoorTempC

        assertTrue("Hour 14 ($hour14°C) must be warmer than Hour 6 ($hour6°C)", hour14 > hour6)
        assertEquals(5.0, hour6, 1.0)  // ~ 8 - 3 = 5°C
        assertEquals(11.0, hour14, 1.0) // ~ 8 + 3 = 11°C
    }

    @Test
    fun `WeatherForecastClient parseOpenMeteoJson correctly parses DWD ICON response`() {
        val client = WeatherForecastClient(OkHttpClient())
        val json = """
            {
                "latitude": 52.52,
                "longitude": 13.41,
                "hourly": {
                    "time": ["2026-10-25T00:00", "2026-10-25T01:00", "2026-10-25T12:00"],
                    "temperature_2m": [4.5, 4.2, 12.8],
                    "direct_normal_irradiance": [0.0, 0.0, 450.0],
                    "diffuse_radiation": [0.0, 0.0, 120.0]
                }
            }
        """.trimIndent()

        val parsed = client.parseOpenMeteoJson(json)
        assertEquals(3, parsed.size)
        assertEquals(4.5, parsed[0].outdoorTempC, 1e-3)
        assertEquals(0.0, parsed[0].totalRadiationWm2, 1e-3)

        assertEquals(12.8, parsed[2].outdoorTempC, 1e-3)
        assertEquals(570.0, parsed[2].totalRadiationWm2, 1e-3) // 450 + 120
    }

    @Test
    fun `VattenfallTariffConfig computes exact gross price and base floor from invoice`() {
        val tariff = VattenfallTariffConfig() // Default invoice parameters

        // Base floor at Spot = 0 ct/kWh net: (0 + 21.3446) * 1.19 = 25.40 ct/kWh gross
        val grossAtZero = tariff.computeGrossCtPerKwh(0.0)
        assertEquals(25.400, grossAtZero, 1e-2)
        assertEquals(25.400, tariff.baseFloorGrossCtPerKwh, 1e-2)

        // At user's monthly average spot price of 12.203365 ct/kWh net (122.03365 EUR/MWh)
        // (12.203365 + 21.3446) * 1.19 = 33.547965 * 1.19 = 39.92 ct/kWh gross
        val grossAtAverage = tariff.computeGrossCtPerKwh(122.03365)
        assertEquals(39.922, grossAtAverage, 1e-2)

        // Base monthly fee gross
        assertEquals(13.80, tariff.baseFeeMonthlyGross, 1e-2)
    }

    @Test
    fun `SetbackHeatLossEstimator accurately estimates building heat loss from return temperature cooldown`() {
        val estimator = SetbackHeatLossEstimator(
            floorScreedCapacityKwhPerK = 4.8,
            defaultIndoorTempC = 21.5,
            minDurationHours = 2.0,
            skipInitialTransitionMinutes = 15,
            latitude = 52.5200,
            longitude = 13.4050,
            minHoursAfterSunset = 2.0,
            enforceNocturnalOnly = true
        )

        // Simulate 4 hours of pure night setback at 21:00 UTC (well after sunset + 2h ~17:50 UTC):
        // 16 snapshots (1 every 15 min) from 21:00 to 01:00 UTC
        // Return temp decays from 25.0°C to 23.3°C (rate ~ 0.425 K/h)
        // Outdoor temp is steady at 10.8°C (delta T ~ 10.7 K)
        val startSec = 1792962000L // 2026-10-25 21:00:00 UTC
        val snapshots = (0 until 16).map { i ->
            val tSec = startSec + i * 900L
            val progressH = (i * 15.0) / 60.0
            val retTemp = 25.0 - progressH * 0.425
            HeatPumpTelemetrySnapshot(
                timestampIso = "2026-10-25T${21 + i / 4}:${(i % 4) * 15}:00Z",
                epochSeconds = tSec,
                outdoorTempC = 10.8,
                supplyTempC = retTemp - 0.1, // loop equalized within 0.1K
                returnTempC = retTemp,
                modulationPct = 0.0, // compressor OFF
                setpointTempC = 17.0 // eco / setback setpoint
            )
        }

        val result = estimator.estimateBuildingHeatLoss(snapshots)
        assertNotNull(result)
        // Cooling rate ~ 0.425 K/h
        assertEquals(0.425, result!!.coolingRateKPerHour, 0.05)
        // Discharge = 4.8 * 0.425 = 2.04 kW
        // H_bld = 2.04 kW / 10.7 K = ~190 W/K
        assertTrue("Estimated heat loss should be around ~180-200 W/K (was ${result.estimatedLossWPerK})", result.estimatedLossWPerK in 170..210)
    }

    @Test
    fun `SetbackHeatLossEstimator strictly filters out daytime snapshots and enforces 2h after sunset rule`() {
        // Solar calculator verification for Berlin (Lat 52.52N, Lon 13.405E) on 2026-10-25:
        // Sunset is ~15:52 UTC. Sunset + 2h = 17:52 UTC. Sunrise next morning is ~05:48 UTC.
        val lat = 52.5200
        val lon = 13.4050

        // 1. Daytime 12:00 UTC -> rejected
        assertFalse("12:00 UTC midday must not be considered nocturnal",
            de.breast.telemetry.SolarTimeCalculator.isNocturnal(java.time.Instant.parse("2026-10-25T12:00:00Z"), lat, lon))

        // 2. 16:30 UTC (38 min after sunset, <2h) -> rejected (residual envelope thermal lag)
        assertFalse("16:30 UTC (<2h after sunset) must not be considered nocturnal",
            de.breast.telemetry.SolarTimeCalculator.isNocturnal(java.time.Instant.parse("2026-10-25T16:30:00Z"), lat, lon))

        // 3. 17:30 UTC (1h38m after sunset, <2h) -> rejected
        assertFalse("17:30 UTC (<2h after sunset) must not be considered nocturnal",
            de.breast.telemetry.SolarTimeCalculator.isNocturnal(java.time.Instant.parse("2026-10-25T17:30:00Z"), lat, lon))

        // 4. 18:30 UTC (>2h after sunset, dusk dissipated) -> nocturnal
        assertTrue("18:30 UTC (>2h after sunset) must be nocturnal",
            de.breast.telemetry.SolarTimeCalculator.isNocturnal(java.time.Instant.parse("2026-10-25T18:30:00Z"), lat, lon))

        // 5. 02:00 UTC (deep night) -> nocturnal
        assertTrue("02:00 UTC deep night must be nocturnal",
            de.breast.telemetry.SolarTimeCalculator.isNocturnal(java.time.Instant.parse("2026-10-26T02:00:00Z"), lat, lon))

        // 6. 05:30 UTC (before morning sunrise ~05:48 UTC) -> nocturnal
        assertTrue("05:30 UTC before sunrise must be nocturnal",
            de.breast.telemetry.SolarTimeCalculator.isNocturnal(java.time.Instant.parse("2026-10-26T05:30:00Z"), lat, lon))

        // 7. 06:15 UTC (after sunrise ~05:48 UTC) -> rejected
        assertFalse("06:15 UTC after sunrise must not be considered nocturnal",
            de.breast.telemetry.SolarTimeCalculator.isNocturnal(java.time.Instant.parse("2026-10-26T06:15:00Z"), lat, lon))

        // End-to-end: Midday idle snapshots must be discarded by estimator
        val estimator = SetbackHeatLossEstimator(
            latitude = lat,
            longitude = lon,
            minHoursAfterSunset = 2.0,
            enforceNocturnalOnly = true
        )

        // Midday idle snapshots from 11:00 to 14:00 UTC (3 hours of compressor OFF)
        val middayEpoch = java.time.Instant.parse("2026-10-25T11:00:00Z").epochSecond
        val middaySnapshots = (0 until 12).map { i ->
            HeatPumpTelemetrySnapshot(
                timestampIso = "2026-10-25T${11 + i / 4}:${(i % 4) * 15}:00Z",
                epochSeconds = middayEpoch + i * 900L,
                outdoorTempC = 15.0,
                supplyTempC = 24.0,
                returnTempC = 24.0,
                modulationPct = 0.0,
                setpointTempC = 21.5
            )
        }
        val middayResult = estimator.estimateBuildingHeatLoss(middaySnapshots)
        assertEquals("Midday idle snapshots must be rejected because they are not nocturnal", null, middayResult)
    }

    @Test
    fun `SetbackHeatLossEstimator Model 3 accurately handles both warm screed discharge and prolonged whole-building cooldown`() {
        val estimator = SetbackHeatLossEstimator(
            floorScreedCapacityKwhPerK = 4.8,
            indoorCapacityKwhPerK = 8.5,
            defaultIndoorTempC = 22.0,
            targetSetpointTempC = 22.0,
            minDurationHours = 1.0,
            skipInitialTransitionMinutes = 15,
            latitude = 52.5200,
            longitude = 13.4050,
            minHoursAfterSunset = 2.0,
            enforceNocturnalOnly = true
        )

        // Block A: Prolonged whole-building cooldown (compressor was off all day, water is at 21.5°C <= 22.0°C)
        // Decays slowly from 21.5°C to 20.6°C over 8 hours (0.11 K/h).
        // Under Model 3 (2R2C), participating thermal mass expands to C_screed + C_indoor = 13.3 kWh/K.
        val coldStartSec = 1793040000L // 2026-10-26 20:00:00 UTC (well nocturnal)
        val coldSnapshots = (0 until 16).map { i ->
            val tSec = coldStartSec + i * 1800L // 8 hours
            val retTemp = 21.5 - (i * 0.5) * 0.11
            HeatPumpTelemetrySnapshot(
                timestampIso = "2026-10-26T20:00:00Z",
                epochSeconds = tSec,
                outdoorTempC = 10.0,
                supplyTempC = retTemp - 0.1,
                returnTempC = retTemp,
                modulationPct = 0.0,
                setpointTempC = 17.0 // eco / setback setpoint
            )
        }
        val coldResult = estimator.estimateBuildingHeatLoss(coldSnapshots)
        assertNotNull("Prolonged whole-building cooldown must be accepted and accurately modeled", coldResult)
        assertEquals(0.11, coldResult!!.coolingRateKPerHour, 0.02)
        // Heat loss = 13.3 * 0.11 / (21.5 - 10.0) = 1.463 / 11.5 = ~127 W/K
        assertTrue("Heat loss for whole-building cooldown should be around ~120-145 W/K (was ${coldResult.estimatedLossWPerK})", coldResult.estimatedLossWPerK in 120..145)

        // Block B: Active warm screed discharge (heating finished in afternoon, return water is 24.5°C > 22.0°C)
        // Decays linearly from 24.5°C to 22.9°C over 5 hours (0.32 K/h, R² ~ 1.0)
        val warmStartSec = 1792951434L // 2026-10-25 18:03:54 UTC (well nocturnal)
        val warmSnapshots = (0 until 20).map { i ->
            val tSec = warmStartSec + i * 900L // 5 hours
            val progressH = (i * 15.0) / 60.0
            val retTemp = 24.5 - progressH * 0.32
            HeatPumpTelemetrySnapshot(
                timestampIso = "2026-10-25T18:00:00Z",
                epochSeconds = tSec,
                outdoorTempC = 10.0,
                supplyTempC = retTemp - 0.1,
                returnTempC = retTemp,
                modulationPct = 0.0,
                setpointTempC = 17.0 // eco / setback setpoint
            )
        }
        val warmResult = estimator.estimateBuildingHeatLoss(warmSnapshots)
        assertNotNull("Warm screed discharge must be accepted", warmResult)
        assertEquals(0.32, warmResult!!.coolingRateKPerHour, 0.05)
        // Heat loss = 4.8 * 0.32 / (22.0 - 10.0) = 1.536 / 12.0 = ~128 W/K
        assertTrue("Heat loss should be around ~128 W/K (was ${warmResult.estimatedLossWPerK})", kotlin.math.abs(128 - warmResult.estimatedLossWPerK) <= 15)
    }

    @Test
    fun `HeatingCurveAnalyzer integrates SetbackHeatLossEstimator to report clean nocturnal cooling rate without daytime distortion`() {
        val analyzer = HeatingCurveAnalyzer()
        val baseSec = 1792951434L // 2026-10-25 18:03:54 UTC

        // 5 valid heating points across different outdoor temperatures to fit curve
        val heatingPoints = listOf(
            HeatPumpTelemetrySnapshot("2026-10-25T14:00:00Z", baseSec - 14400L, outdoorTempC = 15.0, supplyTempC = 26.25, returnTempC = 22.5, modulationPct = 40.0, demand = "ch", chStatus = "on", setpointTempC = 22.0),
            HeatPumpTelemetrySnapshot("2026-10-25T14:30:00Z", baseSec - 12600L, outdoorTempC = 10.0, supplyTempC = 28.50, returnTempC = 24.5, modulationPct = 40.0, demand = "ch", chStatus = "on", setpointTempC = 22.0),
            HeatPumpTelemetrySnapshot("2026-10-25T15:00:00Z", baseSec - 10800L, outdoorTempC = 5.0, supplyTempC = 30.75, returnTempC = 26.5, modulationPct = 40.0, demand = "ch", chStatus = "on", setpointTempC = 22.0),
            HeatPumpTelemetrySnapshot("2026-10-25T15:30:00Z", baseSec - 9000L, outdoorTempC = 0.0, supplyTempC = 33.00, returnTempC = 28.5, modulationPct = 40.0, demand = "ch", chStatus = "on", setpointTempC = 22.0),
            HeatPumpTelemetrySnapshot("2026-10-25T16:00:00Z", baseSec - 7200L, outdoorTempC = -5.0, supplyTempC = 35.25, returnTempC = 30.5, modulationPct = 40.0, demand = "ch", chStatus = "on", setpointTempC = 22.0)
        )

        // Nocturnal setback period starting 2h after sunset with warm screed decaying at 0.32 K/h
        val nocturnalPoints = (0 until 12).map { i ->
            val tSec = baseSec + i * 900L // 3 hours
            val progressH = (i * 15.0) / 60.0
            val retTemp = 24.5 - progressH * 0.32
            HeatPumpTelemetrySnapshot(
                timestampIso = "2026-10-25T18:${i * 15}:00Z",
                epochSeconds = tSec,
                outdoorTempC = 10.0,
                supplyTempC = retTemp - 0.1,
                returnTempC = retTemp,
                modulationPct = 0.0,
                setpointTempC = 17.0 // eco / setback setpoint
            )
        }

        val result = analyzer.analyzeHeatingCurve(
            snapshots = heatingPoints + nocturnalPoints,
            targetSetpointTempC = 22.0,
            latitude = 52.5200,
            longitude = 13.4050
        )
        assertNotNull(result)
        assertNotNull("Nocturnal hourly cooling rate must be estimated", result!!.hourlyCoolingRateC)
        assertEquals(0.32, result.hourlyCoolingRateC!!, 0.05)
    }

    @Test
    fun `HolisticScheduleOptimizer respects OptimizationPreference and balances parabolic budget`() {
        val date = LocalDate.of(2026, 10, 25)
        val dayStartEpoch = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tariff = VattenfallTariffConfig()

        // 24 hours of spot prices: 11:00-15:00 has cheap prices (0 ct net = 25.4 ct gross)
        // Evening 17:00-21:00 has expensive peak (15 ct net = 43.25 ct gross)
        val slots = (0 until 24).map { hour ->
            val net = if (hour in 11..14) 0.0 else 15.0
            SpotPriceSlot(
                startEpochSeconds = dayStartEpoch + hour * 3600L,
                endEpochSeconds = dayStartEpoch + (hour + 1) * 3600L,
                spotEurPerMwh = net * 10.0,
                spotNetCtPerKwh = net,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(net * 10.0)
            )
        }

        val weatherForecast = (0 until 24).map { hour ->
            val temp = if (hour in 11..15) 14.0 else 6.0
            HourlyWeatherForecast(
                dateTime = String.format(java.util.Locale.US, "2026-10-25T%02d:00", hour),
                outdoorTempC = temp
            )
        }

        // 1. Comfort Priority optimizer
        val comfortOpt = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                preference = OptimizationPreference.COMFORT
            )
        )
        val planComfort = comfortOpt.buildSchedule(date, slots, weatherForecast)
        assertNotNull(planComfort)
        val comfWin = planComfort!!.heatingWindows.first()
        // Screed phase delay radiates into evening: window starts around midday
        assertTrue("Comfort window duration must be robust (durationMin=${comfWin.durationMinutes})", comfWin.durationMinutes >= 240)

        // 2. Budget Priority optimizer
        val budgetOpt = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                preference = OptimizationPreference.BUDGET
            )
        )
        val planBudget = budgetOpt.buildSchedule(date, slots, weatherForecast)
        assertNotNull(planBudget)
        val budWin = planBudget!!.heatingWindows.first()
        // Window should capture the cheap 11:00-15:00 period (starts 09:00 - 12:00)
        assertTrue("Budget window should start around cheap period (startMin=${budWin.startMinutes})", budWin.startMinutes in 540..720)
    }

    @Test
    fun `test translation of holistic plan into discrete Bosch switch points and daemon force push`() = runBlocking {
        val date = LocalDate.of(2026, 10, 26) // Monday
        val tariff = VattenfallTariffConfig()
        val berlinZone = SpotPriceClient.BERLIN_ZONE
        val dayStartEpoch = date.atStartOfDay(berlinZone).toInstant().epochSecond

        val slots = (0 until 24).map { hour ->
            val net = if (hour in 11..15) 1.0 else 12.0
            SpotPriceSlot(
                startEpochSeconds = dayStartEpoch + hour * 3600L,
                endEpochSeconds = dayStartEpoch + (hour + 1) * 3600L,
                spotEurPerMwh = net * 10.0,
                spotNetCtPerKwh = net,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(net * 10.0)
            )
        }

        val weatherForecast = (0 until 24).map { hour ->
            HourlyWeatherForecast(
                dateTime = String.format(java.util.Locale.US, "2026-10-26T%02d:00", hour),
                outdoorTempC = if (hour in 11..16) 13.0 else 7.0
            )
        }

        val holisticConfig = HolisticOptimizationConfig(
            targetHeatingTempC = 22.0,
            buildingLossKwPerK = 0.174,
            preference = OptimizationPreference.COMFORT
        )

        val scheduler = SwitchProgramScheduler(
            SwitchScheduleConfig(temperatureSlider = HeatingTemperatureSlider(currentValueC = 22.0))
        )
        val plan = scheduler.buildDailySwitchSchedule(date, slots, weatherForecast, holisticConfig)
        assertNotNull(plan)

        // 1. Verify translation to discrete Bosch switch points
        val hcPoints = plan!!.heatingSwitchPoints
        assertTrue("Must generate at least 2 switch points (ON and OFF)", hcPoints.size >= 2)
        assertEquals("Day code must be Mo", "Mo", hcPoints.first().dayOfWeek)
        assertEquals("First switchpoint must turn on comfort2", "comfort2", hcPoints.first().setpoint)
        assertEquals("Second switchpoint must turn setback eco", "eco", hcPoints[1].setpoint)
        assertTrue("Switch points must align to 15-minute raster", hcPoints.all { it.time % 15 == 0 })
        assertTrue("Switch points must not exceed 23:45 cutoff", hcPoints.all { it.time <= 1425 })

        // 2. Verify dry-run sync translation
        val client = OkHttpClient()
        val fakeTokenStore = object : TokenStore {
            override suspend fun loadTokens(): OAuthTokens? = null
            override suspend fun saveTokens(tokens: OAuthTokens) {}
            override suspend fun clearTokens() {}
            override suspend fun savePendingPkceVerifier(verifier: String) {}
            override suspend fun loadPendingPkceVerifier(): String? = null
        }
        val auth = SingleKeyAuthManager(client, fakeTokenStore)
        val pointT = PointTApiClient(client, auth)

        val syncResult = scheduler.syncSchedulesToHeatPumpDetailed(
            apiClient = pointT,
            gatewayId = "TEST_GATEWAY",
            plans = listOf(plan),
            dryRun = true
        )
        assertTrue(syncResult.isSuccess)
        assertTrue(syncResult.logs.any { it.contains("Would update /heatingCircuits/hc1/switchPrograms/A") })
        assertTrue(syncResult.logs.any { it.contains("Heating=ON") })
        assertTrue(syncResult.logs.any { it.contains("Heating=OFF") })

        // 3. Verify AutoScheduleDaemon forcePush bypasses idempotency
        val currentSignature = "${scheduler.config.targetHeatingTempC}_${scheduler.config.targetOperationHoursPerDay}_${holisticConfig.preference.id}_${holisticConfig.buildingLossKwPerK}_${holisticConfig.targetDailyRuntimeHours}_${holisticConfig.dailyBudgetEuro}_${scheduler.config.dailyBudgetEuro}"
        val fakeStore = object : ScheduleStateStore {
            var state = ScheduleSyncState(
                lastSyncedTodayDate = "2026-10-25",
                lastSyncedTomorrowDate = "2026-10-26",
                lastAppliedSettingsSignature = currentSignature,
                tomorrowWindowSummary = "Old obsolete schedule"
            )
            override suspend fun loadState(): ScheduleSyncState = state
            override suspend fun saveState(state: ScheduleSyncState) { this.state = state }
        }

        val fakeSpotClient = object : SpotPriceClient(client) {
            override suspend fun fetchDayAheadPrices(
                startDate: LocalDate,
                endDate: LocalDate,
                sources: List<SpotPriceSource>
            ): List<SpotPriceSlot> = slots
        }

        val daemon = AutoScheduleDaemon(
            spotClient = fakeSpotClient,
            pointTClient = pointT,
            scheduler = scheduler,
            stateStore = fakeStore,
            dryRun = true,
            holisticConfig = holisticConfig
        )

        val testNow = ZonedDateTime.of(2026, 10, 25, 15, 0, 0, 0, berlinZone)

        // Without forcePush: skipped as already applied
        val skippedRes = daemon.runHourlyCheckCycle(now = testNow, forcePush = false)
        assertFalse("Without forcePush, should skip already synced tomorrow", skippedRes.pushedToHeatPump)

        // With forcePush: forces recalculation & push of tomorrow's holistic schedule
        val forceRes = daemon.runHourlyCheckCycle(now = testNow, forcePush = true)
        assertTrue("With forcePush, should translate and push schedule", forceRes.pushedToHeatPump)

        val finalState = fakeStore.loadState()
        assertNotNull(finalState.tomorrowWindowSummary)
        assertTrue("Summary must include translated switch points", finalState.tomorrowWindowSummary!!.contains("Translated Switch Points:"))
        assertTrue("Summary must include concrete switch point lines", finalState.tomorrowWindowSummary!!.contains("comfort2 @ 22.0°C"))
    }

    @Test
    fun `HolisticScheduleOptimizer respects targetDailyRuntimeHours slider setting`() {
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.50)
        val date = LocalDate.of(2026, 10, 26)
        val dayStart = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond

        val slots = (0 until 24).map { h: Int ->
            val eurMwh = if (h in 11..15) 10.0 else 90.0
            SpotPriceSlot(
                startEpochSeconds = dayStart + h * 3600L,
                endEpochSeconds = dayStart + (h + 1) * 3600L,
                spotEurPerMwh = eurMwh,
                spotNetCtPerKwh = eurMwh / 10.0,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(eurMwh)
            )
        }

        val weatherForecast = (0 until 24).map { h ->
            HourlyWeatherForecast(
                dateTime = String.format(java.util.Locale.US, "2026-10-26T%02d:00", h),
                outdoorTempC = 10.0
            )
        }

        val optimizerShort = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                targetHeatingTempC = 21.0,
                targetDailyRuntimeHours = 3.0,
                preference = OptimizationPreference.BUDGET
            )
        )
        val planShort = optimizerShort.buildSchedule(date, slots, weatherForecast)
        assertNotNull(planShort)

        val optimizerLong = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                targetHeatingTempC = 21.0,
                targetDailyRuntimeHours = 8.0,
                preference = OptimizationPreference.BUDGET
            )
        )
        val planLong = optimizerLong.buildSchedule(date, slots, weatherForecast)
        assertNotNull(planLong)

        // Longer target daily runtime produces a longer planned duration
        assertTrue(
            "Longer target daily runtime (${planLong!!.plannedOperationMinutes}m) should exceed shorter target runtime (${planShort!!.plannedOperationMinutes}m)",
            planLong.plannedOperationMinutes > planShort.plannedOperationMinutes
        )
    }

    @Test
    fun `HolisticScheduleOptimizer strictly obeys hard daily budget limit`() {
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.50)
        val date = LocalDate.of(2026, 10, 26)
        val dayStart = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond

        // Expensive day: 100 EUR/MWh (~30 ct gross), 2.0 kW electrical power draw = ~0.60 EUR/hour
        val slots = (0 until 24).map { h: Int ->
            val eurMwh = 100.0
            SpotPriceSlot(
                startEpochSeconds = dayStart + h * 3600L,
                endEpochSeconds = dayStart + (h + 1) * 3600L,
                spotEurPerMwh = eurMwh,
                spotNetCtPerKwh = eurMwh / 10.0,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(eurMwh)
            )
        }

        val weatherForecast = (0 until 24).map { h ->
            HourlyWeatherForecast(
                dateTime = String.format(java.util.Locale.US, "2026-10-26T%02d:00", h),
                outdoorTempC = 0.0 // cold day requiring ~8-10 hours normally
            )
        }

        // Hard budget cap = 1.50 EUR (can only afford ~2.5 hours at 0.60 EUR/h)
        val budgetCap = 1.50
        val optimizer = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                targetHeatingTempC = 21.0,
                targetDailyRuntimeHours = 8.0,
                estimatedPowerKw = 2.0,
                dailyBudgetEuro = budgetCap,
                preference = OptimizationPreference.BUDGET
            )
        )
        val plan = optimizer.buildSchedule(date, slots, weatherForecast)
        assertNotNull(plan)
        assertTrue(
            "Planned cost (${plan!!.estimatedDailyCostEuro} EUR) must strictly not exceed hard budget limit ($budgetCap EUR)",
            plan.estimatedDailyCostEuro <= budgetCap + 1e-4
        )
    }

    @Test
    fun `HolisticScheduleOptimizer in Budget Priority adheres to target runtime and does not severely undercut duration`() {
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.50)
        val date = LocalDate.of(2026, 10, 26)
        val dayStart = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond

        // Cheap midday hours (11:00-17:00 at 10 EUR/MWh), rest of the day 80 EUR/MWh
        val slots = (0 until 24).map { h: Int ->
            val eurMwh = if (h in 11..16) 10.0 else 80.0
            SpotPriceSlot(
                startEpochSeconds = dayStart + h * 3600L,
                endEpochSeconds = dayStart + (h + 1) * 3600L,
                spotEurPerMwh = eurMwh,
                spotNetCtPerKwh = eurMwh / 10.0,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(eurMwh)
            )
        }

        val weatherForecast = (0 until 24).map { h ->
            HourlyWeatherForecast(
                dateTime = String.format(java.util.Locale.US, "2026-10-26T%02d:00", h),
                outdoorTempC = 8.0
            )
        }

        // Target daily runtime = 6.0 hours (360 minutes) in Budget Priority
        val optimizer = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                targetHeatingTempC = 21.5,
                targetDailyRuntimeHours = 6.0,
                estimatedPowerKw = 2.0,
                dailyBudgetEuro = null, // No artificial hard cap
                preference = OptimizationPreference.BUDGET
            )
        )
        val plan = optimizer.buildSchedule(date, slots, weatherForecast)
        assertNotNull(plan)
        val plannedMins = plan!!.plannedOperationMinutes
        // Must strictly adhere around 6.0 hours (360 minutes), within +/- 45 minutes tolerance
        assertTrue(
            "Planned duration ($plannedMins min) must adhere to 6.0h target (>= 315 min) and not severely undercut to 1.5h-2h",
            plannedMins >= 315
        )
        assertTrue(
            "Planned duration ($plannedMins min) must not excessively overrun (<= 420 min)",
            plannedMins <= 420
        )
    }

    @Test
    fun `non-holistic scheduling strictly cuts duration to adhere to dailyBudgetEuro`() {
        val date = LocalDate.of(2026, 10, 26)
        val dayStart = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tariff = VattenfallTariffConfig(vatRate = 0.0, regionalSurchargeGrossCtPerKwh = 0.0)

        // Flat 25.0 ct/kWh gross price
        val slots = (0 until 24).map { h ->
            SpotPriceSlot(
                startEpochSeconds = dayStart + h * 3600L,
                endEpochSeconds = dayStart + (h + 1) * 3600L,
                spotEurPerMwh = 250.0,
                spotNetCtPerKwh = 25.0,
                totalGrossCtPerKwh = 25.0
            )
        }

        // At 2.0 kW and 25.0 ct/kWh, running 1 hour costs 0.50 EUR.
        // If target operation hours = 6.0h (cost = 3.00 EUR),
        // but daily budget is capped at 1.50 EUR:
        val scheduler = SwitchProgramScheduler(
            SwitchScheduleConfig(
                windowCount = 1,
                maxSwitchPointsPerDay = 2,
                targetOperationHoursPerDay = 6.0,
                dailyBudgetEuro = 1.50,
                estimatedPowerKw = 2.0,
                zeroPriceExtensionEnabled = false,
                autoWeatherAdaptiveHoursEnabled = false
            )
        )

        // Non-holistic call: holisticConfig = null
        val plan = scheduler.buildDailySwitchSchedule(date, slots, emptyList(), holisticConfig = null)
        assertNotNull(plan)
        // 1.50 EUR / (2.0 kW * 0.25 EUR/kWh) = 3.0 hours (180 minutes)
        assertEquals(180, plan!!.plannedOperationMinutes)
        assertEquals(180, plan.budgetCutMinutes)
        assertEquals(1.50, plan.estimatedDailyCostEuro, 1e-6)
        assertTrue(plan.estimatedDailyCostEuro <= 1.50 + 1e-6)
    }

    @Test
    fun `HolisticScheduleOptimizer dynamically modulates inverter power based on thermal building demand and COP instead of flat power`() {
        val date = LocalDate.of(2026, 10, 26)
        val dayStart = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.50)

        // Spot prices around 35 ct/kWh gross (8.5 ct/kWh net)
        val slots = (0 until 24).map { h ->
            val net = 8.5
            SpotPriceSlot(
                startEpochSeconds = dayStart + h * 3600L,
                endEpochSeconds = dayStart + (h + 1) * 3600L,
                spotEurPerMwh = net * 10.0,
                spotNetCtPerKwh = net,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(net * 10.0)
            )
        }

        // Mild autumn day at 11.0°C
        val weatherForecast = (0 until 24).map { h ->
            HourlyWeatherForecast(
                dateTime = String.format(java.util.Locale.US, "2026-10-26T%02d:00", h),
                outdoorTempC = 11.0
            )
        }

        // With 5.00 EUR daily budget:
        // Under the old unmodulated flat 2.0 kW model: 6h * 2.0 kW * ~0.35 €/kWh = 4.20–5.20 EUR, which frequently exceeded 5 EUR and got cut short
        // Under dynamic inverter modulation: 6h of heating on an 11°C day requires ~37.8 kWh_th -> ~6.3 kW_th modulation.
        // At COP ~ 5.0, electrical power is only ~1.30 kW, so 6h uses ~7.8 kWh_elec = ~2.20 EUR, well within the 5.00 EUR limit!
        val optimizer = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                targetHeatingTempC = 21.5,
                targetDailyRuntimeHours = 6.0,
                dailyBudgetEuro = 5.00,
                preference = OptimizationPreference.BUDGET,
                autoPowerModulationEnabled = true
            )
        )

        val plan = optimizer.buildSchedule(date, slots, weatherForecast)
        assertNotNull(plan)
        // Full target duration should be preserved without premature budget cut
        assertTrue("Planned duration should adhere around target 6h (was: ${plan!!.plannedOperationMinutes} min)",
            plan.plannedOperationMinutes in 360..420)
        assertEquals(0, plan.budgetCutMinutes)
        assertTrue("Estimated cost must not exceed 5.00 EUR (was: ${plan.estimatedDailyCostEuro})", plan.estimatedDailyCostEuro <= 5.00)
        assertTrue("Modulated average power must be dynamically calculated between 0.5 kW and 1.6 kW (was: ${plan.estimatedAveragePowerKw})",
            plan.estimatedAveragePowerKw in 0.50..1.6)
        assertTrue("Estimated kWh for 6-7h must be between 2.0 and 11.0 kWh (was: ${plan.estimatedElectricityKwh})",
            plan.estimatedElectricityKwh in 2.0..11.0)
        assertTrue("Estimated modulation percentage must be reasonable (was: ${plan.estimatedModulationPct})",
            plan.estimatedModulationPct in 35.0..80.0)
    }

    @Test
    fun `Real-world telemetry comparison - mild autumn day draws ~2 kWh over 3_5h instead of over-estimating to 7 kWh`() {
        val date = LocalDate.of(2026, 10, 26)
        val dayStart = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.50)

        // Flat 30.0 ct/kWh gross price
        val slots = (0 until 24).map { h ->
            SpotPriceSlot(
                startEpochSeconds = dayStart + h * 3600L,
                endEpochSeconds = dayStart + (h + 1) * 3600L,
                spotEurPerMwh = 50.0,
                spotNetCtPerKwh = 5.0,
                totalGrossCtPerKwh = 30.0
            )
        }

        // Mild 14.5°C autumn day
        val weatherForecast = (0 until 24).map { h ->
            HourlyWeatherForecast(
                dateTime = String.format(java.util.Locale.US, "2026-10-26T%02d:00", h),
                outdoorTempC = 14.5
            )
        }

        // For a well-insulated building (0.10 kW/K loss, e.g. modern KfW55/KfW40 underfloor heating home)
        val optimizer = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                targetHeatingTempC = 21.0,
                targetDailyRuntimeHours = 3.5,
                buildingLossKwPerK = 0.100, // Modern low-energy construction
                preference = OptimizationPreference.BUDGET,
                autoPowerModulationEnabled = true
            )
        )

        val plan = optimizer.buildSchedule(date, slots, weatherForecast)
        assertNotNull(plan)
        val win = plan!!.sharedWindows.first()

        // In mild weather for an efficient building, heat loss is very low (6.5K * 0.100 = 0.65 kW_th).
        // The Bosch Compress 7400i AW 9 modulates down to ~20-40% modulation.
        // Electrical draw is ~0.45 - 0.70 kW (telemetry verified ~38% modulation).
        // Total electricity consumed over 3.5h is ~1.8 - 2.5 kWh (~0.60 EUR), exactly matching the user's observed 2 kWh!
        assertTrue("Modulated power draw on mild day should be ~0.45-0.75 kW (was: ${win.estimatedAveragePowerKw})",
            win.estimatedAveragePowerKw in 0.40..0.75)
        assertTrue("Total 3.5h electricity consumption should be ~1.5-2.6 kWh (was: ${win.estimatedElectricityKwh})",
            win.estimatedElectricityKwh in 1.5..2.6)
        assertTrue("Cost should be well under 1.00 EUR (was: ${win.estimatedWindowCostEuro})",
            win.estimatedWindowCostEuro < 1.00)
    }

    @Test
    fun `EmpiricalModulationAnalyzer extracts ground-truth regression and predicts inverter power`() {
        val baseEpoch = 1761500000L
        val snapshots = mutableListOf<HeatPumpTelemetrySnapshot>()

        // Run 1: 10 snapshots spaced by 5 minutes at Tout = 10.0°C
        for (i in 0 until 10) {
            val t = baseEpoch + i * 300L
            val mod = if (i < 4) 65.0 else 46.0 // first 15-20 min warmup (65%), then steady 46%
            snapshots.add(
                HeatPumpTelemetrySnapshot(
                    timestampIso = "2026-10-26T10:${i * 5}:00Z",
                    epochSeconds = t,
                    outdoorTempC = 10.0,
                    supplyTempC = 30.0,
                    returnTempC = 25.0,
                    demand = "hc1",
                    chStatus = "on",
                    modulationPct = mod
                )
            )
        }

        // Run 2: 2 hours later, 10 snapshots spaced by 5 minutes at Tout = 12.0°C
        val run2Epoch = baseEpoch + 7200L
        for (i in 0 until 10) {
            val t = run2Epoch + i * 300L
            val mod = if (i < 4) 50.0 else 31.0 // warmup 50%, then steady 31%
            snapshots.add(
                HeatPumpTelemetrySnapshot(
                    timestampIso = "2026-10-26T12:${i * 5}:00Z",
                    epochSeconds = t,
                    outdoorTempC = 12.0,
                    supplyTempC = 29.0,
                    returnTempC = 24.5,
                    demand = "hc1",
                    chStatus = "on",
                    modulationPct = mod
                )
            )
        }

        val analyzer = EmpiricalModulationAnalyzer(warmupThresholdMinutes = 20.0, minHeatingSamples = 5)
        val profile = analyzer.analyzeModulationProfile(snapshots)

        assertNotNull(profile)
        assertEquals(2, profile!!.detectedRunsCount)
        assertTrue("Steady state samples should be extracted (was: ${profile.steadyStateSamples})", profile.steadyStateSamples >= 10)
        assertTrue("Modulation at 10C should be around 46% (was: ${profile.modulationAt10C})", profile.modulationAt10C in 42.0..50.0)
        assertTrue("Slope should be around 5.0 to 10.0 %/K (was: ${profile.modulationSlopePerK})", profile.modulationSlopePerK in 5.0..10.0)

        // Predictions
        val mod10 = profile.predictModulationPct(10.0)
        val mod12 = profile.predictModulationPct(12.0)
        assertTrue("Modulation at 10C should be higher than at 12C", mod10 > mod12)

        // Power prediction: at 12°C should be ~0.45-0.75 kW
        val power12 = profile.predictElectricalPowerKw(12.0)
        assertTrue("Power at 12C should be around ~0.45-0.75 kW (was: $power12)", power12 in 0.45..0.75)

        // Warmup overhead factor
        assertTrue("Warmup overhead factor should be >= 1.0 (was: ${profile.warmupModulationOverheadFactor})", profile.warmupModulationOverheadFactor >= 1.0)
    }

    @Test
    fun `EmpiricalModulationAnalyzer returns null on insufficient data`() {
        val analyzer = EmpiricalModulationAnalyzer(minHeatingSamples = 5)
        assertEquals(null, analyzer.analyzeModulationProfile(emptyList()))

        val fewSnapshots = listOf(
            HeatPumpTelemetrySnapshot(
                timestampIso = "2026-10-26T10:00:00Z",
                epochSeconds = 1761500000L,
                outdoorTempC = 10.0,
                supplyTempC = 30.0,
                returnTempC = 25.0,
                demand = "hc1",
                chStatus = "on",
                modulationPct = 40.0
            )
        )
        assertEquals(null, analyzer.analyzeModulationProfile(fewSnapshots))
    }

    @Test
    fun `EmpiricalModulationAnalyzer ignores tainted snapshots with mismatched target setpoint`() {
        val baseEpoch = 1761500000L
        val snapshots = mutableListOf<HeatPumpTelemetrySnapshot>()

        // 10 normal snapshots at 21.5°C setpoint, Tout = 12.0°C, modulation = 31%
        for (i in 0 until 10) {
            snapshots.add(
                HeatPumpTelemetrySnapshot(
                    timestampIso = "2026-10-26T10:${i * 5}:00Z",
                    epochSeconds = baseEpoch + i * 300L,
                    outdoorTempC = 12.0,
                    supplyTempC = 29.0,
                    returnTempC = 24.5,
                    setpointTempC = 21.5,
                    demand = "hc1",
                    chStatus = "on",
                    modulationPct = 31.0
                )
            )
        }

        // 6 tainted snapshots at 25.5°C setpoint, Tout = 12.0°C, modulation = 55%
        for (i in 0 until 6) {
            snapshots.add(
                HeatPumpTelemetrySnapshot(
                    timestampIso = "2026-10-26T14:${i * 5}:00Z",
                    epochSeconds = baseEpoch + 14400L + i * 300L,
                    outdoorTempC = 12.0,
                    supplyTempC = 34.0,
                    returnTempC = 27.0,
                    setpointTempC = 25.5,
                    demand = "hc1",
                    chStatus = "on",
                    modulationPct = 55.0
                )
            )
        }

        val analyzer = EmpiricalModulationAnalyzer(warmupThresholdMinutes = 0.0, minHeatingSamples = 5)

        // 1. When analyzing specifically for 21.5°C target:
        val profile21 = analyzer.analyzeModulationProfile(snapshots, targetSetpointTempC = 21.5)
        assertNotNull(profile21)
        assertEquals(21.5, profile21!!.matchedSetpointTempC)
        assertTrue("Modulation for 21.5C target should be ~31% (was: ${profile21.avgSteadyModulationPct})",
            profile21.avgSteadyModulationPct in 30.0..32.0)

        // 2. When analyzing with null target (auto-mode detection):
        val profileAuto = analyzer.analyzeModulationProfile(snapshots, targetSetpointTempC = null)
        assertNotNull(profileAuto)
        assertEquals(21.5, profileAuto!!.matchedSetpointTempC)
        assertTrue("Auto-detected modulation should reject 25.5C outlier and match ~31% (was: ${profileAuto.avgSteadyModulationPct})",
            profileAuto.avgSteadyModulationPct in 30.0..32.0)
    }

    @Test
    fun `HolisticScheduleOptimizer uses empirical ground-truth modulation to accurately estimate runtime electricity`() {
        val date = LocalDate.of(2026, 10, 26)
        val dayStart = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.50)

        val slots = (0 until 24).map { h ->
            SpotPriceSlot(
                startEpochSeconds = dayStart + h * 3600L,
                endEpochSeconds = dayStart + (h + 1) * 3600L,
                spotEurPerMwh = 40.0,
                spotNetCtPerKwh = 4.0,
                totalGrossCtPerKwh = 28.0
            )
        }

        val weatherForecast = (0 until 24).map { h ->
            HourlyWeatherForecast(
                dateTime = String.format(java.util.Locale.US, "2026-10-26T%02d:00", h),
                outdoorTempC = 12.0 // 12°C weather
            )
        }

        val optimizer = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                targetHeatingTempC = 21.0,
                targetDailyRuntimeHours = 3.0,
                preference = OptimizationPreference.BUDGET,
                autoPowerModulationEnabled = true,
                useEmpiricalGroundTruthModulation = true,
                calibratedModulationAt10C = 46.0,
                calibratedModulationSlopePerK = 7.5
            )
        )

        val plan = optimizer.buildSchedule(date, slots, weatherForecast)
        assertNotNull(plan)
        val win = plan!!.sharedWindows.first()

        // At 12°C outdoor temp with empirical modulation:
        // Empirical modulation = 46.0 - 7.5 * 2.0 = 31.0%
        // Thermal output ~ (31.0/100)*9.0 = 2.8 kW
        // Electrical consumption over ~3-4h should be around ~1.3 to 3.2 kWh
        assertTrue("Estimated electricity should match empirical ~1.3 to 3.2 kWh (was: ${win.estimatedElectricityKwh})",
            win.estimatedElectricityKwh in 1.3..3.2)
        assertTrue("Estimated average power should be ~0.45 to 0.85 kW (was: ${win.estimatedAveragePowerKw})",
            win.estimatedAveragePowerKw in 0.45..0.85)
    }

    @Test
    fun `ControlUnitSimulator reproduces Bosch HPC 410 operational logic on 8C day with 00_00-07_15 window`() {
        val simulator = ControlUnitSimulator(
            buildingLossKwPerK = 0.120,
            screedThermalMassKwhPerK = 5.0,
            calibratedModAt10C = 42.0,
            calibratedModSlopePerK = 5.0,
            cutInHysteresisK = 1.2,
            cutOutHysteresisK = 0.5,
            comfortRoomTempC = 21.5,
            setbackRoomTempC = 17.0
        )

        val hourlyTemps = DoubleArray(24) { 8.0 }
        val slotGrossCt = DoubleArray(96) { 28.0 }

        val result = simulator.simulateSchedule(
            comfortStartMinute = 0,
            comfortEndMinute = 435,
            hourlyTemps = hourlyTemps,
            slotGrossCtPerKwh = slotGrossCt,
            rasterMinutes = 15
        )

        // 1. Compressor does NOT start immediately at 00:00 (residual screed heat delays cut-in)
        val slot0 = result.simulatedSlots[0]
        assertFalse("At 00:00 compressor must remain idle due to residual screed temperature", slot0.isCompressorActive)

        // 2. Cycle starts count is at least 1, and compressor active time is significantly less than 7.25h window
        assertTrue("Compressor should cycle at least once (was: ${result.cycleStartsCount})", result.cycleStartsCount >= 1)
        assertTrue("Active compressor time should be between 1.5h and 4.5h (was: ${result.activeCompressorMinutes / 60.0}h)",
            result.activeCompressorMinutes in 90..270)

        // 3. Electrical consumption matches physical reality (~1.5 to 4.5 kWh daily, ~1.5 to 3.8 kWh window, not 8 to 14 kWh!)
        assertTrue("Total electrical consumption should be realistic ~1.5 to 4.5 kWh (was: ${result.totalElectricityKwh})",
            result.totalElectricityKwh in 1.5..4.5)
        assertTrue("Window electrical consumption should be realistic ~1.5 to 3.8 kWh (was: ${result.windowElectricityKwh})",
            result.windowElectricityKwh in 1.5..3.8)
        assertTrue("Total cost should be well under 1.40 EUR (was: ${result.totalCostEuro})",
            result.totalCostEuro < 1.40)
    }

    @Test
    fun `ControlUnitSimulator scales up active run time in freezing 0C weather`() {
        val simulator = ControlUnitSimulator(
            buildingLossKwPerK = 0.150,
            screedThermalMassKwhPerK = 5.0,
            calibratedModAt10C = 42.0,
            calibratedModSlopePerK = 5.0
        )

        val hourlyTempsCold = DoubleArray(24) { 0.0 }
        val slotGrossCt = DoubleArray(96) { 28.0 }

        val resultCold = simulator.simulateSchedule(
            comfortStartMinute = 0,
            comfortEndMinute = 435,
            hourlyTemps = hourlyTempsCold,
            slotGrossCtPerKwh = slotGrossCt,
            rasterMinutes = 15
        )

        val hourlyTempsMild = DoubleArray(24) { 12.0 }
        val resultMild = simulator.simulateSchedule(
            comfortStartMinute = 0,
            comfortEndMinute = 435,
            hourlyTemps = hourlyTempsMild,
            slotGrossCtPerKwh = slotGrossCt,
            rasterMinutes = 15
        )

        assertTrue("Cold 0°C weather must consume more electricity than mild 12°C weather (${resultCold.totalElectricityKwh} vs ${resultMild.totalElectricityKwh})",
            resultCold.totalElectricityKwh > resultMild.totalElectricityKwh)
        assertTrue("Cold 0°C weather active modulation must be higher than mild 12°C (${resultCold.averageModulationPct} vs ${resultMild.averageModulationPct})",
            resultCold.averageModulationPct > resultMild.averageModulationPct)
    }

    @Test
    fun `HolisticScheduleOptimizer preserves target duration and reports zero budget cut when budget is generous`() {
        val date = LocalDate.of(2026, 10, 27)
        val dayStart = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.50)

        // Realistic spot prices around 8.5 ct/kWh net (~35 ct/kWh gross)
        val slots = (0 until 24).map { h ->
            val net = 8.5
            SpotPriceSlot(
                startEpochSeconds = dayStart + h * 3600L,
                endEpochSeconds = dayStart + (h + 1) * 3600L,
                spotEurPerMwh = net * 10.0,
                spotNetCtPerKwh = net,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(net * 10.0)
            )
        }

        // Typical autumn temperatures (8.0°C to 12.0°C)
        val weatherForecast = (0 until 24).map { h ->
            val temp = if (h in 11..16) 12.0 else 8.0
            HourlyWeatherForecast(
                dateTime = String.format(java.util.Locale.US, "2026-10-27T%02d:00", h),
                outdoorTempC = temp
            )
        }

        // User requests 8.0h target daily runtime with 5.00 EUR daily budget in EFFICIENCY mode
        val optimizer = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                targetHeatingTempC = 21.5,
                targetDailyRuntimeHours = 8.0,
                dailyBudgetEuro = 5.00,
                preference = OptimizationPreference.EFFICIENCY,
                autoPowerModulationEnabled = true,
                useEmpiricalGroundTruthModulation = true
            )
        )

        val plan = optimizer.buildSchedule(date, slots, weatherForecast)
        assertNotNull(plan)
        // In EFFICIENCY mode (minDeltaHours = -0.5), allowable corridor is [7.5h .. maxDelta]
        // Planned duration must be at least 7.5h (450 minutes) and not prematurely cut to 7.0h!
        assertTrue(
            "Planned duration must adhere to target corridor >= 7.5h (was: ${plan!!.plannedOperationMinutes / 60.0}h = ${plan.plannedOperationMinutes} min)",
            plan.plannedOperationMinutes >= 450
        )
        // Crucial bug fix check: budgetCutMinutes must be EXACTLY 0 because 2.xx EUR << 5.00 EUR limit!
        assertEquals(0, plan.budgetCutMinutes)
        assertTrue(
            "Estimated cost must stay well within 5.00 EUR budget (was: ${plan.estimatedDailyCostEuro})",
            plan.estimatedDailyCostEuro <= 5.00
        )
    }

    @Test
    fun `HolisticScheduleOptimizer curtails duration below normal corridor and reports budget cut when budget is strictly binding`() {
        val date = LocalDate.of(2026, 10, 27)
        val dayStart = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.50)

        val slots = (0 until 24).map { h ->
            val net = 8.5
            SpotPriceSlot(
                startEpochSeconds = dayStart + h * 3600L,
                endEpochSeconds = dayStart + (h + 1) * 3600L,
                spotEurPerMwh = net * 10.0,
                spotNetCtPerKwh = net,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(net * 10.0)
            )
        }

        val weatherForecast = (0 until 24).map { h ->
            val temp = if (h in 11..16) 12.0 else 8.0
            HourlyWeatherForecast(
                dateTime = String.format(java.util.Locale.US, "2026-10-27T%02d:00", h),
                outdoorTempC = temp
            )
        }

        // Low budget: 0.65 EUR. A full 7.5h run costs ~1.05 EUR, so the optimizer MUST curtail runtime below 7.5h
        val optimizer = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                targetHeatingTempC = 21.5,
                targetDailyRuntimeHours = 8.0,
                dailyBudgetEuro = 0.65,
                preference = OptimizationPreference.EFFICIENCY,
                autoPowerModulationEnabled = true,
                useEmpiricalGroundTruthModulation = true
            )
        )

        val plan = optimizer.buildSchedule(date, slots, weatherForecast)
        assertNotNull(plan)
        // Must strictly enforce budget <= 0.65 EUR
        assertTrue(
            "Estimated cost must not exceed 0.65 EUR budget (was: ${plan!!.estimatedDailyCostEuro})",
            plan.estimatedDailyCostEuro <= 0.65 + 1e-6
        )
        // Duration must be curtailed below normal 450 min corridor due to binding budget
        assertTrue(
            "Duration must be cut below normal 450 min corridor due to binding budget (was: ${plan.plannedOperationMinutes} min)",
            plan.plannedOperationMinutes < 450
        )
        // Genuinely reports budget cut minutes
        assertTrue(
            "Budget cut minutes must be positive when budget forces curtailment (was: ${plan.budgetCutMinutes})",
            plan.budgetCutMinutes > 0
        )
    }

    @Test
    fun `HolisticScheduleOptimizer in Budget Priority strictly locks to exact target duration when within budget`() {
        val date = LocalDate.of(2026, 10, 27)
        val dayStart = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.50)

        // Varied spot prices: cheap midday (11:00-16:00 @ 5 ct net), expensive night/morning @ 12 ct net
        val slots = (0 until 24).map { h ->
            val net = if (h in 11..16) 5.0 else 12.0
            SpotPriceSlot(
                startEpochSeconds = dayStart + h * 3600L,
                endEpochSeconds = dayStart + (h + 1) * 3600L,
                spotEurPerMwh = net * 10.0,
                spotNetCtPerKwh = net,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(net * 10.0)
            )
        }

        val weatherForecast = (0 until 24).map { h ->
            HourlyWeatherForecast(
                dateTime = String.format(java.util.Locale.US, "2026-10-27T%02d:00", h),
                outdoorTempC = 10.0
            )
        }

        // User explicitly requests 8.0 hours in Budget Priority with 5.00 EUR budget
        val optimizer = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                targetHeatingTempC = 21.5,
                targetDailyRuntimeHours = 8.0,
                dailyBudgetEuro = 5.00,
                preference = OptimizationPreference.BUDGET,
                autoPowerModulationEnabled = true,
                useEmpiricalGroundTruthModulation = true
            )
        )

        val plan = optimizer.buildSchedule(date, slots, weatherForecast)
        assertNotNull(plan)
        // In Budget Priority with Delta=0.0h, runtime must strictly equal 8.0 hours (480 minutes)
        assertEquals(480, plan!!.plannedOperationMinutes)
        assertEquals(0, plan.budgetCutMinutes)
        assertTrue(plan.estimatedDailyCostEuro <= 5.00)
    }

    @Test
    fun `HolisticScheduleOptimizer in Budget Priority trims least cost-effective edges via marginal cost when budget is exceeded`() {
        val date = LocalDate.of(2026, 10, 27)
        val dayStart = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.50)

        val slots = (0 until 24).map { h ->
            val net = 8.5
            SpotPriceSlot(
                startEpochSeconds = dayStart + h * 3600L,
                endEpochSeconds = dayStart + (h + 1) * 3600L,
                spotEurPerMwh = net * 10.0,
                spotNetCtPerKwh = net,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(net * 10.0)
            )
        }

        // Cold morning (5.0°C), warm afternoon (12.0°C) -> morning has higher c_heat (lower COP)
        val weatherForecast = (0 until 24).map { h ->
            val temp = if (h in 11..16) 12.0 else 5.0
            HourlyWeatherForecast(
                dateTime = String.format(java.util.Locale.US, "2026-10-27T%02d:00", h),
                outdoorTempC = temp
            )
        }

        // Budget of 0.80 EUR forces trimming from initial 8.0h window
        val optimizer = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                targetHeatingTempC = 21.5,
                targetDailyRuntimeHours = 8.0,
                dailyBudgetEuro = 0.80,
                preference = OptimizationPreference.BUDGET,
                autoPowerModulationEnabled = true,
                useEmpiricalGroundTruthModulation = true
            )
        )

        val plan = optimizer.buildSchedule(date, slots, weatherForecast)
        assertNotNull(plan)
        // Must strictly meet budget
        assertTrue(plan!!.estimatedDailyCostEuro <= 0.80 + 1e-6)
        // Duration was trimmed
        assertTrue(plan.plannedOperationMinutes < 480)
        assertEquals(480 - plan.plannedOperationMinutes, plan.budgetCutMinutes)
    }

    @Test
    fun `HolisticScheduleOptimizer in Efficiency Priority expands into longer daytime window to maximize bang for buck`() {
        val date = LocalDate.of(2026, 10, 27)
        val dayStart = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.50)

        // Steady spot prices
        val slots = (0 until 24).map { h ->
            val net = 8.0
            SpotPriceSlot(
                startEpochSeconds = dayStart + h * 3600L,
                endEpochSeconds = dayStart + (h + 1) * 3600L,
                spotEurPerMwh = net * 10.0,
                spotNetCtPerKwh = net,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(net * 10.0)
            )
        }

        // Cold night (4°C), peak solar midday (13°C)
        val weatherForecast = (0 until 24).map { h ->
            val temp = if (h in 10..16) 13.0 else 4.0
            HourlyWeatherForecast(
                dateTime = String.format(java.util.Locale.US, "2026-10-27T%02d:00", h),
                outdoorTempC = temp
            )
        }

        // Target 6.0 hours, but generous 5.00 EUR budget in Efficiency Priority
        val optimizer = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                targetHeatingTempC = 21.5,
                targetDailyRuntimeHours = 6.0,
                dailyBudgetEuro = 5.00,
                preference = OptimizationPreference.EFFICIENCY,
                autoPowerModulationEnabled = true,
                useEmpiricalGroundTruthModulation = true
            )
        )

        val plan = optimizer.buildSchedule(date, slots, weatherForecast)
        assertNotNull(plan)
        // Efficiency Priority actively prefers longer daytime run (>= 6.0h = 360 min) to maximize bang for buck & lower modulation
        assertTrue(
            "Efficiency Priority should expand duration above target 360m (was: ${plan!!.plannedOperationMinutes}m = ${plan.plannedOperationMinutes / 60.0}h)",
            plan.plannedOperationMinutes >= 360
        )
        assertEquals(0, plan.budgetCutMinutes)
        assertTrue(plan.estimatedDailyCostEuro <= 5.00)
    }

    @Test
    fun `OptimizationPreference dynamically harmonizes extra runtime with scheduler quantifier`() {
        // Budget Priority is always locked strictly to 0% extra hours
        for (target in listOf(2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 16.0)) {
            assertEquals(0.0, OptimizationPreference.BUDGET.calculateHarmonizedExtraHours(target, 0.5), 1e-6)
            assertEquals(0.0, OptimizationPreference.BUDGET.calculateHarmonizedExtraHours(target, 0.25), 1e-6)
        }

        // Balanced Priority: +25% expansion harmonized to 0.5h quantifier steps
        assertEquals(1.0, OptimizationPreference.BALANCED.calculateHarmonizedExtraHours(4.0, 0.5), 1e-6)
        assertEquals(1.5, OptimizationPreference.BALANCED.calculateHarmonizedExtraHours(6.0, 0.5), 1e-6)
        assertEquals(2.0, OptimizationPreference.BALANCED.calculateHarmonizedExtraHours(8.0, 0.5), 1e-6)
        assertEquals(2.5, OptimizationPreference.BALANCED.calculateHarmonizedExtraHours(10.0, 0.5), 1e-6)
        assertEquals(3.0, OptimizationPreference.BALANCED.calculateHarmonizedExtraHours(12.0, 0.5), 1e-6)

        // Efficiency Priority: +35% expansion harmonized to 0.5h quantifier steps
        assertEquals(1.5, OptimizationPreference.EFFICIENCY.calculateHarmonizedExtraHours(4.0, 0.5), 1e-6)
        assertEquals(2.0, OptimizationPreference.EFFICIENCY.calculateHarmonizedExtraHours(6.0, 0.5), 1e-6)
        assertEquals(3.0, OptimizationPreference.EFFICIENCY.calculateHarmonizedExtraHours(8.0, 0.5), 1e-6)
        assertEquals(3.5, OptimizationPreference.EFFICIENCY.calculateHarmonizedExtraHours(10.0, 0.5), 1e-6)
        assertEquals(4.0, OptimizationPreference.EFFICIENCY.calculateHarmonizedExtraHours(12.0, 0.5), 1e-6)

        // Comfort Priority: +20% expansion harmonized to 0.5h quantifier steps
        assertEquals(1.0, OptimizationPreference.COMFORT.calculateHarmonizedExtraHours(4.0, 0.5), 1e-6)
        assertEquals(1.0, OptimizationPreference.COMFORT.calculateHarmonizedExtraHours(6.0, 0.5), 1e-6)
        assertEquals(1.5, OptimizationPreference.COMFORT.calculateHarmonizedExtraHours(8.0, 0.5), 1e-6)
        assertEquals(2.0, OptimizationPreference.COMFORT.calculateHarmonizedExtraHours(10.0, 0.5), 1e-6)
    }

    @Test
    fun `HolisticScheduleOptimizer evaluates dynamically harmonized candidate slot corridors`() {
        val date = LocalDate.of(2026, 10, 27)
        val dayStart = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.50)

        val slots = (0 until 24).map { h ->
            val net = 7.0
            SpotPriceSlot(
                startEpochSeconds = dayStart + h * 3600L,
                endEpochSeconds = dayStart + (h + 1) * 3600L,
                spotEurPerMwh = net * 10.0,
                spotNetCtPerKwh = net,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(net * 10.0)
            )
        }

        val weatherForecast = (0 until 24).map { h ->
            HourlyWeatherForecast(
                dateTime = String.format(java.util.Locale.US, "2026-10-27T%02d:00", h),
                outdoorTempC = 12.0
            )
        }

        // Target: 8.0h in Balanced mode (+25% -> +2.0h max corridor allowance)
        val optimizer = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                targetHeatingTempC = 21.5,
                targetDailyRuntimeHours = 8.0,
                preference = OptimizationPreference.BALANCED,
                durationQuantizerHours = 0.5
            )
        )

        val plan = optimizer.buildSchedule(date, slots, weatherForecast)
        assertNotNull(plan)
        val plannedHours = plan!!.plannedOperationMinutes / 60.0
        // Duration must strictly obey non-negative floor (>= 8.0h) and not exceed 10.0h (8.0h + 2.0h)
        assertTrue("Planned hours $plannedHours must be >= 8.0h floor", plannedHours >= 8.0)
        assertTrue("Planned hours $plannedHours must be <= 10.0h ceiling", plannedHours <= 10.0)
        assertNotNull(plan.physicsBreakdown)
        assertTrue(plan.physicsBreakdown!!.contains("Dynamic Runtime Corridor (Harmonized Quantizer)"))
        assertTrue(plan.physicsBreakdown!!.contains("Scheduler Quantizer Step: 0.50 h (30 min)"))
    }

    @Test
    fun `EmpiricalModulationAnalyzer incorporates 25k historical prior anchor into new telemetry regression`() {
        val baseEpoch = 1761500000L
        val snapshots = mutableListOf<HeatPumpTelemetrySnapshot>()

        // 10 new snapshots at 12°C with raw modulation around 30% (would fit to ~45% at 10°C, slope ~7.5%/K)
        for (i in 0 until 10) {
            snapshots.add(
                HeatPumpTelemetrySnapshot(
                    timestampIso = "2026-10-27T10:${i * 5}:00Z",
                    epochSeconds = baseEpoch + i * 300L,
                    outdoorTempC = 12.0,
                    supplyTempC = 29.0,
                    returnTempC = 24.5,
                    setpointTempC = 21.5,
                    demand = "hc1",
                    chStatus = "on",
                    modulationPct = if (i < 3) 50.0 else 30.0
                )
            )
        }

        val analyzer = EmpiricalModulationAnalyzer(warmupThresholdMinutes = 15.0, minHeatingSamples = 5)

        // Without prior: fits purely to active samples
        val profileWithoutPrior = analyzer.analyzeModulationProfile(snapshots)
        assertNotNull(profileWithoutPrior)
        assertFalse(profileWithoutPrior!!.historicalPriorIncorporated)

        // With 25k historical prior anchor (historical baseline: 42.0% at 10°C, slope 3.5%/K with heavy weight W=100)
        val profileWithPrior = analyzer.analyzeModulationProfile(
            snapshots = snapshots,
            priorModulationAt10C = 42.0,
            priorModulationSlopePerK = 3.5,
            priorWeight = 100.0
        )
        assertNotNull(profileWithPrior)
        assertTrue(profileWithPrior!!.historicalPriorIncorporated)
        assertEquals(100.0, profileWithPrior.historicalPriorWeight, 0.01)

        // The blended modulation at 10°C should be anchored closer to the 42.0% prior than the raw fit
        assertTrue(
            "Blended modulation at 10C should be anchored close to 42% (was: ${profileWithPrior.modulationAt10C})",
            kotlin.math.abs(profileWithPrior.modulationAt10C - 42.0) < kotlin.math.abs(profileWithoutPrior.modulationAt10C - 42.0)
        )
    }

    @Test
    fun `SetbackHeatLossEstimator incorporates 25k historical heat loss baseline into nocturnal blocks`() {
        val baseEpoch = 1761500000L
        // Nocturnal block spanning 3 hours from 22:00 to 01:00 UTC
        val snapshots = (0..36).map { i ->
            val ret = 26.0 - (i / 36.0) * 0.9 // 0.9K drop over 3.0h = 0.30 K/h
            HeatPumpTelemetrySnapshot(
                timestampIso = "2026-10-27T22:00:00Z",
                epochSeconds = baseEpoch + i * 300L,
                outdoorTempC = 10.0,
                supplyTempC = ret,
                returnTempC = ret,
                setpointTempC = 18.0,
                demand = "idle",
                chStatus = "off",
                modulationPct = 0.0
            )
        }

        val estimator = SetbackHeatLossEstimator(
            defaultIndoorTempC = 21.5,
            targetSetpointTempC = 21.5,
            enforceNocturnalOnly = false
        )

        // Without prior
        val resWithout = estimator.estimateBuildingHeatLoss(snapshots)
        assertNotNull(resWithout)
        assertFalse(resWithout!!.historicalPriorIncorporated)

        // With 25k historical baseline: 150 W/K with heavy weight W = 50.0
        val resWithPrior = estimator.estimateBuildingHeatLoss(
            snapshots = snapshots,
            priorHeatLossWPerK = 150,
            priorWeight = 50.0
        )
        assertNotNull(resWithPrior)
        assertTrue(resWithPrior!!.historicalPriorIncorporated)
        assertEquals(50.0, resWithPrior.historicalPriorWeight, 0.01)

        // Weighted loss should be blended towards 150 W/K
        val diffWith = kotlin.math.abs(resWithPrior.weightedLossWPerK!! - 150)
        val diffWithout = kotlin.math.abs((resWithout.weightedLossWPerK ?: resWithout.estimatedLossWPerK) - 150)
        assertTrue("Blended heat loss should be closer to 150 W/K prior", diffWith <= diffWithout)
    }

    @Test
    fun `Verification of User Active Setup - Comfort 22C with 9_25h window produces realistic ~3 kWh electricity`() {
        val simulator = ControlUnitSimulator(
            buildingLossKwPerK = 0.180,
            screedThermalMassKwhPerK = 5.0,
            calibratedModAt10C = 45.97,
            calibratedModSlopePerK = 6.38,
            cutInHysteresisK = 1.2,
            cutOutHysteresisK = 0.5,
            heatingCurveSlope = 0.65,
            comfortRoomTempC = 22.0,
            setbackRoomTempC = 17.0,
            nominalThermalKw = 9.0,
            circulationPumpKw = 0.04,
            parasiticBaselinePowerKw = 0.050
        )

        // 96 slots of 15 min. Mild 11.5°C day, 53.4 ct/kWh gross peak price
        val hourlyTemps = DoubleArray(24) { 11.5 }
        val slotGrossCt = DoubleArray(96) { 53.4 }

        // Scheduled comfort window: 08:30 (510 min) to 17:45 (1065 min) = 9.25 hours (37 slots)
        val dailyEnergyNeedKwh = 18.5 // 13.5 kWh daily loss + 5.0 kWh screed recharge

        val sim = simulator.simulateSchedule(
            comfortStartMinute = 510,
            comfortEndMinute = 1065,
            hourlyTemps = hourlyTemps,
            slotGrossCtPerKwh = slotGrossCt,
            rasterMinutes = 15,
            targetDailyThermalEnergyNeedKwh = dailyEnergyNeedKwh
        )

        println("=== SIMULATION VERIFICATION RESULT ===")
        println("Active Compressor Minutes: ${sim.activeCompressorMinutes} min (${sim.activeCompressorMinutes / 60.0} h)")
        println("Cycle Starts Count: ${sim.cycleStartsCount}")
        println("Average Active Power: ${sim.averageActivePowerKw} kW")
        println("Average Active Modulation: ${sim.averageModulationPct}%")
        println("Window Electricity: ${sim.windowElectricityKwh} kWh")
        println("Window Cost: ${sim.windowCostEuro} EUR")
        println("Total Electricity: ${sim.totalElectricityKwh} kWh")
        println("Total Cost: ${sim.totalCostEuro} EUR")

        // Assert that compressor does NOT run continuously for all 9.25h (555 min)
        assertTrue("Compressor active time should be between 3.5h and 6.5h (was: ${sim.activeCompressorMinutes / 60.0}h)",
            sim.activeCompressorMinutes in 210..390)
        // Assert that active electrical power is ~0.45 - 0.55 kW (not 0.78 kW!)
        assertTrue("Active power should be realistic ~0.45 - 0.55 kW (was: ${sim.averageActivePowerKw})",
            sim.averageActivePowerKw in 0.45..0.55)
        // Assert that window electricity is ~2.0 - 3.5 kWh (not 7.5 kWh!)
        assertTrue("Window electricity should be ~2.0 - 3.5 kWh (was: ${sim.windowElectricityKwh})",
            sim.windowElectricityKwh in 2.0..3.5)
        // Assert that cost is ~1.20 - 1.90 EUR (not 3.99 EUR!)
        assertTrue("Window cost should be ~1.20 - 1.90 EUR (was: ${sim.windowCostEuro})",
            sim.windowCostEuro in 1.20..1.90)
    }

    private class InMemoryThermalParametersStore(
        override var learnedBuildingLossKwPerK: Double = 0.150,
        override var learnedCoolingRateKPerHour: Double = 0.20,
        override var learnedCycleCount: Int = 0,
        override var lastLearnedTimestampIso: String? = null,
        override val baseBuildingLossKwPerK: Double = 0.150,
        override val sliderTempC: Double = 21.5
    ) : de.breast.telemetry.ThermalParametersStore

    @Test
    fun `AdaptiveThermalObserver continuously learns Hbld and cooling rate from nocturnal telemetry via EMA`() {
        val store = InMemoryThermalParametersStore(
            learnedBuildingLossKwPerK = 0.150,
            learnedCoolingRateKPerHour = 0.20,
            learnedCycleCount = 0
        )
        val observer = de.breast.telemetry.AdaptiveThermalObserver(
            learningRateEma = 0.20,
            minRSquared = 0.65,
            minDurationHours = 1.0,
            latitude = 52.5200,
            longitude = 13.4050
        )

        // Synthesize 5 hours of clean nocturnal setback telemetry (21:00 to 02:00, deep night)
        // 1761516000 is 2026-10-26 21:00:00 UTC (deep night in Berlin)
        val baseEpoch = 1761516000L
        val snapshots = mutableListOf<HeatPumpTelemetrySnapshot>()
        for (i in 0 until 20) {
            val t = baseEpoch + i * 900L // every 15 min
            val hours = i * 0.25
            val retT = 20.8 - (0.16 * hours) // cooling at 0.16 K/h in whole-house coupled setback regime
            snapshots.add(
                HeatPumpTelemetrySnapshot(
                    timestampIso = "2026-10-26T21:${i * 15}:00Z",
                    epochSeconds = t,
                    supplyTempC = retT + 0.2,
                    returnTempC = retT,
                    outdoorTempC = 8.0,
                    setpointTempC = 17.0,
                    modulationPct = 0.0,
                    demand = "off",
                    chStatus = "off"
                )
            )
        }

        val result = observer.performOnlineCalibration(snapshots, store)
        assertNotNull("Calibration result should be non-null for clean nocturnal setback data", result)
        assertTrue("Calibration should flag as updated", result!!.isUpdated)
        assertEquals(1, store.learnedCycleCount)
        assertTrue("R^2 should be very high (was: ${result.rSquared})", result.rSquared > 0.95)

        // Prior Hbld was 0.150. Observed Hbld is ~0.17 - 0.20. With 20% EMA, updated Hbld should shift towards observed:
        assertTrue("Updated Hbld should be between 0.150 and 0.180 (was: ${store.learnedBuildingLossKwPerK})",
            store.learnedBuildingLossKwPerK in 0.150..0.180)
        assertTrue("Updated cooldown rate should be between 0.15 and 0.20 K/h (was: ${store.learnedCoolingRateKPerHour})",
            store.learnedCoolingRateKPerHour in 0.15..0.20)

        // Verify deduplication: re-running with the same data must NOT double-update
        val secondResult = observer.performOnlineCalibration(snapshots, store)
        assertNotNull(secondResult)
        assertFalse("Second run with identical data must not apply duplicate update", secondResult!!.isUpdated)
        assertEquals(1, store.learnedCycleCount)
    }

    @Test
    fun `BuildingEnergyBalanceModel utilizes live measured screed return temp to sharply calibrate reheat need`() {
        val model = BuildingEnergyBalanceModel(
            buildingLossCoefficientKwPerK = 0.150,
            heatPumpNominalThermalPowerKw = 4.5,
            floorScreedThermalCapacityKwhPerK = 5.0,
            baselineInternalGainsKw = 0.35
        )
        val hourlyTemps = List(24) { 10.0 }

        // Case A: Theoretical projection without live sensor reading
        val projectedNeed = model.calculateDailyThermalEnergyNeedKwh(
            targetRoomTempC = 21.5,
            hourlyOutdoorTempsC = hourlyTemps,
            scheduledComfortHours = 8.0,
            coolingRateKPerHour = 0.20
        )

        // Case B: House retained extra heat; live screed return is warm (e.g. 25.5°C vs 26.0°C target -> only 0.5K deficit)
        val warmScreedNeed = model.calculateDailyThermalEnergyNeedKwh(
            targetRoomTempC = 21.5,
            hourlyOutdoorTempsC = hourlyTemps,
            scheduledComfortHours = 8.0,
            liveStartingReturnTempC = 25.5,
            targetComfortReturnTempC = 26.0
        )

        // Reheat need with warm screed is only 5.0 kWh/K * 0.5 K = 2.5 kWh_th
        // Compared to theoretical projection (~5.6 kWh_th reheat), warm screed requires less total energy
        assertTrue("Warm screed should result in lower thermal energy need ($warmScreedNeed < $projectedNeed)",
            warmScreedNeed < projectedNeed)

        // Case C: House experienced high cooling; live screed return is cold (e.g. 24.0°C vs 26.0°C target -> 2.0K deficit)
        val coldScreedNeed = model.calculateDailyThermalEnergyNeedKwh(
            targetRoomTempC = 21.5,
            hourlyOutdoorTempsC = hourlyTemps,
            scheduledComfortHours = 8.0,
            liveStartingReturnTempC = 24.0,
            targetComfortReturnTempC = 26.0
        )
        assertTrue("Cold screed should dynamically increase thermal energy need ($coldScreedNeed > $warmScreedNeed)",
            coldScreedNeed > warmScreedNeed)
    }

    @Test
    fun `ControlUnitSimulator correctly returns standby parasitic load when compressor modulation is zero`() {
        val sim = ControlUnitSimulator(
            parasiticBaselinePowerKw = 0.050,
            standbyPowerKw = 0.030
        )
        // When modulation is 0.0, power must be the 0.050 kW parasitic baseline, NOT clamped to 0.45 kW active minimum!
        val powerAtZero = sim.predictElectricalPowerKw(modulationPct = 0.0, outdoorTempC = 8.0)
        assertEquals(0.050, powerAtZero, 1e-4)

        // When modulation is active (e.g. 35%), power must scale above 0.45 kW
        val powerAtActive = sim.predictElectricalPowerKw(modulationPct = 35.0, outdoorTempC = 8.0)
        assertTrue("Active power should exceed 0.45 kW minimum (was: $powerAtActive)", powerAtActive >= 0.45)
    }

    @Test
    fun `AdaptiveThermalObserver correctly evaluates timestamp ordering across timezone formats`() {
        val store = InMemoryThermalParametersStore(
            learnedBuildingLossKwPerK = 0.150,
            learnedCoolingRateKPerHour = 0.20,
            learnedCycleCount = 1,
            // Last learned stored in Berlin local offset (+01:00)
            lastLearnedTimestampIso = "2026-10-27T02:00:00+01:00"
        )
        val observer = de.breast.telemetry.AdaptiveThermalObserver(
            learningRateEma = 0.20,
            minRSquared = 0.65,
            minDurationHours = 1.0,
            latitude = 52.5200,
            longitude = 13.4050
        )

        // Synthesize an older block (2026-10-26 21:00:00 UTC = 22:00:00+01:00, which is earlier than 2026-10-27T02:00:00+01:00)
        val baseEpoch = 1761516000L // 2026-10-26T21:00:00Z
        val snapshots = (0 until 20).map { i ->
            val t = baseEpoch + i * 900L
            val hours = i * 0.25
            val retT = 20.8 - (0.16 * hours)
            HeatPumpTelemetrySnapshot(
                timestampIso = "2026-10-26T21:${i * 15}:00Z",
                epochSeconds = t,
                supplyTempC = retT + 0.2,
                returnTempC = retT,
                outdoorTempC = 8.0,
                setpointTempC = 17.0,
                modulationPct = 0.0,
                demand = "off",
                chStatus = "off"
            )
        }

        val result = observer.performOnlineCalibration(snapshots, store)
        assertNotNull(result)
        // Must recognize that 2026-10-26T21:...Z is older than 2026-10-27T02:00:00+01:00 and NOT update
        assertFalse("Older nocturnal block must not update state across timezone string representation", result!!.isUpdated)
        assertEquals(1, store.learnedCycleCount)
    }

    @Test
    fun `HolisticScheduleOptimizer target comfort return is physically consistent with 0_65 heating curve slope`() {
        val date = LocalDate.of(2026, 10, 27)
        val dayStart = date.atStartOfDay(SpotPriceClient.BERLIN_ZONE).toInstant().epochSecond
        val tariff = VattenfallTariffConfig(vatRate = 0.19, regionalSurchargeGrossCtPerKwh = 18.50)

        val slots = (0 until 24).map { h ->
            val net = 7.0
            SpotPriceSlot(
                startEpochSeconds = dayStart + h * 3600L,
                endEpochSeconds = dayStart + (h + 1) * 3600L,
                spotEurPerMwh = net * 10.0,
                spotNetCtPerKwh = net,
                totalGrossCtPerKwh = tariff.computeGrossCtPerKwh(net * 10.0)
            )
        }

        val weatherForecast = (0 until 24).map { h ->
            HourlyWeatherForecast(
                dateTime = String.format(java.util.Locale.US, "2026-10-27T%02d:00", h),
                outdoorTempC = 8.0
            )
        }

        val optimizer = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                targetHeatingTempC = 21.5,
                targetDailyRuntimeHours = 8.0,
                preference = OptimizationPreference.BALANCED,
                liveStartingReturnTempC = 24.5
            )
        )

        val plan = optimizer.buildSchedule(date, slots, weatherForecast)
        assertNotNull(plan)
        assertNotNull(plan!!.physicsBreakdown)
        // Target return at 8°C outdoor and 21.5°C room with slope 0.65:
        // Flow = 21.5 + 0.65 * (20 - 8) = 21.5 + 7.8 = 29.3°C; Return = 29.3 - 4.5 = 24.8°C
        assertTrue(
            "Breakdown should report physically consistent target return of ~24.8°C (was: ${plan.physicsBreakdown})",
            plan.physicsBreakdown!!.contains("Target return: 24.8°C")
        )
    }

    @Test
    fun `ElectricityTariffConfig computes gross price matching utility letter contract breakdown`() {
        // Default German tariff: 19% VAT, 21.3446 ct/kWh net working price surcharge (from utility letter)
        val defaultTariff = ElectricityTariffConfig()
        assertEquals(21.3446, defaultTariff.totalNetSurchargeCtPerKwh, 1e-4)
        assertEquals(25.400, defaultTariff.computeGrossCtPerKwh(0.0), 1e-2)
        assertEquals(39.922, defaultTariff.computeGrossCtPerKwh(122.03365), 1e-2)

        // Austrian tariff: 20% VAT
        val austriaTariff = defaultTariff.copy(vatRate = 0.20)
        assertEquals(25.6135, austriaTariff.computeGrossCtPerKwh(0.0), 1e-2)
        // (12.203365 + 21.3446) * 1.20 = 40.2576 ct/kWh
        assertEquals(40.258, austriaTariff.computeGrossCtPerKwh(122.03365), 1e-2)

        // Reduced 7% VAT
        val reducedTariff = defaultTariff.copy(vatRate = 0.07)
        assertEquals(22.839, reducedTariff.computeGrossCtPerKwh(0.0), 1e-2)

        // 0% Tax Exempt
        val exemptTariff = defaultTariff.copy(vatRate = 0.0)
        assertEquals(21.345, exemptTariff.computeGrossCtPerKwh(0.0), 1e-2)
    }

    @Test
    fun `TimeOfUseTimetable accurately classifies night off-peak day peak and weekend full-day off-peak`() {
        val timetable = TimeOfUseTimetable(
            peakPriceGrossCtPerKwh = 38.0,
            offPeakPriceGrossCtPerKwh = 24.0,
            offPeakStartHour = 22,
            offPeakEndHour = 6,
            weekendAllOffPeak = true
        )
        val zone = SpotPriceClient.BERLIN_ZONE

        // Wednesday 14:00 (Weekday Peak)
        val wednesdayDay = java.time.ZonedDateTime.of(2026, 11, 4, 14, 0, 0, 0, zone)
        assertFalse(timetable.isOffPeak(wednesdayDay))
        assertEquals(38.0, timetable.getGrossPrice(wednesdayDay), 1e-3)

        // Wednesday 22:30 (Weekday Off-Peak Night Start)
        val wednesdayNight = java.time.ZonedDateTime.of(2026, 11, 4, 22, 30, 0, 0, zone)
        assertTrue(timetable.isOffPeak(wednesdayNight))
        assertEquals(24.0, timetable.getGrossPrice(wednesdayNight), 1e-3)

        // Thursday 03:00 (Weekday Off-Peak Past Midnight)
        val thursdayEarly = java.time.ZonedDateTime.of(2026, 11, 5, 3, 0, 0, 0, zone)
        assertTrue(timetable.isOffPeak(thursdayEarly))
        assertEquals(24.0, timetable.getGrossPrice(thursdayEarly), 1e-3)

        // Thursday 05:59 (Weekday Off-Peak Before End)
        val thursdayBeforeEnd = java.time.ZonedDateTime.of(2026, 11, 5, 5, 59, 0, 0, zone)
        assertTrue(timetable.isOffPeak(thursdayBeforeEnd))

        // Thursday 06:01 (Weekday Peak After End)
        val thursdayMorning = java.time.ZonedDateTime.of(2026, 11, 5, 6, 1, 0, 0, zone)
        assertFalse(timetable.isOffPeak(thursdayMorning))
        assertEquals(38.0, timetable.getGrossPrice(thursdayMorning), 1e-3)

        // Saturday 12:00 (Weekend All-Day Off-Peak)
        val saturdayNoon = java.time.ZonedDateTime.of(2026, 11, 7, 12, 0, 0, 0, zone)
        assertTrue(timetable.isOffPeak(saturdayNoon))
        assertEquals(24.0, timetable.getGrossPrice(saturdayNoon), 1e-3)

        // Sunday 20:00 (Weekend All-Day Off-Peak)
        val sundayEvening = java.time.ZonedDateTime.of(2026, 11, 8, 20, 0, 0, 0, zone)
        assertTrue(timetable.isOffPeak(sundayEvening))
        assertEquals(24.0, timetable.getGrossPrice(sundayEvening), 1e-3)
    }

    @Test
    fun `ElectricityTariffConfig generateSyntheticSlots generates full 24h slots with exact peak and off-peak pricing`() {
        val date = LocalDate.of(2026, 11, 4) // Wednesday
        val tariff = ElectricityTariffConfig(
            pricingModel = TariffPricingModel.TIME_OF_USE,
            touTimetable = TimeOfUseTimetable(
                peakPriceGrossCtPerKwh = 38.0,
                offPeakPriceGrossCtPerKwh = 24.0,
                offPeakStartHour = 22,
                offPeakEndHour = 6,
                weekendAllOffPeak = true
            )
        )
        val slots = tariff.generateSyntheticSlots(date, date)
        assertEquals(96, slots.size) // 96 x 15-minute intervals = 24 hours

        val client = SpotPriceClient(OkHttpClient(), tariff)
        assertTrue("Synthetic slots must cover full day", client.hasCompleteDayPrices(date, slots))

        // Slot at 02:00 (index 8: 8 * 15m = 2h) must have off-peak rate 24.0 ct/kWh
        assertEquals(24.0, slots[8].totalGrossCtPerKwh, 1e-3)

        // Slot at 12:00 (index 48: 48 * 15m = 12h) must have peak rate 38.0 ct/kWh
        assertEquals(38.0, slots[48].totalGrossCtPerKwh, 1e-3)
    }

    @Test
    fun `HolisticScheduleOptimizer schedules heating cleanly into off-peak window under Time-of-Use pricing`() {
        val date = LocalDate.of(2026, 11, 4) // Wednesday
        val tariff = ElectricityTariffConfig(
            pricingModel = TariffPricingModel.TIME_OF_USE,
            touTimetable = TimeOfUseTimetable(
                peakPriceGrossCtPerKwh = 38.0,
                offPeakPriceGrossCtPerKwh = 24.0,
                offPeakStartHour = 22,
                offPeakEndHour = 6,
                weekendAllOffPeak = true
            )
        )
        val slots = tariff.generateSyntheticSlots(date, date)

        // Uniform mild weather forecast so COP does not overpower price spread
        val weatherForecast = (0 until 24).map { h ->
            HourlyWeatherForecast(
                dateTime = String.format(java.util.Locale.US, "2026-11-04T%02d:00", h),
                outdoorTempC = 7.0
            )
        }

        val optimizer = HolisticScheduleOptimizer(
            config = HolisticOptimizationConfig(
                targetHeatingTempC = 21.5,
                targetDailyRuntimeHours = 6.0,
                preference = OptimizationPreference.BUDGET
            )
        )

        val plan = optimizer.buildSchedule(date, slots, weatherForecast)
        assertNotNull(plan)
        assertEquals(1, plan!!.sharedWindows.size)
        val window = plan.sharedWindows[0]

        // Under 38 ct vs 24 ct pricing with flat weather, the budget optimizer must schedule during off-peak hours (00:00-06:00)
        val startHour = window.startMinutes / 60
        assertTrue(
            "Under TOU pricing, scheduled start ($startHour:00) must be in the off-peak window (00:00 to 06:00 or 22:00 to 24:00)",
            startHour in 0..5 || startHour in 22..23
        )
    }

    @Test
    fun `Price input text parsing supports European comma and standard dot decimals`() {
        fun parseInput(raw: String?): Double? {
            return raw?.trim()?.replace(',', '.')?.toDoubleOrNull()
        }

        // Standard dot notation
        assertEquals(21.3446, parseInput("21.3446")!!, 1e-4)
        assertEquals(13.80, parseInput("13.80")!!, 1e-4)
        assertEquals(19.0, parseInput("19.0")!!, 1e-4)

        // European comma notation (standard on German/Austrian phone keyboards)
        assertEquals(21.3446, parseInput("21,3446")!!, 1e-4)
        assertEquals(13.80, parseInput("13,80")!!, 1e-4)
        assertEquals(19.0, parseInput("19,0")!!, 1e-4)
        assertEquals(38.5, parseInput(" 38,5 ")!!, 1e-4)
        assertEquals(0.0, parseInput("0,0")!!, 1e-4)

        // Invalid or empty input produces null safely
        assertNull(parseInput(""))
        assertNull(parseInput("   "))
        assertNull(parseInput("abc"))
        assertNull(parseInput("21,34,56"))
    }

    @Test
    fun `Price input formatting formats clean decimals without trailing zero clutter`() {
        fun formatPriceValue(value: Double, maxDecimals: Int = 4): String {
            return if (value == value.toLong().toDouble()) {
                String.format(java.util.Locale.US, "%.1f", value)
            } else {
                val s = String.format(java.util.Locale.US, "%.${maxDecimals}f", value)
                s.trimEnd('0').let { if (it.endsWith('.')) "${it}0" else it }
            }
        }

        assertEquals("21.3446", formatPriceValue(21.3446, 4))
        assertEquals("13.8", formatPriceValue(13.80, 2))
        assertEquals("19.0", formatPriceValue(19.0, 2))
        assertEquals("0.0", formatPriceValue(0.0, 2))
        assertEquals("38.0", formatPriceValue(38.0, 2))
        assertEquals("24.5", formatPriceValue(24.50, 2))
        assertEquals("21.345", formatPriceValue(21.3450, 4))
    }

    @Test
    fun `BreastI18n localization provides complete strings across EN, DE, and ZH without blank fields`() {
        for (lang in de.breast.auth.AppLanguage.entries) {
            val strings = de.breast.ui.BreastI18n.get(lang)
            assertNotNull(strings)
            assertTrue("App title must not be blank for $lang", strings.appTitle.isNotBlank())
            assertTrue("Settings title must not be blank for $lang", strings.settingsTitle.isNotBlank())
            assertTrue("Done label must not be blank for $lang", strings.done.isNotBlank())
            assertTrue("Refresh button must not be blank for $lang", strings.refreshTelemetryBtn.isNotBlank())
            assertTrue("Dynamic spot model label must not be blank for $lang", strings.dynamicSpotModel.isNotBlank())
            assertTrue("ToU model label must not be blank for $lang", strings.touModel.isNotBlank())
            assertTrue("Flat rate model label must not be blank for $lang", strings.flatRateModel.isNotBlank())
            assertTrue("Holistic section title must not be blank for $lang", strings.holisticSectionTitle.isNotBlank())
            assertTrue("Sources section title must not be blank for $lang", strings.sourcesSectionTitle.isNotBlank())
            assertTrue("Telemetry recording title must not be blank for $lang", strings.telemetryRecSectionTitle.isNotBlank())
            assertTrue("Audit title must not be blank for $lang", strings.todayPhysicsAuditTitle.isNotBlank())
            assertTrue("Contract formula detail must not be blank for $lang", strings.contractFormulaDetail.isNotBlank())
            assertTrue("ToU summary detail must not be blank for $lang", strings.touSummaryDetail.isNotBlank())
            assertTrue("Flat rate note desc must not be blank for $lang", strings.flatRateNoteDesc.isNotBlank())
            assertTrue("Setback scan message must not be blank for $lang", strings.scanningSetbackMsg.isNotBlank())
            assertTrue("Modulation calibration message must not be blank for $lang", strings.modCalibratedMsg.isNotBlank())
        }

        // Verify language-specific keywords
        val enStrings = de.breast.ui.BreastI18n.get(de.breast.auth.AppLanguage.EN)
        val deStrings = de.breast.ui.BreastI18n.get(de.breast.auth.AppLanguage.DE)
        val zhStrings = de.breast.ui.BreastI18n.get(de.breast.auth.AppLanguage.ZH)

        assertEquals("Settings", enStrings.settingsTitle)
        assertEquals("Einstellungen", deStrings.settingsTitle)
        assertEquals("系统设置", zhStrings.settingsTitle)

        assertEquals("Refresh Telemetry", enStrings.refreshTelemetryBtn)
        assertEquals("Telemetrie aktualisieren", deStrings.refreshTelemetryBtn)
        assertEquals("刷新遥测数据", zhStrings.refreshTelemetryBtn)

        assertEquals("Smart Heat Pump", enStrings.defaultHeatPumpName)
        assertEquals("Smarte Wärmepumpe", deStrings.defaultHeatPumpName)
        assertEquals("智能热泵", zhStrings.defaultHeatPumpName)

        assertEquals("Close", enStrings.close)
        assertEquals("Schließen", deStrings.close)
        assertEquals("关闭", zhStrings.close)

        // Telemetry descriptions & keys
        assertEquals(" (+0 K Base)", enStrings.telemetryShiftBase)
        assertEquals(" (+0 K Basis)", deStrings.telemetryShiftBase)
        assertEquals(" (+0 K 基准)", zhStrings.telemetryShiftBase)

        // Cascade Priority
        assertEquals("Priority Cascade:\n", enStrings.cascadePriorityTitle)
        assertEquals("Prioritäts-Kaskade:\n", deStrings.cascadePriorityTitle)
        assertEquals("数据源优先级级联:\n", zhStrings.cascadePriorityTitle)

        // Weather Diagnostics
        assertTrue(deStrings.weatherOfflineMsg.contains("Wetterdienst offline"))
        assertTrue(zhStrings.weatherOfflineMsg.contains("气象接口离线"))

        // Setback & Modulation Analysis
        assertEquals("Heat Loss Rate", enStrings.chipHeatLossRate)
        assertEquals("Wärmeverlustrate", deStrings.chipHeatLossRate)
        assertEquals("散热损耗速率", zhStrings.chipHeatLossRate)

        assertEquals("Baseline Mod", enStrings.chipBaselineMod)
        assertEquals("Basis-Modulation", deStrings.chipBaselineMod)
        assertEquals("基准调制率", zhStrings.chipBaselineMod)
    }

    @Test
    fun `BreastI18n notification localization provides complete localized strings across EN, DE, and ZH`() {
        for (lang in de.breast.auth.AppLanguage.entries) {
            val notif = de.breast.ui.BreastI18n.notificationsFor(lang)
            assertNotNull(notif)
            assertTrue("channelAuthName must not be blank for $lang", notif.channelAuthName.isNotBlank())
            assertTrue("channelAuthDesc must not be blank for $lang", notif.channelAuthDesc.isNotBlank())
            assertTrue("channelSyncName must not be blank for $lang", notif.channelSyncName.isNotBlank())
            assertTrue("channelSyncDesc must not be blank for $lang", notif.channelSyncDesc.isNotBlank())
            assertTrue("channelBgName must not be blank for $lang", notif.channelBgName.isNotBlank())
            assertTrue("channelBgDesc must not be blank for $lang", notif.channelBgDesc.isNotBlank())
            assertTrue("authAlertTitle must not be blank for $lang", notif.authAlertTitle.isNotBlank())
            assertTrue("authAlertTapPrompt must not be blank for $lang", notif.authAlertTapPrompt.isNotBlank())
            assertTrue("syncStatusTitle must not be blank for $lang", notif.syncStatusTitle.isNotBlank())
            assertTrue("syncStatusSummaryFormat must not be blank for $lang", notif.syncStatusSummaryFormat.isNotBlank())
            assertTrue("bgTitleTelemetryAndAuto must not be blank for $lang", notif.bgTitleTelemetryAndAuto.isNotBlank())
            assertTrue("bgContentTelemetryAndAuto must not be blank for $lang", notif.bgContentTelemetryAndAuto.isNotBlank())
            assertTrue("bgBigTextTelemetryAndAuto must not be blank for $lang", notif.bgBigTextTelemetryAndAuto.isNotBlank())
            assertTrue("bgTitleTelemetryOnly must not be blank for $lang", notif.bgTitleTelemetryOnly.isNotBlank())
            assertTrue("bgContentTelemetryOnly must not be blank for $lang", notif.bgContentTelemetryOnly.isNotBlank())
            assertTrue("bgBigTextTelemetryOnly must not be blank for $lang", notif.bgBigTextTelemetryOnly.isNotBlank())
            assertTrue("bgTitleSyncOnly must not be blank for $lang", notif.bgTitleSyncOnly.isNotBlank())
            assertTrue("bgContentSyncOnly must not be blank for $lang", notif.bgContentSyncOnly.isNotBlank())
            assertTrue("bgBigTextSyncOnly must not be blank for $lang", notif.bgBigTextSyncOnly.isNotBlank())
            assertTrue("interval1Min must not be blank for $lang", notif.interval1Min.isNotBlank())
            assertTrue("interval1Hour must not be blank for $lang", notif.interval1Hour.isNotBlank())
            assertTrue("intervalNMinFormat must not be blank for $lang", notif.intervalNMinFormat.isNotBlank())
        }

        val enNotif = de.breast.ui.BreastI18n.notificationsFor(de.breast.auth.AppLanguage.EN)
        val deNotif = de.breast.ui.BreastI18n.notificationsFor(de.breast.auth.AppLanguage.DE)
        val zhNotif = de.breast.ui.BreastI18n.notificationsFor(de.breast.auth.AppLanguage.ZH)

        assertEquals("SingleKey ID Login Alerts", enNotif.channelAuthName)
        assertEquals("SingleKey ID Anmeldehinweise", deNotif.channelAuthName)
        assertEquals("SingleKey ID 登录提醒", zhNotif.channelAuthName)

        assertEquals("Daily Heat Pump Schedule Sync", enNotif.channelSyncName)
        assertEquals("Täglicher Wärmepumpen-Sync", deNotif.channelSyncName)
        assertEquals("每日热泵调度同步", zhNotif.channelSyncName)

        assertEquals("Background Telemetry & Automation Badge", enNotif.channelBgName)
        assertEquals("Hintergrund-Telemetrie & Automatisierungs-Status", deNotif.channelBgName)
        assertEquals("后台遥测与自动化状态常驻通知", zhNotif.channelBgName)

        assertEquals("Bosch SingleKey ID Login Needed", enNotif.authAlertTitle)
        assertEquals("Bosch SingleKey ID Anmeldung erforderlich", deNotif.authAlertTitle)
        assertEquals("需要登录博世 SingleKey ID", zhNotif.authAlertTitle)

        assertEquals("Heat Pump Schedule Updated", enNotif.syncStatusTitle)
        assertEquals("Wärmepumpen-Zeitplan aktualisiert", deNotif.syncStatusTitle)
        assertEquals("热泵运行计划已更新", zhNotif.syncStatusTitle)

        // Test auth error message localization
        val authErr = "No SingleKey ID refresh_token available. Please log in."
        assertEquals("Nicht bei Bosch SingleKey ID angemeldet. Bitte anmelden.", de.breast.ui.BreastI18n.localizeAuthErrorMessage(authErr, de.breast.auth.AppLanguage.DE))
        assertEquals("未登录博世 SingleKey ID，请点击登录。", de.breast.ui.BreastI18n.localizeAuthErrorMessage(authErr, de.breast.auth.AppLanguage.ZH))
        assertEquals(authErr, de.breast.ui.BreastI18n.localizeAuthErrorMessage(authErr, de.breast.auth.AppLanguage.EN))
    }

    @Test
    fun `BreastI18n helper methods dynamically translate physics audit, plan summaries, and curve summaries`() {
        val samplePlan = "02:00 – 06:00 (4.0 h) • Standard Window\nAvg 24.5 ct/kWh gross • Est. 1.85 € win (2.50 € 24h) (5.2 kWh win • 7.1 kWh 24h @ ~35% mod) • Target 21.0 °C\nTranslated Switch Points:\n  ► 02:00  comfort2 @ 21.0°C (HEATING ON)  •  DHW low @ 42°C\n  ► 06:00  setback @ 17.0°C (SETBACK)  •  DHW off"

        val dePlan = de.breast.ui.BreastI18n.localizePlanSummary(samplePlan, de.breast.auth.AppLanguage.DE)
        assertTrue("DE plan must translate Standard Window", dePlan.contains("Standard-Zeitfenster"))
        assertTrue("DE plan must translate Translated Switch Points", dePlan.contains("Schaltpunkte Reglerprogramm:"))
        assertTrue("DE plan must translate HEATING ON", dePlan.contains("(HEIZEN AN)"))
        assertTrue("DE plan must translate SETBACK", dePlan.contains("(ABSENKUNG)"))
        assertTrue("DE plan must translate DHW low", dePlan.contains("Warmwasser 42°C"))
        assertTrue("DE plan must translate DHW off", dePlan.contains("Warmwasser Aus"))

        val zhPlan = de.breast.ui.BreastI18n.localizePlanSummary(samplePlan, de.breast.auth.AppLanguage.ZH)
        assertTrue("ZH plan must translate Standard Window", zhPlan.contains("标准窗口"))
        assertTrue("ZH plan must translate Translated Switch Points", zhPlan.contains("下发至热泵的定时开关点:"))
        assertTrue("ZH plan must translate HEATING ON", zhPlan.contains("(采暖开启)"))
        assertTrue("ZH plan must translate SETBACK", zhPlan.contains("(夜间回落)"))
        assertTrue("ZH plan must translate DHW low", zhPlan.contains("生活热水 42°C"))
        assertTrue("ZH plan must translate DHW off", zhPlan.contains("生活热水 关闭"))

        val sampleAudit = "1. Weather Conditions (DWD ICON Forecast):\n   • 24h Average Outdoor Temp: 5.2°C (Min 2.0°C, Max 8.0°C)\n2. Thermal Demand (2R2C Coupled State-Space):\n   • Building Loss Coeff (Hbld): 0.180 kW/K (Calibrated baseline)\n3. Inverter Modulation & Thermodynamics:\n   • Target Flow Temp: 32.5°C (Carnot Lift: 27.3 K)\n4. Tariff & Cost:\n   • Active Window Operating Cost: 1.85 €\n5. Dynamic Runtime Corridor (Harmonized Quantizer):\n   • Preference Mode: Comfort (+25% expansion allowance)"

        val deAudit = de.breast.ui.BreastI18n.localizePhysicsAudit(sampleAudit, de.breast.auth.AppLanguage.DE)
        assertTrue("DE audit must translate Weather Conditions", deAudit.contains("1. Wetterbedingungen (DWD ICON Vorhersage):"))
        assertTrue("DE audit must translate Thermal Demand", deAudit.contains("2. Wärmebedarf (2R2C gekoppeltes Zustandsraummodell):"))
        assertTrue("DE audit must translate Thermodynamics", deAudit.contains("3. Inverter-Modulation & Thermodynamik:"))
        assertTrue("DE audit must translate Tariff & Cost", deAudit.contains("4. Stromtarif & Kosten:"))
        assertTrue("DE audit must translate Runtime Corridor", deAudit.contains("5. Dynamischer Laufzeitkorridor (Harmonisierter Quantisierer):"))
        assertTrue("DE audit must translate Comfort", deAudit.contains("Komfort"))

        val zhAudit = de.breast.ui.BreastI18n.localizePhysicsAudit(sampleAudit, de.breast.auth.AppLanguage.ZH)
        assertTrue("ZH audit must translate Weather Conditions", zhAudit.contains("1. 气象条件 (德国气象局 DWD ICON 预报):"))
        assertTrue("ZH audit must translate Thermal Demand", zhAudit.contains("2. 需热量解算 (2R2C 状态空间耦合模型):"))
        assertTrue("ZH audit must translate Thermodynamics", zhAudit.contains("3. 变频调制与热力学状态:"))
        assertTrue("ZH audit must translate Tariff & Cost", zhAudit.contains("4. 电价资费与成本明细:"))
        assertTrue("ZH audit must translate Runtime Corridor", zhAudit.contains("5. 动态运行时长走廊 (阶梯量化器):"))
        assertTrue("ZH audit must translate Comfort", zhAudit.contains("舒适优先"))

        val sampleCurve = "Optimal Bosch underfloor curve (Slope 0.45, Flow @0°C: 31.0°C). Very good heat pump efficiency."
        val deCurve = de.breast.ui.BreastI18n.localizeCurveSummary(sampleCurve, de.breast.auth.AppLanguage.DE)
        assertTrue("DE curve summary translated", deCurve.contains("Optimale Bosch Fußboden-Heizkurve"))
        val zhCurve = de.breast.ui.BreastI18n.localizeCurveSummary(sampleCurve, de.breast.auth.AppLanguage.ZH)
        assertTrue("ZH curve summary translated", zhCurve.contains("博世地暖标准最优曲线"))
    }

    @Test
    fun `SettingsAnalysisPresenter buildSetbackUiModel executes cleanly across EN, DE, and ZH without formatting crashes`() {
        val mockResultWithPrior = de.breast.telemetry.SetbackEstimationResult(
            sampleCount = 45,
            durationHours = 3.5,
            coolingRateKPerHour = 0.22,
            avgOutdoorTempC = 2.4,
            avgIndoorTempC = 21.0,
            estimatedLossKwPerK = 0.135,
            estimatedLossWPerK = 135,
            timestampStartIso = "2026-10-28T22:00:00Z",
            timestampEndIso = "2026-10-29T01:30:00Z",
            rSquared = 0.94,
            temperatureDropK = 0.77,
            blockCount = 2,
            weightedLossWPerK = 142,
            effectiveCapacityKwhPerK = 6.4,
            historicalPriorIncorporated = true,
            historicalPriorWeight = 25000.0
        )

        val mockResultWithoutPrior = de.breast.telemetry.SetbackEstimationResult(
            sampleCount = 20,
            durationHours = 2.0,
            coolingRateKPerHour = 0.15,
            avgOutdoorTempC = 5.0,
            avgIndoorTempC = 21.5,
            estimatedLossKwPerK = 0.120,
            estimatedLossWPerK = 120,
            timestampStartIso = "2026-10-28T23:00:00Z",
            timestampEndIso = "2026-10-29T01:00:00Z",
            rSquared = 0.88,
            temperatureDropK = 0.30,
            blockCount = 1,
            weightedLossWPerK = null,
            effectiveCapacityKwhPerK = 5.0,
            historicalPriorIncorporated = false,
            historicalPriorWeight = 0.0
        )

        for (lang in de.breast.auth.AppLanguage.entries) {
            val i18n = de.breast.ui.BreastI18n.get(lang)

            // Test 1: Prior active
            val modelWithPrior = de.breast.ui.SettingsAnalysisPresenter.buildSetbackUiModel(
                result = mockResultWithPrior,
                historicalPriorLoss = 145,
                historicalWeight = 25000.0,
                i18n = i18n
            )
            assertNotNull("Model with prior must not be null for $lang", modelWithPrior)
            assertEquals(142, modelWithPrior!!.chosenLossInt)
            assertTrue("Status text populated", modelWithPrior.statusText.isNotBlank())
            assertEquals(4, modelWithPrior.badges.size)
            assertTrue("Anchor badge present", modelWithPrior.badges.any { it.label == i18n.anchorActiveBadge })
            assertEquals(i18n.chipHeatLossRate, modelWithPrior.chip1.title)
            assertEquals("142 W/K", modelWithPrior.chip1.value)
            assertEquals(5, modelWithPrior.nuanceItems.size)
            assertTrue("Apply button text contains loss", modelWithPrior.applyButtonText.contains("142"))

            // Test 2: Prior inactive
            val modelWithoutPrior = de.breast.ui.SettingsAnalysisPresenter.buildSetbackUiModel(
                result = mockResultWithoutPrior,
                historicalPriorLoss = 145,
                historicalWeight = 0.0,
                i18n = i18n
            )
            assertNotNull("Model without prior must not be null for $lang", modelWithoutPrior)
            assertEquals(120, modelWithoutPrior!!.chosenLossInt)
            assertFalse("Anchor badge absent", modelWithoutPrior.badges.any { it.label == i18n.anchorActiveBadge })
            assertTrue("Apply button text contains loss", modelWithoutPrior.applyButtonText.contains("120"))

            // Test 3: Null result
            val nullModel = de.breast.ui.SettingsAnalysisPresenter.buildSetbackUiModel(
                result = null,
                historicalPriorLoss = 145,
                historicalWeight = 0.0,
                i18n = i18n
            )
            assertNull(nullModel)
        }
    }

    @Test
    fun `SettingsAnalysisPresenter buildModulationUiModel executes cleanly across EN, DE, and ZH without formatting crashes`() {
        val mockProfileWithPrior = de.breast.telemetry.EmpiricalModulationProfile(
            totalHeatingSamples = 520,
            steadyStateSamples = 410,
            detectedRunsCount = 14,
            avgSteadyModulationPct = 38.5,
            avgSteadyOutdoorTempC = 4.2,
            avgSteadyDeltaTC = 4.8,
            inferredSteadyPowerKw = 1.45,
            warmupModulationOverheadFactor = 1.18,
            modulationAt10C = 32.4,
            modulationSlopePerK = 1.65,
            minObservedModulationPct = 28.0,
            maxObservedModulationPct = 72.0,
            evaluationSummary = "Calibrated profile",
            matchedSetpointTempC = 21.5,
            rSquared = 0.93,
            minObservedOutdoorTempC = -2.1,
            maxObservedOutdoorTempC = 11.4,
            avgWarmupModulationPct = 52.0,
            warmupSamplesCount = 42,
            historicalPriorIncorporated = true,
            historicalPriorWeight = 25000.0
        )

        for (lang in de.breast.auth.AppLanguage.entries) {
            val i18n = de.breast.ui.BreastI18n.get(lang)

            // Test 1: Full profile with historical prior
            val model = de.breast.ui.SettingsAnalysisPresenter.buildModulationUiModel(
                profile = mockProfileWithPrior,
                targetTemp = 21.5,
                historicalModAt10C = 30.0,
                historicalWeight = 25000,
                i18n = i18n
            )
            assertNotNull("Modulation model must not be null for $lang", model)
            assertTrue(model!!.statusText.isNotBlank())
            assertEquals(4, model.badges.size)
            assertTrue("Anchor badge present", model.badges.any { it.label == i18n.anchorActiveBadge })
            assertEquals(i18n.chipBaselineMod, model.chip1.title)
            assertEquals("32.4% @ 10°C", model.chip1.value)
            assertEquals(4, model.nuanceItems.size)
            assertTrue("Nuance box title non-blank", model.nuanceTitle.isNotBlank())
            assertTrue("Projections table generated", model.projections.isNotEmpty())
            assertTrue("Apply button text non-blank", model.applyButtonText.contains("32.4"))

            // Test 2: Null profile
            val nullModel = de.breast.ui.SettingsAnalysisPresenter.buildModulationUiModel(
                profile = null,
                targetTemp = 21.5,
                historicalModAt10C = 30.0,
                historicalWeight = 25000,
                i18n = i18n
            )
            assertNull(nullModel)
        }
    }

    @Test
    fun `SettingsAnalysisPresenter executeSafeClearDatabase in dev mode safely copies and deletes copy while preserving real database`() {
        val tempDir = java.nio.file.Files.createTempDirectory("breast_test_db").toFile()
        try {
            val originalDb = java.io.File(tempDir, "breast_telemetry.db")
            originalDb.writeText("CRITICAL_USER_TELEMETRY_DATA_SNAPSHOT_12345")
            val cacheDir = java.io.File(tempDir, "cache")
            cacheDir.mkdirs()

            var productionClearCalled = false

            // Action: In Dev Mode (isDevMode = true)
            val result = de.breast.ui.SettingsAnalysisPresenter.executeSafeClearDatabase(
                isDevMode = true,
                originalDbFile = originalDb,
                cacheDir = cacheDir,
                onClearOriginalDb = { productionClearCalled = true }
            )

            // Assertions
            assertTrue("Dev mode reported in result", result.isDevMode)
            assertTrue("Copy was created during verification", result.copyCreated)
            assertTrue("Copy was deleted during cleanup", result.copyDeleted)
            assertTrue("Operation marked successful", result.success)
            assertFalse("Production clear MUST NOT be called in dev mode", productionClearCalled)
            assertTrue("Original database file MUST still exist", originalDb.exists())
            assertEquals(
                "Original database content MUST NOT be modified or cleared",
                "CRITICAL_USER_TELEMETRY_DATA_SNAPSHOT_12345",
                originalDb.readText()
            )

            // Action: In Production Mode (isDevMode = false)
            val prodResult = de.breast.ui.SettingsAnalysisPresenter.executeSafeClearDatabase(
                isDevMode = false,
                originalDbFile = originalDb,
                cacheDir = cacheDir,
                onClearOriginalDb = { productionClearCalled = true; originalDb.delete() }
            )
            assertFalse("Production mode reported", prodResult.isDevMode)
            assertTrue("Production clear was invoked", productionClearCalled)
            assertFalse("Original database was wiped by onClearOriginalDb", originalDb.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `KeystoreSecretStore encrypt and decrypt round trip preserves secret string`() {
        val sampleSecret = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.sensitive_payload_token_98765"
        val encrypted = de.breast.auth.KeystoreSecretStore.encrypt(sampleSecret)

        assertNotNull("Encryption should produce non-null ciphertext", encrypted)
        assertFalse("Ciphertext must not equal plaintext", encrypted == sampleSecret)
        assertFalse("Ciphertext must not contain sensitive token content", encrypted!!.contains("sensitive_payload_token"))

        val decrypted = de.breast.auth.KeystoreSecretStore.decrypt(encrypted)
        assertNotNull("Decryption must succeed", decrypted)
        assertEquals("Decrypted plaintext must match original exactly", sampleSecret, decrypted)
    }

    @Test
    fun `KeystoreSecretStore decrypt handles invalid or corrupted ciphertext safely`() {
        assertNull("Empty string returns null", de.breast.auth.KeystoreSecretStore.decrypt(""))
        assertNull("Truncated bytes return null", de.breast.auth.KeystoreSecretStore.decrypt("AAAA"))
        assertNull("Garbage base64 returns null", de.breast.auth.KeystoreSecretStore.decrypt("bm90X2FfZm9ybWF0dGVkX2NpcGhlcnRleHQ="))
    }
}
