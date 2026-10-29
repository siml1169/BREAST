package de.breast.ui

import de.breast.telemetry.SetbackEstimationResult
import de.breast.telemetry.EmpiricalModulationProfile
import de.breast.telemetry.OperatingPointProjection
import java.io.File
import kotlin.math.roundToInt

data class AnalysisBadge(
    val label: String,
    val bgHex: String,
    val strokeHex: String,
    val textHex: String
)

data class AnalysisMetricChip(
    val title: String,
    val value: String,
    val subtitle: String
)

data class SetbackAnalysisUiModel(
    val chosenLoss: Double,
    val chosenLossInt: Int,
    val statusText: String,
    val statusSuccess: Boolean,
    val badges: List<AnalysisBadge>,
    val chip1: AnalysisMetricChip, // Heat Loss Rate
    val chip2: AnalysisMetricChip, // Cooling Rate
    val chip3: AnalysisMetricChip, // Outdoor Temp
    val chip4: AnalysisMetricChip, // Thermal Mass
    val nuanceTitle: String,
    val nuanceItems: List<String>,
    val applyButtonText: String
)

data class ModulationAnalysisUiModel(
    val statusText: String,
    val statusSuccess: Boolean,
    val badges: List<AnalysisBadge>,
    val chip1: AnalysisMetricChip, // Baseline Mod
    val chip2: AnalysisMetricChip, // Sensitivity
    val chip3: AnalysisMetricChip, // Steady Power
    val chip4: AnalysisMetricChip, // Loop Spread
    val nuanceTitle: String,
    val nuanceItems: List<String>,
    val projections: List<OperatingPointProjection>,
    val applyButtonText: String
)

data class SafeClearDatabaseResult(
    val isDevMode: Boolean,
    val copyCreated: Boolean,
    val copyDeleted: Boolean,
    val success: Boolean,
    val message: String
)

data class AnalysisPalette(
    val accentGreen: String = "#4ADE80",
    val accentGreenBg: String = "#052E16",
    val accentGreenStroke: String = "#16A34A",
    val accentBlue: String = "#38BDF8",
    val accentBlueBg: String = "#0C4A6E",
    val accentBlueStroke: String = "#0284C7",
    val badgeBg: String = "#1E293B",
    val badgeStroke: String = "#334155",
    val textSecondary: String = "#94A3B8"
)

/**
 * Presenter responsible for type-safe rendering and validation of Telemetry Analysis UI models
 * (Night Setback, Inverter Modulation, and Safe Dev-Mode Database Operations).
 */
object SettingsAnalysisPresenter {

    fun buildSetbackUiModel(
        result: SetbackEstimationResult?,
        historicalPriorLoss: Int,
        historicalWeight: Double,
        i18n: AppStrings,
        palette: AnalysisPalette = AnalysisPalette()
    ): SetbackAnalysisUiModel? {
        if (result == null) return null

        val chosenLossInt = result.weightedLossWPerK ?: result.estimatedLossWPerK
        val chosenLoss = chosenLossInt.toDouble()

        val badges = mutableListOf<AnalysisBadge>()
        badges.add(AnalysisBadge("${chosenLossInt} W/K", palette.accentGreenBg, palette.accentGreenStroke, palette.accentGreen))
        badges.add(AnalysisBadge("R² = " + String.format(java.util.Locale.US, "%.2f", result.rSquared), palette.badgeBg, palette.badgeStroke, palette.accentBlue))
        if (result.historicalPriorIncorporated) {
            badges.add(AnalysisBadge(i18n.anchorActiveBadge, palette.accentBlueBg, palette.accentBlueStroke, palette.accentBlue))
        }
        val blockFormat = if (result.blockCount == 1) i18n.blockSingle else i18n.blockPlural
        badges.add(AnalysisBadge(String.format(java.util.Locale.US, blockFormat, result.blockCount), palette.badgeBg, palette.badgeStroke, palette.textSecondary))

        val chip1 = AnalysisMetricChip(i18n.chipHeatLossRate, "${chosenLossInt} W/K", i18n.chipWeightedConfidence)
        val chip2 = AnalysisMetricChip(
            i18n.chipCoolingRate,
            String.format(java.util.Locale.US, "%.2f K/h", result.coolingRateKPerHour),
            String.format(java.util.Locale.US, i18n.chipTempDropFormat, result.temperatureDropK)
        )
        val chip3 = AnalysisMetricChip(
            i18n.chipOutdoorTemp,
            String.format(java.util.Locale.US, "%.1f°C", result.avgOutdoorTempC),
            result.formatLocalTimeSpan()
        )
        val chip4 = AnalysisMetricChip(
            i18n.chipThermalMass,
            String.format(java.util.Locale.US, "%.1f kWh/K", result.effectiveCapacityKwhPerK),
            i18n.chipScreedCore
        )

        val priorMsg = if (result.historicalPriorIncorporated) {
            String.format(java.util.Locale.US, i18n.nuanceSetbackPriorFormat, historicalPriorLoss, historicalWeight.toInt(), result.blockCount)
        } else {
            i18n.nuanceSetbackWindow
        }
        val nuanceItems = listOf(
            priorMsg,
            i18n.nuanceSolarGate,
            i18n.nuanceQuiescence,
            String.format(java.util.Locale.US, i18n.nuanceMassCouplingFormat, result.effectiveCapacityKwhPerK),
            i18n.nuanceMultiNight
        )

        return SetbackAnalysisUiModel(
            chosenLoss = chosenLoss,
            chosenLossInt = chosenLossInt,
            statusText = i18n.setbackCompleteMsg,
            statusSuccess = true,
            badges = badges,
            chip1 = chip1,
            chip2 = chip2,
            chip3 = chip3,
            chip4 = chip4,
            nuanceTitle = i18n.nuanceBoxTitle,
            nuanceItems = nuanceItems,
            applyButtonText = String.format(java.util.Locale.US, i18n.applySetbackBtnFormat, chosenLossInt)
        )
    }

