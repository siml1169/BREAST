package de.breast.auth

import android.content.Context
import de.breast.daemon.ScheduleStateStore
import de.breast.daemon.ScheduleSyncState
import de.breast.optimizer.HeatingTemperatureSlider
import de.breast.optimizer.HolisticOptimizationConfig
import de.breast.optimizer.OptimizationPreference
import de.breast.optimizer.SwitchScheduleConfig
import de.breast.spot.ElectricityTariffConfig
import de.breast.spot.SpotPriceSource
import de.breast.spot.TariffPricingModel
import de.breast.spot.TimeOfUseTimetable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Android `SharedPreferences`-backed implementations of [TokenStore], [ScheduleStateStore],
 * and user settings persistence (Slider Target Temperature, Daily Budget in EUR, Heat Pump kW).
 */
class AndroidPrefsTokenStore(context: Context) : TokenStore {
    private val prefs = context.applicationContext.getSharedPreferences("breast_auth_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun loadTokens(): OAuthTokens? {
        val raw = prefs.getString("oauth_tokens_json", null) ?: return null
        val trimmed = raw.trimStart()

        // 1. Transparent migration from legacy unencrypted JSON
        if (trimmed.startsWith("{")) {
            val legacyTokens = runCatching { json.decodeFromString<OAuthTokens>(trimmed) }.getOrNull()
            if (legacyTokens != null) {
                // Transparently migrate to hardware-backed encrypted storage
                saveTokens(legacyTokens)
            }
            return legacyTokens
        }

        // 2. Decrypt with Keystore
        val decrypted = KeystoreSecretStore.decrypt(raw) ?: return null
        return runCatching { json.decodeFromString<OAuthTokens>(decrypted) }.getOrNull()
    }

    override suspend fun saveTokens(tokens: OAuthTokens) {
        val serialized = json.encodeToString(tokens)
        val encrypted = KeystoreSecretStore.encrypt(serialized) ?: serialized
        prefs.edit().putString("oauth_tokens_json", encrypted).apply()
    }

    override suspend fun clearTokens() {
        prefs.edit().remove("oauth_tokens_json").apply()
    }

    override suspend fun savePendingPkceVerifier(verifier: String) {
        val encrypted = KeystoreSecretStore.encrypt(verifier) ?: verifier
        prefs.edit().putString("pkce_verifier", encrypted).apply()
    }

    override suspend fun loadPendingPkceVerifier(): String? {
        val raw = prefs.getString("pkce_verifier", null) ?: return null
        val decrypted = KeystoreSecretStore.decrypt(raw)
        return decrypted ?: raw
    }
}

class AndroidPrefsScheduleStateStore(context: Context) : ScheduleStateStore {
    private val prefs = context.applicationContext.getSharedPreferences("breast_sync_state", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun loadState(): ScheduleSyncState {
        val raw = prefs.getString("sync_state_json", null) ?: return ScheduleSyncState()
        return runCatching { json.decodeFromString<ScheduleSyncState>(raw) }.getOrDefault(ScheduleSyncState())
    }

    override suspend fun saveState(state: ScheduleSyncState) {
        prefs.edit().putString("sync_state_json", json.encodeToString(state)).apply()
    }
}

enum class AppLanguage(val code: String, val title: String) {
    EN("en", "English"),
    DE("de", "Deutsch"),
    ZH("zh", "中文");

    companion object {
        fun fromCode(code: String): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: EN
    }
}

class UserSettingsStore(context: Context) : de.breast.telemetry.ThermalParametersStore {
    private val prefs = context.applicationContext.getSharedPreferences("breast_user_settings", Context.MODE_PRIVATE)

    var heatPumpName: String
        get() = prefs.getString("heat_pump_custom_name", "Smart Heat Pump") ?: "Smart Heat Pump"
        set(value) = prefs.edit().putString("heat_pump_custom_name", value.trim().ifEmpty { "Smart Heat Pump" }).apply()

    var appLanguage: AppLanguage
        get() = AppLanguage.fromCode(prefs.getString("app_language", "en") ?: "en")
        set(value) = prefs.edit().putString("app_language", value.code).apply()

