package de.breast.api

import de.breast.auth.SingleKeyAuthManager
import de.breast.telemetry.HeatPumpTelemetrySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class BoschGateway(
    val deviceId: String,
    val deviceType: String = "unknown",
    val productId: String? = null,
    val fwVersion: String? = null
)

@Serializable
data class RecordingEntry(
    val c: Int? = null,
    val y: Double? = null,
    val d: String? = null
)

@Serializable
data class ResourceNode(
    val id: String,
    val type: String? = null,
    val value: JsonElement? = null,
    val unitOfMeasure: String? = null,
    val writeable: Int? = null,
    val recordable: Int? = null,
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val stepSize: Double? = null,
    val allowedValues: List<String>? = null,
    val references: List<ResourceReference>? = null,
    val switchPoints: List<SwitchPoint>? = null,
    val maxNbOfSwitchPoints: Int? = null,
    val maxNbOfSwitchPointsPerDay: Int? = null,
    val switchPointTimeRaster: Int? = null,
    val setpointProperty: ResourceReference? = null,
    val recording: List<RecordingEntry>? = null,
    val interval: String? = null
) {
    val isWritable: Boolean get() = (writeable ?: 0) > 0
}

@Serializable
data class ResourceReference(
    val id: String,
    val uri: String? = null
)

@Serializable
data class SwitchPoint(
    val dayOfWeek: String,
    val setpoint: String,
    val time: Int
) {
    fun formatTimeHm(): String {
        val h = (time / 60).coerceIn(0, 23)
        val m = (time % 60).coerceIn(0, 59)
        return String.format("%02d:%02d", h, m)
    }
}

@Serializable
data class SwitchProgramResource(
    val id: String,
    val type: String = "switchProgram",
    val maxNbOfSwitchPoints: Int = 42,
    val maxNbOfSwitchPointsPerDay: Int = 6,
    val switchPointTimeRaster: Int = 15,
    val writeable: Int = 1,
    val setpointProperty: ResourceReference? = null,
    val switchPoints: List<SwitchPoint> = emptyList()
)

@Serializable
data class PutResponseDetail(
    val ok: Boolean,
    val httpCode: Int,
    val responseBody: String = "",
    val usedFormat: String = ""
)

