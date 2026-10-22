package de.breast.auth

import android.content.Context
import de.breast.daemon.ScheduleStateStore
import de.breast.daemon.ScheduleSyncState
import de.breast.optimizer.HeatingTemperatureSlider
import de.breast.optimizer.SwitchScheduleConfig
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
        return runCatching { json.decodeFromString<OAuthTokens>(raw) }.getOrNull()
    }

    override suspend fun saveTokens(tokens: OAuthTokens) {
        prefs.edit().putString("oauth_tokens_json", json.encodeToString(tokens)).apply()
    }

    override suspend fun clearTokens() {
        prefs.edit().remove("oauth_tokens_json").apply()
    }

    override suspend fun savePendingPkceVerifier(verifier: String) {
        prefs.edit().putString("pkce_verifier", verifier).apply()
    }

    override suspend fun loadPendingPkceVerifier(): String? {
        return prefs.getString("pkce_verifier", null)
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

class UserSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("breast_user_settings", Context.MODE_PRIVATE)

    var sliderTempC: Double
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

    var estimatedPowerKw: Double
        get() = prefs.getFloat("estimated_power_kw", 2.0f).toDouble()
        set(value) = prefs.edit().putFloat("estimated_power_kw", value.toFloat()).apply()

    var autoBackgroundEnabled: Boolean
        get() = prefs.getBoolean("auto_bg_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_bg_enabled", value).apply()

    var debugModeEnabled: Boolean
        get() = prefs.getBoolean("debug_mode_enabled", false)
        set(value) = prefs.edit().putBoolean("debug_mode_enabled", value).apply()

    fun buildScheduleConfig(): SwitchScheduleConfig {
        return SwitchScheduleConfig(
            windowCount = 1,
            maxSwitchPointsPerDay = 2,
            targetOperationHoursPerDay = targetOperationHours,
            temperatureSlider = HeatingTemperatureSlider().withValue(sliderTempC),
            dailyBudgetEuro = dailyBudgetEuro,
            estimatedPowerKw = estimatedPowerKw
        )
    }
}