    override val baseBuildingLossKwPerK: Double get() = buildingLossKwPerK

    override var sliderTempC: Double
        get() = prefs.getFloat("slider_temp_c", 21.5f).toDouble()
        set(value) = prefs.edit().putFloat("slider_temp_c", value.toFloat()).apply()

    var targetOperationHours: Double
        get() = prefs.getFloat("target_operation_hours", 6.0f).toDouble()
        set(value) = prefs.edit().putFloat("target_operation_hours", value.toFloat()).apply()

    var dailyBudgetEuro: Double?
        get() {
            val v = prefs.getFloat("daily_budget_euro", -1f)
            return if (v > 0f) v.toDouble() else null
        }
        set(value) {
            if (value == null || value <= 0.0) {
                prefs.edit().remove("daily_budget_euro").apply()
            } else {
                prefs.edit().putFloat("daily_budget_euro", value.toFloat()).apply()
            }
        }

    var autoPowerModulationEnabled: Boolean
        get() = prefs.getBoolean("auto_power_modulation_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_power_modulation_enabled", value).apply()

    var groundTruthModulationEnabled: Boolean
        get() = prefs.getBoolean("ground_truth_modulation_enabled", true)
        set(value) = prefs.edit().putBoolean("ground_truth_modulation_enabled", value).apply()

    var calibratedModulationAt10C: Double
        get() = prefs.getFloat("calibrated_mod_at_10c", 42.0f).toDouble()
        set(value) = prefs.edit().putFloat("calibrated_mod_at_10c", value.toFloat()).apply()

    var calibratedModulationSlopePerK: Double
        get() = prefs.getFloat("calibrated_mod_slope_per_k", 5.0f).toDouble()
        set(value) = prefs.edit().putFloat("calibrated_mod_slope_per_k", value.toFloat()).apply()

    /**
     * Long-term 25k Historical Modulation Baseline Anchor:
     * Stores the cumulative weighted average of modulation parameters across past 25,000-datapoint epochs.
     * Incorporates historical learning into new incoming telemetry batches without expanding memory bounds.
     */
    var historicalModulation25kAt10C: Double
        get() = prefs.getFloat("hist_mod_25k_at_10c", 42.0f).toDouble()
        set(value) = prefs.edit().putFloat("hist_mod_25k_at_10c", value.toFloat()).apply()

    var historicalModulation25kSlopePerK: Double
        get() = prefs.getFloat("hist_mod_25k_slope_per_k", 3.5f).toDouble()
        set(value) = prefs.edit().putFloat("hist_mod_25k_slope_per_k", value.toFloat()).apply()

    var historicalModulation25kWeight: Int
        get() = prefs.getInt("hist_mod_25k_weight", 0)
        set(value) = prefs.edit().putInt("hist_mod_25k_weight", value.coerceIn(0, 25000)).apply()

    var historicalHeatLoss25kWPerK: Int
        get() = prefs.getInt("hist_heat_loss_25k_w_per_k", 132)
        set(value) = prefs.edit().putInt("hist_heat_loss_25k_w_per_k", value.coerceIn(50, 400)).apply()

    var historicalHeatLoss25kWeight: Double
        get() = prefs.getFloat("hist_heat_loss_25k_weight", 0.0f).toDouble()
        set(value) = prefs.edit().putFloat("hist_heat_loss_25k_weight", value.toFloat().coerceIn(0f, 25000f)).apply()

    fun save25kHistoricalModulation(modAt10C: Double, slope: Double, newSamples: Int) {
        val oldWeight = historicalModulation25kWeight
        val totalWeight = (oldWeight + newSamples).coerceAtMost(25000)
        if (totalWeight <= 0) return
        val wOld = oldWeight.toDouble()
        val wNew = newSamples.toDouble()
        val wSum = wOld + wNew
        val blendedMod = if (oldWeight > 0) {
            (historicalModulation25kAt10C * wOld + modAt10C * wNew) / wSum
        } else modAt10C
        val blendedSlope = if (oldWeight > 0) {
            (historicalModulation25kSlopePerK * wOld + slope * wNew) / wSum
        } else slope
        historicalModulation25kAt10C = blendedMod
        historicalModulation25kSlopePerK = blendedSlope
        historicalModulation25kWeight = totalWeight
    }

