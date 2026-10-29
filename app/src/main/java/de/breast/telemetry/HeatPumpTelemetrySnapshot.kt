package de.breast.telemetry

import kotlinx.serialization.Serializable

@Serializable
data class HeatPumpTelemetrySnapshot(
    val timestampIso: String,
    val epochSeconds: Long,
    val outdoorTempC: Double?,
    val supplyTempC: Double?,
    val returnTempC: Double?,
    val condenserTempC: Double? = null,
    val setpointTempC: Double? = null,
    val modulationPct: Double? = null,
    val demand: String? = null,
    val chStatus: String? = null,
    val spotPriceGrossCt: Double? = null,
    val spotPriceNetCt: Double? = null
) {
    val deltaTempC: Double?
        get() = if (supplyTempC != null && returnTempC != null) supplyTempC - returnTempC else null

    val isHeatingActive: Boolean
        get() = demand?.contains("ch", ignoreCase = true) == true ||
            (chStatus?.equals("on", ignoreCase = true) == true && (modulationPct ?: 0.0) > 15.0 && (deltaTempC ?: 0.0) > 0.8)

    val isDhwActive: Boolean
        get() = demand?.contains("dhw", ignoreCase = true) == true ||
            ((condenserTempC ?: 0.0) > 48.0 && (modulationPct ?: 0.0) > 20.0 && (supplyTempC ?: 0.0) < (returnTempC ?: 0.0))

    val isIdle: Boolean
        get() = !isHeatingActive && !isDhwActive && ((modulationPct ?: 0.0) <= 5.0)

    fun toCsvRow(): String {
        return listOf(
            timestampIso,
            epochSeconds,
            outdoorTempC?.let { String.format("%.2f", it) } ?: "",
            supplyTempC?.let { String.format("%.2f", it) } ?: "",
            returnTempC?.let { String.format("%.2f", it) } ?: "",
            condenserTempC?.let { String.format("%.2f", it) } ?: "",
            setpointTempC?.let { String.format("%.2f", it) } ?: "",
            modulationPct?.let { String.format("%.1f", it) } ?: "",
            demand ?: "",
            chStatus ?: "",
            spotPriceGrossCt?.let { String.format("%.2f", it) } ?: "",
            spotPriceNetCt?.let { String.format("%.2f", it) } ?: ""
        ).joinToString(",")
    }

    companion object {
        const val CSV_HEADER = "timestamp_iso,epoch_seconds,outdoor_temp_c,supply_temp_c,return_temp_c,condenser_temp_c,setpoint_temp_c,modulation_pct,demand,ch_status,spot_price_gross_ct,spot_price_net_ct"
    }
}