class PointTApiClient(
    private val httpClient: OkHttpClient,
    private val authManager: SingleKeyAuthManager,
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://pointt-api.bosch-thermotechnology.com/pointt-api/api/v1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val DEFAULT_ROOT_RESOURCES = listOf(
            "/gateway",
            "/system",
            "/heatingCircuits",
            "/dhwCircuits",
            "/heatSources",
            "/solarCircuits",
            "/ventilation",
            "/notifications",
            "/recordings"
        )
    }

    suspend fun getGateways(): List<BoschGateway> = withContext(Dispatchers.IO) {
        suspend fun executeGet(token: String): List<BoschGateway>? {
            val request = Request.Builder()
                .url("$baseUrl/gateways")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build()

            return httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code == 401) {
                    return@use null
                }
                if (!response.isSuccessful) {
                    throw IllegalStateException("GET /gateways failed (HTTP ${response.code}): $body")
                }
                val arr = json.parseToJsonElement(body).jsonArray
                arr.map { elem ->
                    val obj = elem.jsonObject
                    BoschGateway(
                        deviceId = obj["deviceId"]?.jsonPrimitive?.content
                            ?: obj["id"]?.jsonPrimitive?.content
                            ?: "unknown",
                        deviceType = obj["deviceType"]?.jsonPrimitive?.content ?: "unknown",
                        productId = obj["productId"]?.jsonPrimitive?.content,
                        fwVersion = obj["fwVersion"]?.jsonPrimitive?.content
                    )
                }
            }
        }

        val token = authManager.getValidAccessToken()
        executeGet(token) ?: run {
            val refreshed = authManager.refreshAccessToken()
            executeGet(refreshed.accessToken)
                ?: throw de.breast.auth.SingleKeyAuthException(
                    "Bosch PointT API rejected refreshed token with HTTP 401. Please log in again.",
                    httpCode = 401
                )
        }
    }

    suspend fun getResource(gatewayId: String, resourcePath: String): ResourceNode? =
        withContext(Dispatchers.IO) {
            val normalizedPath = if (resourcePath.startsWith("/")) resourcePath else "/$resourcePath"
            val token = authManager.getValidAccessToken()
            val request = Request.Builder()
                .url("$baseUrl/gateways/$gatewayId/resource$normalizedPath")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.code == 404 || response.code == 400 || response.code == 403) {
                    return@use null
                }
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isBlank()) {
                    return@use null
                }
                runCatching {
                    json.decodeFromString<ResourceNode>(body)
                }.getOrNull()
            }
        }

    suspend fun fetchRecording(
        gatewayId: String,
        resourcePath: String,
        interval: String? = null
    ): ResourceNode? = withContext(Dispatchers.IO) {
        val normalizedPath = if (resourcePath.startsWith("/")) resourcePath else "/$resourcePath"
        val token = authManager.getValidAccessToken()
        val urlBuilder = "$baseUrl/gateways/$gatewayId/resource$normalizedPath".toHttpUrlOrNull()?.newBuilder()
            ?: return@withContext null
        if (!interval.isNullOrBlank()) {
            urlBuilder.addQueryParameter("interval", interval)
        }
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code == 404 || response.code == 400 || response.code == 403) {
                return@use null
            }
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) {
                return@use null
            }
            runCatching {
                json.decodeFromString<ResourceNode>(body)
            }.getOrNull()
        }
    }

    suspend fun fetchRawRecording(
        gatewayId: String,
        resourcePath: String,
        interval: String? = null
    ): String? = withContext(Dispatchers.IO) {
        val normalizedPath = if (resourcePath.startsWith("/")) resourcePath else "/$resourcePath"
        val token = authManager.getValidAccessToken()
        val urlBuilder = "$baseUrl/gateways/$gatewayId/resource$normalizedPath".toHttpUrlOrNull()?.newBuilder()
            ?: return@withContext null
        if (!interval.isNullOrBlank()) {
            urlBuilder.addQueryParameter("interval", interval)
        }
        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) null else body
        }
    }

    suspend fun putResourceValue(
        gatewayId: String,
        resourcePath: String,
        value: JsonPrimitive
    ): Boolean = withContext(Dispatchers.IO) {
        val normalizedPath = if (resourcePath.startsWith("/")) resourcePath else "/$resourcePath"
        val token = authManager.getValidAccessToken()
        val payload: JsonObject = buildJsonObject {
            put("value", value)
        }
        val request = Request.Builder()
            .url("$baseUrl/gateways/$gatewayId/resource$normalizedPath")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .put(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            response.isSuccessful
        }
    }

    suspend fun getActiveSwitchProgramName(
        gatewayId: String,
        circuitPrefix: String
    ): String {
        val normalized = if (circuitPrefix.startsWith("/")) circuitPrefix else "/$circuitPrefix"
        val activeNode = getResource(gatewayId, "$normalized/activeSwitchProgram")
        val activeName = (activeNode?.value as? JsonPrimitive)?.content
        if (!activeName.isNullOrBlank()) {
            return activeName
        }

        val programsList = getResource(gatewayId, "$normalized/switchPrograms")
        val firstRef = programsList?.references?.firstOrNull()?.id?.substringAfterLast("/")
        return firstRef?.takeIf { it.isNotBlank() } ?: "A"
    }

    suspend fun getSwitchProgram(
        gatewayId: String,
        programResourcePath: String
    ): SwitchProgramResource? = withContext(Dispatchers.IO) {
        val normalizedPath = if (programResourcePath.startsWith("/")) programResourcePath else "/$programResourcePath"
        val token = authManager.getValidAccessToken()
        val request = Request.Builder()
            .url("$baseUrl/gateways/$gatewayId/resource$normalizedPath")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) return@use null
            runCatching {
                json.decodeFromString<SwitchProgramResource>(body)
            }.getOrNull()
        }
    }

    suspend fun getRawResourceJson(
        gatewayId: String,
        resourcePath: String
    ): String? = withContext(Dispatchers.IO) {
        val normalizedPath = if (resourcePath.startsWith("/")) resourcePath else "/$resourcePath"
        val token = authManager.getValidAccessToken()
        val request = Request.Builder()
            .url("$baseUrl/gateways/$gatewayId/resource$normalizedPath")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) null else body
        }
    }

    suspend fun putSwitchProgramDetailed(
        gatewayId: String,
        programResourcePath: String,
        switchPoints: List<SwitchPoint>,
        existingProgram: SwitchProgramResource? = null
    ): PutResponseDetail = withContext(Dispatchers.IO) {
        val normalizedPath = if (programResourcePath.startsWith("/")) programResourcePath else "/$programResourcePath"
        val token = authManager.getValidAccessToken()

        // Primary format (verified via decompiled com.bosch.tt.dashtt PutSwitchPoints @JvmInline value class
        // and live K30 gateway 101845229): top-level JSON array of SwitchPoint objects -> HTTP 204 No Content
        val rawArrayPayload = json.encodeToString(switchPoints)
        val req1 = Request.Builder()
            .url("$baseUrl/gateways/$gatewayId/resource$normalizedPath")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .put(rawArrayPayload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val res1 = httpClient.newCall(req1).execute().use { response ->
            PutResponseDetail(
                ok = response.isSuccessful,
                httpCode = response.code,
                responseBody = response.body?.string().orEmpty().take(240),
                usedFormat = "raw-array"
            )
        }
        if (res1.ok) return@withContext res1

        // Fallback: object-wrapped {"switchPoints": [...]} for older KM200/PointT gateways
        @Serializable
        data class SwitchPointsPayload(val switchPoints: List<SwitchPoint>)

        val wrappedPayload = json.encodeToString(SwitchPointsPayload(switchPoints))
        val req2 = Request.Builder()
            .url("$baseUrl/gateways/$gatewayId/resource$normalizedPath")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .put(wrappedPayload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val res2 = httpClient.newCall(req2).execute().use { response ->
            PutResponseDetail(
                ok = response.isSuccessful,
                httpCode = response.code,
                responseBody = response.body?.string().orEmpty().take(240),
                usedFormat = "switchPoints-only"
            )
        }
        if (res2.ok) return@withContext res2

        // Return combined failure diagnostics from both attempts
        PutResponseDetail(
            ok = false,
            httpCode = res1.httpCode,
            responseBody = "attempt1(${res1.httpCode}): ${res1.responseBody.ifBlank { "empty" }} | attempt2(${res2.httpCode}): ${res2.responseBody.ifBlank { "empty" }}",
            usedFormat = "failed"
        )
    }

    suspend fun putSwitchProgram(
        gatewayId: String,
        programResourcePath: String,
        switchPoints: List<SwitchPoint>
    ): Boolean = putSwitchProgramDetailed(gatewayId, programResourcePath, switchPoints).ok

    suspend fun crawlResourceTree(
        gatewayId: String,
        rootPaths: List<String> = DEFAULT_ROOT_RESOURCES,
        maxDepth: Int = 5,
        onNodeDiscovered: ((String, ResourceNode) -> Unit)? = null
    ): Map<String, ResourceNode> {
        val discovered = linkedMapOf<String, ResourceNode>()
        val visited = mutableSetOf<String>()

        suspend fun visit(path: String, depth: Int) {
            val normalized = if (path.startsWith("/")) path else "/$path"
            if (depth > maxDepth || !visited.add(normalized)) return

            val node = getResource(gatewayId, normalized) ?: return
            discovered[normalized] = node
            onNodeDiscovered?.invoke(normalized, node)

            val refs = node.references.orEmpty()
            for (ref in refs) {
                if (ref.id.isNotBlank()) {
                    visit(ref.id, depth + 1)
                }
            }
        }

        for (root in rootPaths) {
            visit(root, depth = 0)
        }
        return discovered
    }

    suspend fun setHeatingOperationMode(
        gatewayId: String,
        hcId: String = "hc1",
        mode: String
    ): Boolean = putResourceValue(
        gatewayId = gatewayId,
        resourcePath = "/heatingCircuits/$hcId/operationMode",
        value = JsonPrimitive(mode)
    )

    suspend fun setHeatingManualRoomSetpoint(
        gatewayId: String,
        hcId: String = "hc1",
        temperatureCelsius: Double
    ): Boolean = putResourceValue(
        gatewayId = gatewayId,
        resourcePath = "/heatingCircuits/$hcId/manualRoomSetpoint",
        value = JsonPrimitive(temperatureCelsius)
    )

    suspend fun setHeatingTemperatureLevel(
        gatewayId: String,
        hcId: String = "hc1",
        levelName: String = "comfort2",
        temperatureCelsius: Double
    ): Boolean = putResourceValue(
        gatewayId = gatewayId,
        resourcePath = "/heatingCircuits/$hcId/temperatureLevels/$levelName",
        value = JsonPrimitive(temperatureCelsius)
    )

    suspend fun setDhwOperationMode(
        gatewayId: String,
        dhwId: String = "dhw1",
        mode: String
    ): Boolean = putResourceValue(
        gatewayId = gatewayId,
        resourcePath = "/dhwCircuits/$dhwId/operationMode",
        value = JsonPrimitive(mode)
    )

    suspend fun setDhwExtraCharge(
        gatewayId: String,
        dhwId: String = "dhw1",
        start: Boolean
    ): Boolean = putResourceValue(
        gatewayId = gatewayId,
        resourcePath = "/dhwCircuits/$dhwId/charge",
        value = JsonPrimitive(if (start) "start" else "stop")
    )

    suspend fun getHeatPumpTelemetry(
        gatewayId: String,
        spotPriceGrossCt: Double? = null,
        spotPriceNetCt: Double? = null
    ): HeatPumpTelemetrySnapshot = withContext(Dispatchers.IO) {
        val now = java.time.Instant.now()
        val nowIso = java.time.format.DateTimeFormatter.ISO_INSTANT.format(now)
        val epochSeconds = now.epochSecond

        fun parseDouble(node: ResourceNode?): Double? {
            val prim = node?.value as? JsonPrimitive ?: return null
            return prim.content.toDoubleOrNull()
        }

        fun parseString(node: ResourceNode?): String? {
            val prim = node?.value as? JsonPrimitive
            if (prim != null) return prim.content
            val arr = node?.value as? kotlinx.serialization.json.JsonArray
            if (arr != null && arr.isNotEmpty()) {
                return arr.joinToString(",") { (it as? JsonPrimitive)?.content ?: it.toString() }
            }
            return null
        }

        val outdoorNode = getResource(gatewayId, "/system/sensors/temperatures/outdoor_t1")
        val supplyNode = getResource(gatewayId, "/heatSources/actualSupplyTemperature")
        val returnNode = getResource(gatewayId, "/heatSources/returnTemperature")
        val condNode = getResource(gatewayId, "/heatSources/hs1/supplyFlowCondenserTemp")
        val modNode = getResource(gatewayId, "/heatSources/actualModulation")
        val demandNode = getResource(gatewayId, "/heatSources/actualHeatDemand")
        val chStatusNode = getResource(gatewayId, "/heatSources/chStatus")
        val setpointNode = getResource(gatewayId, "/heatingCircuits/hc1/currentRoomSetpoint")

        HeatPumpTelemetrySnapshot(
            timestampIso = nowIso,
            epochSeconds = epochSeconds,
            outdoorTempC = parseDouble(outdoorNode),
            supplyTempC = parseDouble(supplyNode),
            returnTempC = parseDouble(returnNode),
            condenserTempC = parseDouble(condNode),
            setpointTempC = parseDouble(setpointNode),
            modulationPct = parseDouble(modNode),
            demand = parseString(demandNode),
            chStatus = parseString(chStatusNode),
            spotPriceGrossCt = spotPriceGrossCt,
            spotPriceNetCt = spotPriceNetCt
        )
    }
}