    fun save25kHistoricalHeatLoss(lossWPerK: Int, newWeight: Double) {
        val oldWeight = historicalHeatLoss25kWeight
        val totalWeight = (oldWeight + newWeight).coerceAtMost(25000.0)
        if (totalWeight <= 0.0) return
        val blendedLoss = if (oldWeight > 0.0) {
            ((historicalHeatLoss25kWPerK * oldWeight + lossWPerK * newWeight) / (oldWeight + newWeight)).toInt()
        } else lossWPerK
        historicalHeatLoss25kWPerK = blendedLoss
        historicalHeatLoss25kWeight = totalWeight
    }

    var estimatedPowerKw: Double
        get() = prefs.getFloat("estimated_power_kw", 0.7f).toDouble()
        set(value) = prefs.edit().putFloat("estimated_power_kw", value.toFloat()).apply()

    /**
     * Continuous parasitic electrical baseline load in Watts (secondary underfloor pump + standby electronics).
     * Defaults to 50.0 W (generous rule of thumb: ~25W secondary ECM pump + ~15W control boards + primary pump).
     * Can be directly calibrated when an external electricity submeter (e.g. Shelly 3EM / Eastron SDM) is installed.
     */
    var parasiticBaselinePowerWatts: Double
        get() = prefs.getFloat("parasitic_baseline_power_watts", 50.0f).toDouble()
        set(value) = prefs.edit().putFloat("parasitic_baseline_power_watts", value.toFloat()).apply()

    var externalSubmeterActive: Boolean
        get() = prefs.getBoolean("external_submeter_active", false)
        set(value) = prefs.edit().putBoolean("external_submeter_active", value).apply()

    var autoBackgroundEnabled: Boolean
        get() = prefs.getBoolean("auto_bg_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_bg_enabled", value).apply()

    var debugModeEnabled: Boolean
        get() = prefs.getBoolean("debug_mode_enabled", false)
        set(value) = prefs.edit().putBoolean("debug_mode_enabled", value).apply()

    var telemetryCollectionEnabled: Boolean
        get() = prefs.getBoolean("telemetry_collection_enabled", true)
        set(value) = prefs.edit().putBoolean("telemetry_collection_enabled", value).apply()

    var telemetryIntervalMinutes: Int
        get() = prefs.getInt("telemetry_interval_minutes", 15).coerceIn(1, 60)
        set(value) = prefs.edit().putInt("telemetry_interval_minutes", value.coerceIn(1, 60)).apply()

    var zeroPriceExtensionEnabled: Boolean
        get() = prefs.getBoolean("zero_price_ext_enabled", true)
        set(value) = prefs.edit().putBoolean("zero_price_ext_enabled", value).apply()

    var autoWeatherAdaptiveHoursEnabled: Boolean
        get() = prefs.getBoolean("auto_weather_adaptive_hours", true)
        set(value) = prefs.edit().putBoolean("auto_weather_adaptive_hours", value).apply()

    var enabledSpotSources: Set<String>
        get() = prefs.getStringSet("enabled_spot_sources", SpotPriceSource.DEFAULT_ENABLED)
            ?: SpotPriceSource.DEFAULT_ENABLED
        set(value) = prefs.edit().putStringSet("enabled_spot_sources", value).apply()

    var primarySpotSource: String
        get() = prefs.getString("primary_spot_source", SpotPriceSource.VATTENFALL.id)
            ?: SpotPriceSource.VATTENFALL.id
        set(value) = prefs.edit().putString("primary_spot_source", value).apply()

    fun getOrderedSpotSources(): List<SpotPriceSource> {
        val enabledSet = enabledSpotSources
        val primary = SpotPriceSource.fromId(primarySpotSource) ?: SpotPriceSource.VATTENFALL
        val ordered = mutableListOf<SpotPriceSource>()
        if (primary.id in enabledSet) {
            ordered.add(primary)
        }
        for (src in SpotPriceSource.ALL) {
            if (src != primary && src.id in enabledSet) {
                ordered.add(src)
            }
        }
        if (ordered.isEmpty()) {
            ordered.add(SpotPriceSource.VATTENFALL)
        }
        return ordered
    }