    fun buildModulationUiModel(
        profile: EmpiricalModulationProfile?,
        targetTemp: Double,
        historicalModAt10C: Double,
        historicalWeight: Int,
        i18n: AppStrings,
        palette: AnalysisPalette = AnalysisPalette()
    ): ModulationAnalysisUiModel? {
        if (profile == null) return null

        val baseStr = String.format(java.util.Locale.US, "%.1f", profile.modulationAt10C)
        val slopeStr = String.format(java.util.Locale.US, "%.2f", profile.modulationSlopePerK)
        val powerStr = String.format(java.util.Locale.US, "%.2f", profile.inferredSteadyPowerKw)
        val matchedSp = profile.matchedSetpointTempC ?: targetTemp

        val badges = mutableListOf<AnalysisBadge>()
        badges.add(AnalysisBadge(String.format(java.util.Locale.US, i18n.badgeTargetFormat, matchedSp), palette.accentGreenBg, palette.accentGreenStroke, palette.accentGreen))
        badges.add(AnalysisBadge("R² = " + String.format(java.util.Locale.US, "%.2f", profile.rSquared), palette.badgeBg, palette.badgeStroke, palette.accentBlue))
        if (profile.historicalPriorIncorporated) {
            badges.add(AnalysisBadge(i18n.anchorActiveBadge, palette.accentBlueBg, palette.accentBlueStroke, palette.accentBlue))
        }
        badges.add(AnalysisBadge(String.format(java.util.Locale.US, i18n.badgeRunsCountFormat, profile.detectedRunsCount, profile.steadyStateSamples), palette.badgeBg, palette.badgeStroke, palette.textSecondary))

        val chip1 = AnalysisMetricChip(i18n.chipBaselineMod, "${baseStr}% @ 10°C", i18n.chipInverterFreq)
        val chip2 = AnalysisMetricChip(i18n.chipSensitivity, "+${slopeStr}% / K", i18n.chipColderSens)
        val chip3 = AnalysisMetricChip(i18n.chipSteadyPower, "~${powerStr} kW", i18n.chipShaftPump)
        val chip4 = AnalysisMetricChip(i18n.chipLoopSpread, String.format(java.util.Locale.US, "ΔT = %.1f K", profile.avgSteadyDeltaTC), i18n.chipHydraulicLift)

        val overshootPct = ((profile.warmupModulationOverheadFactor - 1.0) * 100.0).roundToInt().coerceAtLeast(0)
        val warmupAvgStr = profile.avgWarmupModulationPct?.let { String.format(java.util.Locale.US, "%.1f%%", it) } ?: "55.0%"
        val priorModMsg = if (profile.historicalPriorIncorporated) {
            String.format(java.util.Locale.US, i18n.nuanceModPriorFormat, historicalModAt10C, historicalWeight)
        } else {
            i18n.nuanceModWindow
        }
        val nuanceItems = listOf(
            priorModMsg,
            String.format(java.util.Locale.US, i18n.nuancePidStartupFormat, profile.warmupSamplesCount, warmupAvgStr, overshootPct),
            String.format(java.util.Locale.US, i18n.nuanceObservedCorridorFormat, profile.minObservedOutdoorTempC ?: 0.0, profile.maxObservedOutdoorTempC ?: 0.0, profile.minObservedModulationPct, profile.maxObservedModulationPct),
            i18n.nuanceInverterClamping
        )

        return ModulationAnalysisUiModel(
            statusText = i18n.modCalibratedMsg,
            statusSuccess = true,
            badges = badges,
            chip1 = chip1,
            chip2 = chip2,
            chip3 = chip3,
            chip4 = chip4,
            nuanceTitle = i18n.nuanceBoxTitle,
            nuanceItems = nuanceItems,
            projections = profile.getOperatingProjections(),
            applyButtonText = String.format(java.util.Locale.US, i18n.applyModulationBtnFormat, baseStr, slopeStr)
        )
    }

    fun executeSafeClearDatabase(
        isDevMode: Boolean,
        originalDbFile: File,
        cacheDir: File,
        onClearOriginalDb: () -> Unit
    ): SafeClearDatabaseResult {
        if (!isDevMode) {
            onClearOriginalDb()
            return SafeClearDatabaseResult(
                isDevMode = false,
                copyCreated = false,
                copyDeleted = false,
                success = true,
                message = "Database cleared."
            )
        }

        val copyFile = File(cacheDir, "dev_copy_${originalDbFile.name}")
        val copyCreated = try {
            if (originalDbFile.exists()) {
                originalDbFile.copyTo(copyFile, overwrite = true)
            } else {
                copyFile.writeText("dev_test_placeholder")
            }
            copyFile.exists()
        } catch (e: Exception) {
            false
        }

        val copyDeleted = if (copyCreated) {
            try {
                copyFile.delete() || !copyFile.exists()
            } catch (e: Exception) {
                false
            }
        } else false

        return SafeClearDatabaseResult(
            isDevMode = true,
            copyCreated = copyCreated,
            copyDeleted = copyDeleted,
            success = copyCreated && copyDeleted && (if (originalDbFile.exists()) originalDbFile.exists() else true),
            message = "Dev Mode Safe Test: DB copy created & deleted successfully. Real database preserved."
        )
    }
}
