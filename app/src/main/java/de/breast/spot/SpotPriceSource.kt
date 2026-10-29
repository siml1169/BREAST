package de.breast.spot

import kotlinx.serialization.Serializable

@Serializable
enum class SpotPriceSource(
    val id: String,
    val displayName: String,
    val resolutionDescription: String
) {
    VATTENFALL("vattenfall", "Vattenfall (Ökostrom Dynamik)", "15-min official tariff API"),
    ENERGY_CHARTS("energy_charts", "Fraunhofer Energy-Charts", "15-min raster (EPEX Spot DE-LU)"),
    AWATTAR("awattar", "aWATTar DE", "60-min prompt Day-Ahead (~13:00 CET)");

    companion object {
        fun fromId(id: String): SpotPriceSource? =
            values().firstOrNull { it.id.equals(id, ignoreCase = true) }

        val ALL: List<SpotPriceSource> = values().toList()

        val DEFAULT_ENABLED: Set<String> = setOf(VATTENFALL.id, ENERGY_CHARTS.id, AWATTAR.id)
    }
}

data class SourceTestResult(
    val source: SpotPriceSource,
    val success: Boolean,
    val slotCount: Int,
    val latencyMs: Long,
    val message: String
)