    var tariffPricingModel: String
        get() = prefs.getString("tariff_pricing_model", TariffPricingModel.DYNAMIC_SPOT.id) ?: TariffPricingModel.DYNAMIC_SPOT.id
        set(value) = prefs.edit().putString("tariff_pricing_model", value).apply()

    var tariffVatRatePercent: Double
        get() = prefs.getFloat("tariff_vat_rate_pct", 19.0f).toDouble()
        set(value) = prefs.edit().putFloat("tariff_vat_rate_pct", value.toFloat().coerceIn(0.0f, 100.0f)).apply()

    var tariffNetSurchargeCtPerKwh: Double
        get() = prefs.getFloat("tariff_net_surcharge_ct", 21.3446f).toDouble()
        set(value) = prefs.edit().putFloat("tariff_net_surcharge_ct", value.toFloat().coerceAtLeast(0.0f)).apply()

    var tariffSupplierMarginNetCt: Double
        get() = prefs.getFloat("tariff_supplier_margin_net_ct", 0.0f).toDouble()
        set(value) = prefs.edit().putFloat("tariff_supplier_margin_net_ct", value.toFloat().coerceAtLeast(0.0f)).apply()

    var tariffRegionalSurchargeGrossCt: Double?
        get() {
            val v = prefs.getFloat("tariff_regional_surcharge_gross_ct", -1f)
            return if (v >= 0f) v.toDouble() else null
        }
        set(value) {
            if (value == null || value < 0.0) {
                prefs.edit().remove("tariff_regional_surcharge_gross_ct").apply()
            } else {
                prefs.edit().putFloat("tariff_regional_surcharge_gross_ct", value.toFloat()).apply()
            }
        }

    var tariffBaseFeeMonthlyGross: Double
        get() = prefs.getFloat("tariff_base_fee_monthly_gross", 13.80f).toDouble()
        set(value) = prefs.edit().putFloat("tariff_base_fee_monthly_gross", value.toFloat().coerceAtLeast(0.0f)).apply()

    var tariffPriceCapGrossCt: Double?
        get() {
            val v = prefs.getFloat("tariff_price_cap_gross_ct", -1f)
            return if (v > 0f) v.toDouble() else null
        }
        set(value) {
            if (value == null || value <= 0.0) {
                prefs.edit().remove("tariff_price_cap_gross_ct").apply()
            } else {
                prefs.edit().putFloat("tariff_price_cap_gross_ct", value.toFloat()).apply()
            }
        }

    var tariffTouPeakPriceGrossCt: Double
        get() = prefs.getFloat("tariff_tou_peak_price_gross_ct", 38.0f).toDouble()
        set(value) = prefs.edit().putFloat("tariff_tou_peak_price_gross_ct", value.toFloat().coerceAtLeast(0.0f)).apply()

    var tariffTouOffPeakPriceGrossCt: Double
        get() = prefs.getFloat("tariff_tou_off_peak_price_gross_ct", 24.0f).toDouble()
        set(value) = prefs.edit().putFloat("tariff_tou_off_peak_price_gross_ct", value.toFloat().coerceAtLeast(0.0f)).apply()

    var tariffTouOffPeakStartHour: Int
        get() = prefs.getInt("tariff_tou_off_peak_start_hour", 22).coerceIn(0, 23)
        set(value) = prefs.edit().putInt("tariff_tou_off_peak_start_hour", value.coerceIn(0, 23)).apply()

    var tariffTouOffPeakEndHour: Int
        get() = prefs.getInt("tariff_tou_off_peak_end_hour", 6).coerceIn(0, 23)
        set(value) = prefs.edit().putInt("tariff_tou_off_peak_end_hour", value.coerceIn(0, 23)).apply()

    var tariffTouWeekendAllOffPeak: Boolean
        get() = prefs.getBoolean("tariff_tou_weekend_all_off_peak", true)
        set(value) = prefs.edit().putBoolean("tariff_tou_weekend_all_off_peak", value).apply()

