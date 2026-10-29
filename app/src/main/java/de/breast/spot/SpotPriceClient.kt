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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

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
    val netSurchargeCtPerKwh: Double = 21.3446, // 19.2946 (Verbrauchspreis) + 2.0500 (Stromsteuer)
    val regionalSurchargeGrossCtPerKwh: Double? = null,
    val baseFeeMonthlyGross: Double = 13.80, // 141.18 €/year net -> 13.80 €/month gross
    val priceCapGrossCtPerKwh: Double? = null
) {
    val baseFloorGrossCtPerKwh: Double
        get() = regionalSurchargeGrossCtPerKwh ?: (netSurchargeCtPerKwh * (1.0 + vatRate))

    fun computeGrossCtPerKwh(spotEurPerMwh: Double): Double {
        val spotNetCt = spotEurPerMwh / 10.0
        val rawGross = if (regionalSurchargeGrossCtPerKwh != null) {
            spotNetCt * (1.0 + vatRate) + regionalSurchargeGrossCtPerKwh
        } else {
            (spotNetCt + netSurchargeCtPerKwh) * (1.0 + vatRate)
        }
        return if (priceCapGrossCtPerKwh != null) minOf(rawGross, priceCapGrossCtPerKwh) else rawGross
    }
}

open class SpotPriceClient(
    private val httpClient: OkHttpClient,
    private val tariffConfig: VattenfallTariffConfig = VattenfallTariffConfig()
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val VATTENFALL_DAVIS_BASE_URL = "https://davis.vattenfall.de/api/digitalinterface/1.4"
        const val VATTENFALL_SUBSCRIPTION_KEY = "b2b769e800fb49a7b685c49d050c825d"
        const val ENERGY_CHARTS_URL = "https://api.energy-charts.info/price"
        const val AWATTAR_DE_URL = "https://api.awattar.de/v1/marketdata"
        val BERLIN_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    @Volatile
    private var cachedDavisToken: String? = null
    @Volatile
    private var cachedDavisParentLogId: String? = null
    @Volatile
    private var cachedDavisTokenExpiryEpochMs: Long = 0L

    fun hasCompleteDayPrices(date: LocalDate, spotSlots: List<SpotPriceSlot>): Boolean {
        val dayStartEpoch = date.atStartOfDay(BERLIN_ZONE).toInstant().epochSecond
        val dayEndEpoch = date.plusDays(1).atStartOfDay(BERLIN_ZONE).toInstant().epochSecond

        val coveredSeconds = spotSlots.sumOf { slot ->
            val overlapStart = maxOf(slot.startEpochSeconds, dayStartEpoch)
            val overlapEnd = minOf(slot.endEpochSeconds, dayEndEpoch)
            (overlapEnd - overlapStart).coerceAtLeast(0L)
        }
        return coveredSeconds >= 23L * 3600L
    }

    open suspend fun fetchDayAheadPrices(
        startDate: LocalDate = LocalDate.now(BERLIN_ZONE),
        endDate: LocalDate = startDate.plusDays(1),
        sources: List<SpotPriceSource> = SpotPriceSource.ALL
    ): List<SpotPriceSlot> = withContext(Dispatchers.IO) {
        val activeSources = sources.ifEmpty { listOf(SpotPriceSource.VATTENFALL) }

        var bestTodaySlots: List<SpotPriceSlot> = emptyList()
        var bestTomorrowSlots: List<SpotPriceSlot> = emptyList()

        for (source in activeSources) {
            val candidateSlots = runCatching {
                when (source) {
                    SpotPriceSource.VATTENFALL -> fetchFromVattenfall(startDate)
                    SpotPriceSource.ENERGY_CHARTS -> fetchFromEnergyCharts(startDate, endDate)
                    SpotPriceSource.AWATTAR -> fetchFromAwattar(startDate, endDate)
                }
            }.getOrDefault(emptyList())

            if (candidateSlots.isEmpty()) continue

            val hasTomorrow = hasCompleteDayPrices(endDate, candidateSlots)
            val hasToday = hasCompleteDayPrices(startDate, candidateSlots)

            if (hasTomorrow) {
                if (hasToday) {
                    return@withContext candidateSlots
                } else if (bestTodaySlots.isNotEmpty()) {
                    val tomorrowOnly = candidateSlots.filter { slot ->
                        Instant.ofEpochSecond(slot.startEpochSeconds).atZone(BERLIN_ZONE).toLocalDate() == endDate
                    }
                    return@withContext (bestTodaySlots + tomorrowOnly).sortedBy { it.startEpochSeconds }
                } else {
                    return@withContext candidateSlots
                }
            }

            // Keep best today slots (prefer higher resolution, e.g. 15-min over 60-min)
            val todayPart = candidateSlots.filter { slot ->
                Instant.ofEpochSecond(slot.startEpochSeconds).atZone(BERLIN_ZONE).toLocalDate() == startDate
            }
            if (todayPart.isNotEmpty() && (bestTodaySlots.isEmpty() || todayPart.size > bestTodaySlots.size)) {
                bestTodaySlots = todayPart
            }

            val tomorrowPart = candidateSlots.filter { slot ->
                Instant.ofEpochSecond(slot.startEpochSeconds).atZone(BERLIN_ZONE).toLocalDate() == endDate
            }
            if (tomorrowPart.isNotEmpty() && (bestTomorrowSlots.isEmpty() || tomorrowPart.size > bestTomorrowSlots.size)) {
                bestTomorrowSlots = tomorrowPart
            }
        }

        if (bestTodaySlots.isNotEmpty() && bestTomorrowSlots.isNotEmpty()) {
            return@withContext (bestTodaySlots + bestTomorrowSlots).sortedBy { it.startEpochSeconds }
        }

        if (bestTomorrowSlots.isNotEmpty()) {
            return@withContext bestTomorrowSlots
        }

        if (bestTodaySlots.isNotEmpty()) {
            return@withContext bestTodaySlots
        }

        emptyList()
    }

    suspend fun testPriceSources(
        date: LocalDate = LocalDate.now(BERLIN_ZONE)
    ): Map<SpotPriceSource, SourceTestResult> = withContext(Dispatchers.IO) {
        SpotPriceSource.ALL.associateWith { source ->
            val startMs = System.currentTimeMillis()
            try {
                val slots = when (source) {
                    SpotPriceSource.VATTENFALL -> fetchFromVattenfall(date)
                    SpotPriceSource.ENERGY_CHARTS -> fetchFromEnergyCharts(date, date)
                    SpotPriceSource.AWATTAR -> fetchFromAwattar(date, date)
                }
                val latency = System.currentTimeMillis() - startMs
                SourceTestResult(
                    source = source,
                    success = slots.isNotEmpty(),
                    slotCount = slots.size,
                    latencyMs = latency,
                    message = if (slots.isNotEmpty()) "OK: ${slots.size} slots (${latency}ms)" else "No slots returned"
                )
            } catch (e: Exception) {
                val latency = System.currentTimeMillis() - startMs
                SourceTestResult(
                    source = source,
                    success = false,
                    slotCount = 0,
                    latencyMs = latency,
                    message = "Error: ${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    open internal fun fetchFromEnergyCharts(
        startDate: LocalDate,
        endDate: LocalDate
    ): List<SpotPriceSlot> {
        val url = ENERGY_CHARTS_URL.toHttpUrl().newBuilder()
            .addQueryParameter("bzn", "DE-LU")
            .addQueryParameter("start", startDate.toString())
            .addQueryParameter("end", endDate.plusDays(1).toString())
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

    open internal fun fetchFromAwattar(
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

    internal fun getOrFetchDavisToken(): Pair<String, String?> {
        val now = System.currentTimeMillis()
        val token = cachedDavisToken
        if (token != null && now < cachedDavisTokenExpiryEpochMs) {
            return token to cachedDavisParentLogId
        }

        val requestBodyJson = """
            {
                "Client": "WEB",
                "Mandant": "VESALES",
                "Timeout": null,
                "StandVom": null,
                "ParentLogId": null,
                "TransaktionsId": "${UUID.randomUUID()}",
                "Sprache": "DE",
                "ReferenzId": "optin",
                "Priority": null,
                "RequestKeyValues": {},
                "Request": {
                    "Tenant": null,
                    "Werte": {}
                }
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("$VATTENFALL_DAVIS_BASE_URL/Account/GetTokenAnonym")
            .header("Content-Type", "application/json")
            .header("Ocp-Apim-Subscription-Key", VATTENFALL_SUBSCRIPTION_KEY)
            .post(requestBodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Vattenfall GetTokenAnonym failed (HTTP ${response.code}): $body")
            }
            val root = json.parseToJsonElement(body).jsonObject
            val result = root["Result"]?.jsonObject ?: error("Missing Result in Vattenfall token response")
            val accessToken = result["AccessToken"]?.jsonPrimitive?.content ?: error("Missing AccessToken in Vattenfall token response")
            val parentLogId = root["ParentLogId"]?.jsonPrimitive?.content

            cachedDavisToken = accessToken
            cachedDavisParentLogId = parentLogId
            // Davis tokens are valid for ~10 min; cache for 5 min (300_000 ms)
            cachedDavisTokenExpiryEpochMs = now + 300_000L

            return accessToken to parentLogId
        }
    }

    open internal fun fetchFromVattenfall(
        startDate: LocalDate = LocalDate.now(BERLIN_ZONE)
    ): List<SpotPriceSlot> {
        val (token, parentLogId) = getOrFetchDavisToken()
        val parentLogIdJson = if (parentLogId != null) "\"$parentLogId\"" else "null"

        val requestBodyJson = """
            {
                "Client": "WEB",
                "Mandant": "VESALES",
                "Timeout": null,
                "ParentLogId": $parentLogIdJson,
                "TransaktionsId": "${UUID.randomUUID()}",
                "ReferenzId": "lpProducts",
                "StandVom": null,
                "Sprache": "DE",
                "Priority": "Low",
                "Request": {
                    "Intervall": "15MIN",
                    "DatumVon": "$startDate",
                    "DatumBis": "$startDate"
                }
            }
        """.trimIndent()

        val request = Request.Builder()
            .url("$VATTENFALL_DAVIS_BASE_URL/Produkt/GetSpotPreise")
            .header("Content-Type", "application/json")
            .header("Ocp-Apim-Subscription-Key", VATTENFALL_SUBSCRIPTION_KEY)
            .header("davis-token", token)
            .post(requestBodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Vattenfall GetSpotPreise failed (HTTP ${response.code}): $body")
            }
            return parseVattenfallSpotPricesJson(body)
        }
    }

    internal fun parseVattenfallSpotPricesJson(body: String): List<SpotPriceSlot> {
        val root = json.parseToJsonElement(body).jsonObject
        val result = root["Result"]?.jsonObject ?: return emptyList()
        val tage = result["Tage"]?.jsonArray ?: return emptyList()
        val slots = mutableListOf<SpotPriceSlot>()

        for (dayElem in tage) {
            val dayObj = dayElem.jsonObject
            val datumStr = dayObj["Datum"]?.jsonPrimitive?.content ?: continue
            val date = runCatching { LocalDate.parse(datumStr) }.getOrNull() ?: continue
            val preiseNetto = dayObj["PreiseNetto"]?.jsonObject ?: continue

            for ((timeKey, priceElem) in preiseNetto) {
                if (timeKey.length != 4) continue
                val hour = timeKey.substring(0, 2).toIntOrNull() ?: continue
                val min = timeKey.substring(2, 4).toIntOrNull() ?: continue
                val netCtPerKwh = priceElem.jsonPrimitive.doubleOrNull ?: continue
                val eurPerMwh = netCtPerKwh * 10.0

                val startSec = date.atTime(hour, min).atZone(BERLIN_ZONE).toEpochSecond()
                val endSec = startSec + 900L

                slots.add(
                    SpotPriceSlot(
                        startEpochSeconds = startSec,
                        endEpochSeconds = endSec,
                        spotEurPerMwh = eurPerMwh,
                        spotNetCtPerKwh = netCtPerKwh,
                        totalGrossCtPerKwh = tariffConfig.computeGrossCtPerKwh(eurPerMwh)
                    )
                )
            }
        }
        return slots.sortedBy { it.startEpochSeconds }
    }
}
