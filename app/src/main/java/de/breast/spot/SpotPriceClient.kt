package de.breast.spot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class SpotPriceSlot(
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val spotEurPerMwh: Double,
    val spotNetCtPerKwh: Double,
    val totalGrossCtPerKwh: Double
) {
    val startInstant: Instant get() = Instant.ofEpochSecond(startEpochSeconds)
    val endInstant: Instant get() = Instant.ofEpochSecond(endEpochSeconds)
}

@Serializable
data class VattenfallTariffConfig(
    val vatRate: Double = 0.19,
    val regionalSurchargeGrossCtPerKwh: Double = 18.50,
    val priceCapGrossCtPerKwh: Double? = null
) {
    fun computeGrossCtPerKwh(spotEurPerMwh: Double): Double {
        val spotNetCt = spotEurPerMwh / 10.0
        val rawGross = spotNetCt * (1.0 + vatRate) + regionalSurchargeGrossCtPerKwh
        return if (priceCapGrossCtPerKwh != null) minOf(rawGross, priceCapGrossCtPerKwh) else rawGross
    }
}

class SpotPriceClient(
    private val httpClient: OkHttpClient,
    private val tariffConfig: VattenfallTariffConfig = VattenfallTariffConfig()
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val ENERGY_CHARTS_URL = "https://api.energy-charts.info/price"
        const val AWATTAR_DE_URL = "https://api.awattar.de/v1/marketdata"
        val BERLIN_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
    }

    suspend fun fetchDayAheadPrices(
        startDate: LocalDate = LocalDate.now(BERLIN_ZONE),
        endDate: LocalDate = startDate.plusDays(1)
    ): List<SpotPriceSlot> = withContext(Dispatchers.IO) {
        runCatching {
            fetchFromEnergyCharts(startDate, endDate)
        }.getOrElse {
            fetchFromAwattar(startDate, endDate)
        }
    }

    internal fun fetchFromEnergyCharts(
        startDate: LocalDate,
        endDate: LocalDate
    ): List<SpotPriceSlot> {
        val url = ENERGY_CHARTS_URL.toHttpUrl().newBuilder()
            .addQueryParameter("bzn", "DE-LU")
            .addQueryParameter("start", startDate.toString())
            .addQueryParameter("end", endDate.toString())
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Energy-Charts API failed (HTTP ${response.code}): $body")
            }
            return parseEnergyChartsJson(body)
        }
    }

    internal fun parseEnergyChartsJson(body: String): List<SpotPriceSlot> {
        val root = json.parseToJsonElement(body).jsonObject
        val timestamps = root["unix_seconds"]?.jsonArray
            ?: error("Missing unix_seconds in Energy-Charts response")
        val prices = root["price"]?.jsonArray
            ?: error("Missing price array in Energy-Charts response")

        val count = minOf(timestamps.size, prices.size)
        if (count == 0) return emptyList()

        val defaultDuration = if (count >= 2) {
            val t0 = timestamps[0].jsonPrimitive.longOrNull ?: 0L
            val t1 = timestamps[1].jsonPrimitive.longOrNull ?: (t0 + 3600L)
            (t1 - t0).coerceAtLeast(900L)
        } else {
            3600L
        }

        val slots = mutableListOf<SpotPriceSlot>()
        for (i in 0 until count) {
            val startSec = timestamps[i].jsonPrimitive.longOrNull ?: continue
            val priceEurMwh = prices[i].jsonPrimitive.doubleOrNull ?: continue
            val endSec = if (i + 1 < count) {
                timestamps[i + 1].jsonPrimitive.longOrNull ?: (startSec + defaultDuration)
            } else {
                startSec + defaultDuration
            }
            slots.add(
                SpotPriceSlot(
                    startEpochSeconds = startSec,
                    endEpochSeconds = endSec,
                    spotEurPerMwh = priceEurMwh,
                    spotNetCtPerKwh = priceEurMwh / 10.0,
                    totalGrossCtPerKwh = tariffConfig.computeGrossCtPerKwh(priceEurMwh)
                )
            )
        }
        return slots
    }

    internal fun fetchFromAwattar(
        startDate: LocalDate,
        endDate: LocalDate
    ): List<SpotPriceSlot> {
        val startMillis = startDate.atStartOfDay(BERLIN_ZONE).toInstant().toEpochMilli()
        val endMillis = endDate.plusDays(1).atStartOfDay(BERLIN_ZONE).toInstant().toEpochMilli()

        val url = AWATTAR_DE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("start", startMillis.toString())
            .addQueryParameter("end", endMillis.toString())
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("aWATTar API failed (HTTP ${response.code}): $body")
            }
            return parseAwattarJson(body)
        }
    }

    internal fun parseAwattarJson(body: String): List<SpotPriceSlot> {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonArray ?: return emptyList()

        return data.mapNotNull { elem ->
            val obj = elem.jsonObject
            val startMs = obj["start_timestamp"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val endMs = obj["end_timestamp"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val eurPerMwh = obj["marketprice"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null

            SpotPriceSlot(
                startEpochSeconds = startMs / 1000L,
                endEpochSeconds = endMs / 1000L,
                spotEurPerMwh = eurPerMwh,
                spotNetCtPerKwh = eurPerMwh / 10.0,
                totalGrossCtPerKwh = tariffConfig.computeGrossCtPerKwh(eurPerMwh)
            )
        }
    }
}