    var tariffFlatPriceGrossCt: Double
        get() = prefs.getFloat("tariff_flat_price_gross_ct", 32.0f).toDouble()
        set(value) = prefs.edit().putFloat("tariff_flat_price_gross_ct", value.toFloat().coerceAtLeast(0.0f)).apply()

    fun buildTariffConfig(): ElectricityTariffConfig {
        return ElectricityTariffConfig(
            pricingModel = TariffPricingModel.fromId(tariffPricingModel),
            vatRate = (tariffVatRatePercent / 100.0).coerceIn(0.0, 1.0),
            netSurchargeCtPerKwh = tariffNetSurchargeCtPerKwh,
            supplierMarginNetCtPerKwh = tariffSupplierMarginNetCt,
            regionalSurchargeGrossCtPerKwh = tariffRegionalSurchargeGrossCt,
            baseFeeMonthlyGross = tariffBaseFeeMonthlyGross,
            priceCapGrossCtPerKwh = tariffPriceCapGrossCt,
            touTimetable = TimeOfUseTimetable(
                peakPriceGrossCtPerKwh = tariffTouPeakPriceGrossCt,
                offPeakPriceGrossCtPerKwh = tariffTouOffPeakPriceGrossCt,
                offPeakStartHour = tariffTouOffPeakStartHour,
                offPeakEndHour = tariffTouOffPeakEndHour,
                weekendAllOffPeak = tariffTouWeekendAllOffPeak
            ),
            flatPriceGrossCtPerKwh = tariffFlatPriceGrossCt
        )
    }

    var holisticOptimizationEnabled: Boolean
        get() = prefs.getBoolean("holistic_optimization_enabled", true)
        set(value) = prefs.edit().putBoolean("holistic_optimization_enabled", value).apply()

    var buildingLossKwPerK: Double
        get() = prefs.getFloat("building_loss_kw_per_k", 0.150f).toDouble()
        set(value) = prefs.edit().putFloat("building_loss_kw_per_k", value.toFloat()).apply()

    var maxThermalBufferingK: Double
        get() = prefs.getFloat("max_thermal_buffering_k", 1.2f).toDouble()
        set(value) = prefs.edit().putFloat("max_thermal_buffering_k", value.toFloat()).apply()

    var weatherLatitude: Double
        get() = prefs.getFloat("weather_latitude", 52.52f).toDouble()
        set(value) = prefs.edit().putFloat("weather_latitude", value.toFloat()).apply()

    var weatherLongitude: Double
        get() = prefs.getFloat("weather_longitude", 13.41f).toDouble()
        set(value) = prefs.edit().putFloat("weather_longitude", value.toFloat()).apply()

    var optimizationPreference: String
        get() = prefs.getString("optimization_preference", OptimizationPreference.BALANCED.id) ?: OptimizationPreference.BALANCED.id
        set(value) = prefs.edit().putString("optimization_preference", value).apply()

    var chartShowHpc1: Boolean
        get() = prefs.getBoolean("chart_show_hpc1", true)
        set(value) = prefs.edit().putBoolean("chart_show_hpc1", value).apply()

    var chartShowHpc2: Boolean
        get() = prefs.getBoolean("chart_show_hpc2", true)
        set(value) = prefs.edit().putBoolean("chart_show_hpc2", value).apply()

    var chartShowHpc3: Boolean
        get() = prefs.getBoolean("chart_show_hpc3", false)
        set(value) = prefs.edit().putBoolean("chart_show_hpc3", value).apply()

    var chartShowHpc4: Boolean
        get() = prefs.getBoolean("chart_show_hpc4", false)
        set(value) = prefs.edit().putBoolean("chart_show_hpc4", value).apply()

    var chartShowHpc5: Boolean
        get() = prefs.getBoolean("chart_show_hpc5", false)
        set(value) = prefs.edit().putBoolean("chart_show_hpc5", value).apply()

    var chartShowScatterSteadyState: Boolean
        get() = prefs.getBoolean("chart_show_scatter_steady", true)
        set(value) = prefs.edit().putBoolean("chart_show_scatter_steady", value).apply()

