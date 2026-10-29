package de.breast.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.sin

@Serializable
data class HourlyWeatherForecast(
    val dateTime: String,
    val outdoorTempC: Double,
    val directRadiationWm2: Double = 0.0,
    val diffuseRadiationWm2: Double = 0.0
) {
    val totalRadiationWm2: Double get() = directRadiationWm2 + diffuseRadiationWm2
}

class WeatherForecastClient(
    private val httpClient: OkHttpClient,
    val latitude: Double = 52.5200,
    val longitude: Double = 13.4050,
    val timezone: String = "Europe/Berlin"
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val DWD_ICON_URL = "https://api.open-meteo.com/v1/dwd-icon"
        const val FALLBACK_FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        val BERLIN_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
    }

    suspend fun fetchHourlyForecast(
        startDate: LocalDate = LocalDate.now(BERLIN_ZONE),
        days: Int = 2
    ): List<HourlyWeatherForecast> = withContext(Dispatchers.IO) {
        runCatching {
            fetchFromUrl(DWD_ICON_URL, days)
        }.recoverCatching {
            fetchFromUrl(FALLBACK_FORECAST_URL, days)
        }.getOrElse {
            generateSyntheticDiurnalForecast(startDate, days, baseOutdoorTempC = 8.0)
        }
    }

    private fun fetchFromUrl(baseUrl: String, days: Int): List<HourlyWeatherForecast> {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("latitude", String.format(java.util.Locale.US, "%.4f", latitude))
            .addQueryParameter("longitude", String.format(java.util.Locale.US, "%.4f", longitude))
            .addQueryParameter("hourly", "temperature_2m,direct_normal_irradiance,diffuse_radiation")
            .addQueryParameter("timezone", timezone)
            .addQueryParameter("forecast_days", days.coerceIn(1, 7).toString())
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "BREAST-HeatPump/1.0")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Weather API failed (HTTP ${response.code}): $body")
            }
            return parseOpenMeteoJson(body)
        }
    }

    internal fun parseOpenMeteoJson(body: String): List<HourlyWeatherForecast> {
        val root = json.parseToJsonElement(body).jsonObject
        val hourly = root["hourly"]?.jsonObject ?: return emptyList()

        val times = hourly["time"]?.jsonArray ?: return emptyList()
        val temps = hourly["temperature_2m"]?.jsonArray ?: return emptyList()
        val direct = hourly["direct_normal_irradiance"]?.jsonArray
        val diffuse = hourly["diffuse_radiation"]?.jsonArray

        val count = minOf(times.size, temps.size)
        val list = mutableListOf<HourlyWeatherForecast>()

        for (i in 0 until count) {
            val tStr = times[i].jsonPrimitive.content
            val temp = temps[i].jsonPrimitive.doubleOrNull ?: continue
            val dir = direct?.getOrNull(i)?.jsonPrimitive?.doubleOrNull ?: 0.0
            val dif = diffuse?.getOrNull(i)?.jsonPrimitive?.doubleOrNull ?: 0.0

            list.add(
                HourlyWeatherForecast(
                    dateTime = tStr,
                    outdoorTempC = temp,
                    directRadiationWm2 = dir,
                    diffuseRadiationWm2 = dif
                )
            )
        }
        return list
    }

    /**
     * Fallback diurnal sinusoidal curve if offline:
     * Minimum temperature around 06:00, maximum around 14:00 with ~6K diurnal swing.
     */
    fun generateSyntheticDiurnalForecast(
        startDate: LocalDate,
        days: Int,
        baseOutdoorTempC: Double = 8.0,
        diurnalSwingK: Double = 6.0
    ): List<HourlyWeatherForecast> {
        val list = mutableListOf<HourlyWeatherForecast>()
        val totalHours = days * 24
        val startDateTime = startDate.atStartOfDay()

        val minT = baseOutdoorTempC - diurnalSwingK / 2.0
        val maxT = baseOutdoorTempC + diurnalSwingK / 2.0

        for (h in 0 until totalHours) {
            val dt = startDateTime.plusHours(h.toLong())
            val hourOfDay = dt.hour
            // Asymmetric diurnal cycle: minimum at 06:00 (sunrise), maximum at 14:00 (solar peak)
            val temp = if (hourOfDay in 6..14) {
                // Daytime heating from 06:00 to 14:00 (8h heating)
                val frac = 0.5 * (1.0 - kotlin.math.cos(Math.PI * (hourOfDay - 6.0) / 8.0))
                minT + (maxT - minT) * frac
            } else {
                // Nighttime radiative cooling from 14:00 to 06:00 (16h cooling)
                val nightHour = if (hourOfDay > 14) hourOfDay - 14.0 else hourOfDay + 10.0
                val frac = 0.5 * (1.0 - kotlin.math.cos(Math.PI * nightHour / 16.0))
                maxT - (maxT - minT) * frac
            }

            val solar = if (hourOfDay in 8..17) {
                val solarAngle = Math.PI * (hourOfDay - 8.0) / 9.0
                400.0 * sin(solarAngle)
            } else {
                0.0
            }

            list.add(
                HourlyWeatherForecast(
                    dateTime = dt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    outdoorTempC = Math.round(temp * 10.0) / 10.0,
                    directRadiationWm2 = solar * 0.7,
                    diffuseRadiationWm2 = solar * 0.3
                )
            )
        }
        return list
    }
}