    var chartShowScatterWarmup: Boolean
        get() = prefs.getBoolean("chart_show_scatter_warmup", false)
        set(value) = prefs.edit().putBoolean("chart_show_scatter_warmup", value).apply()

    var chartShowLivePoint: Boolean
        get() = prefs.getBoolean("chart_show_live_point", true)
        set(value) = prefs.edit().putBoolean("chart_show_live_point", value).apply()

    var chartParallelShiftK: Double
        get() = prefs.getFloat("chart_parallel_shift_k", 0.0f).toDouble()
        set(value) = prefs.edit().putFloat("chart_parallel_shift_k", value.toFloat()).apply()

    override var learnedBuildingLossKwPerK: Double
        get() = prefs.getFloat("learned_building_loss_kw_per_k", buildingLossKwPerK.toFloat()).toDouble()
        set(value) = prefs.edit().putFloat("learned_building_loss_kw_per_k", value.toFloat()).apply()

    override var learnedCoolingRateKPerHour: Double
        get() = prefs.getFloat("learned_cooling_rate_k_per_h", 0.18f).toDouble()
        set(value) = prefs.edit().putFloat("learned_cooling_rate_k_per_h", value.toFloat()).apply()

    override var learnedCycleCount: Int
        get() = prefs.getInt("learned_cycle_count", 0)
        set(value) = prefs.edit().putInt("learned_cycle_count", value).apply()

    override var lastLearnedTimestampIso: String?
        get() = prefs.getString("last_learned_timestamp_iso", null)
        set(value) = prefs.edit().putString("last_learned_timestamp_iso", value).apply()

    fun buildHolisticOptimizationConfig(
        targetHeatingTempC: Double = sliderTempC,
        liveStartingReturnTempC: Double? = null
    ): HolisticOptimizationConfig {
        val effectiveLossKwPerK = if (learnedCycleCount > 0) learnedBuildingLossKwPerK else buildingLossKwPerK
        return HolisticOptimizationConfig(
            targetHeatingTempC = targetHeatingTempC,
            targetDailyRuntimeHours = targetOperationHours,
            estimatedPowerKw = estimatedPowerKw,
            parasiticBaselinePowerKw = parasiticBaselinePowerWatts / 1000.0,
            customMeasuredParasiticLoadKw = if (externalSubmeterActive) parasiticBaselinePowerWatts / 1000.0 else null,
            autoPowerModulationEnabled = autoPowerModulationEnabled,
            useEmpiricalGroundTruthModulation = groundTruthModulationEnabled,
            calibratedModulationAt10C = calibratedModulationAt10C,
            calibratedModulationSlopePerK = calibratedModulationSlopePerK,
            buildingLossKwPerK = effectiveLossKwPerK,
            coolingRateKPerHour = learnedCoolingRateKPerHour,
            maxThermalBufferingK = maxThermalBufferingK,
            dailyBudgetEuro = dailyBudgetEuro,
            preference = OptimizationPreference.fromId(optimizationPreference),
            liveStartingReturnTempC = liveStartingReturnTempC,
            adaptiveCycleCount = learnedCycleCount
        )
    }

    fun buildScheduleConfig(averageOutdoorTempC: Double? = null): SwitchScheduleConfig {
        val isHolistic = holisticOptimizationEnabled
        return SwitchScheduleConfig(
            windowCount = 1,
            maxSwitchPointsPerDay = 2,
            targetOperationHoursPerDay = targetOperationHours,
            temperatureSlider = HeatingTemperatureSlider().withValue(sliderTempC),
            dailyBudgetEuro = dailyBudgetEuro,
            estimatedPowerKw = estimatedPowerKw,
            autoPowerModulationEnabled = autoPowerModulationEnabled,
            zeroPriceExtensionEnabled = if (isHolistic) zeroPriceExtensionEnabled else false,
            autoWeatherAdaptiveHoursEnabled = false, // When holistic is disabled, strictly adhere to slider runtime
            averageOutdoorTempC = averageOutdoorTempC
        )
    }
}
