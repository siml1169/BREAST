package de.breast.ui

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import de.breast.auth.UserSettingsStore
import de.breast.telemetry.HeatingCurveAnalyzer
import de.breast.telemetry.SqliteTelemetryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HeatingCurveFullscreenActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OUTDOOR_TEMP = "extra_outdoor_temp"
        const val EXTRA_SUPPLY_TEMP = "extra_supply_temp"
        const val EXTRA_RETURN_TEMP = "extra_return_temp"
        const val EXTRA_SLIDER_TEMP = "extra_slider_temp"
    }

    private lateinit var chartView: HeatingCurveChartView
    private lateinit var telemetryStore: SqliteTelemetryStore
    private lateinit var settingsStore: UserSettingsStore
    private val curveAnalyzer = HeatingCurveAnalyzer()

    private lateinit var liveSummaryText: TextView
    private lateinit var formulaBottomText: TextView

    // Parallel shift toggle states: 0.0K (20°C), +2.0K (21.5°C), +7.3K (25.5°C)
    private val shiftOffsets = listOf(
        0.0 to "+0 K (20.0°C base)",
        2.0 to "+2.0 K (21.5°C)",
        7.3 to "+7.3 K (25.5°C)"
    )
    private var currentShiftIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        telemetryStore = SqliteTelemetryStore(this)
        settingsStore = UserSettingsStore(this)

        val savedShift = settingsStore.chartParallelShiftK
        val foundIdx = shiftOffsets.indexOfFirst { kotlin.math.abs(it.first - savedShift) < 0.1 }
        currentShiftIndex = if (foundIdx >= 0) foundIdx else 0

        setContentView(buildContentView())
        loadTelemetryData()
    }

    private fun isDarkMode(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun dp(dpVal: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dpVal,
            resources.displayMetrics
        ).toInt()
    }

    private fun roundedDrawable(bgColor: Int, strokeColor: Int, cornerDp: Float = 8f): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            setStroke(dp(1.2f), strokeColor)
            cornerRadius = dp(cornerDp).toFloat()
        }
    }

    private fun buildContentView(): View {
        val dark = isDarkMode()
        val bgRoot = if (dark) Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC")
        val textPrimary = if (dark) Color.parseColor("#F1F5F9") else Color.parseColor("#0F172A")
        val textMuted = if (dark) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgRoot)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }

        // Top Navigation & Metrics Row
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6f) }
        }

        val closeBtn = Button(this).apply {
            text = "✕ Close"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            val btnBg = Color.parseColor("#334155")
            val btnStroke = Color.parseColor("#475569")
            background = roundedDrawable(btnBg, btnStroke, 8f)
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34f)
            ).apply { rightMargin = dp(12f) }
            setOnClickListener { finish() }
        }
        topBar.addView(closeBtn)

        val titleText = TextView(this).apply {
            text = "HEATING CURVE FULLSCREEN (BOSCH HPC 410 & TELEMETRY)"
            setTextColor(textPrimary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(titleText)

        liveSummaryText = TextView(this).apply {
            val out = intent.getDoubleExtra(EXTRA_OUTDOOR_TEMP, -999.0)
            val flow = intent.getDoubleExtra(EXTRA_SUPPLY_TEMP, -999.0)
            val ret = intent.getDoubleExtra(EXTRA_RETURN_TEMP, -999.0)
            val slider = intent.getDoubleExtra(EXTRA_SLIDER_TEMP, settingsStore.sliderTempC)

            text = if (out > -900.0 && flow > -900.0) {
                val dt = if (ret > -900.0) String.format(" | ΔT=%.1fK", flow - ret) else ""
                String.format("Outdoor: %.1f°C | Flow: %.1f°C%s | Target: %.1f°C", out, flow, dt, slider)
            } else {
                String.format("Target: %.1f°C", slider)
            }
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.END
        }
        topBar.addView(liveSummaryText)
        root.addView(topBar)

        // Overlay Picker Filter Chips (Scrollable Horizontal Row)
        val chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6f) }
        }

        val chipsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Helper to create toggle chip buttons
        fun createToggleChip(
            label: String,
            activeColorHex: String,
            initialState: Boolean,
            onToggle: (Boolean) -> Unit
        ): Button {
            var state = initialState
            val activeColor = Color.parseColor(activeColorHex)
            val inactiveBg = if (dark) Color.parseColor("#1E293B") else Color.parseColor("#E2E8F0")
            val inactiveStroke = if (dark) Color.parseColor("#334155") else Color.parseColor("#CBD5E1")
            val inactiveText = textMuted

            return Button(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(10f), dp(3f), dp(10f), dp(3f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(32f)
                ).apply { rightMargin = dp(6f) }

                fun updateStyle() {
                    if (state) {
                        text = "✓ $label"
                        setTextColor(Color.WHITE)
                        background = roundedDrawable(activeColor, activeColor, 16f)
                    } else {
                        text = label
                        setTextColor(inactiveText)
                        background = roundedDrawable(inactiveBg, inactiveStroke, 16f)
                    }
                }
                updateStyle()

                setOnClickListener {
                    state = !state
                    updateStyle()
                    onToggle(state)
                }
            }
        }

        // Toggles for all curves - persisted to UserSettingsStore
        chipsContainer.addView(createToggleChip("HPC [1] 45°", "#0284C7", settingsStore.chartShowHpc1) {
            settingsStore.chartShowHpc1 = it
            chartView.showHpcCurve1 = it
        })
        chipsContainer.addView(createToggleChip("HPC [2] 40°", "#6366F1", settingsStore.chartShowHpc2) {
            settingsStore.chartShowHpc2 = it
            chartView.showHpcCurve2 = it
        })
        chipsContainer.addView(createToggleChip("HPC [3] 35° Low-E", "#9333EA", settingsStore.chartShowHpc3) {
            settingsStore.chartShowHpc3 = it
            chartView.showHpcCurve3 = it
        })
        chipsContainer.addView(createToggleChip("HPC [4] 35° FBH", "#0891B2", settingsStore.chartShowHpc4) {
            settingsStore.chartShowHpc4 = it
            chartView.showHpcCurve4 = it
        })
        chipsContainer.addView(createToggleChip("HPC [5] 60° Rad", "#EA580C", settingsStore.chartShowHpc5) {
            settingsStore.chartShowHpc5 = it
            chartView.showHpcCurve5 = it
        })
        chipsContainer.addView(createToggleChip("Equilibrium Pts", "#10B981", settingsStore.chartShowScatterSteadyState) {
            settingsStore.chartShowScatterSteadyState = it
            chartView.showScatterSteadyState = it
        })
        chipsContainer.addView(createToggleChip("Warm-Up Pts", "#F59E0B", settingsStore.chartShowScatterWarmup) {
            settingsStore.chartShowScatterWarmup = it
            chartView.showScatterWarmup = it
        })
        chipsContainer.addView(createToggleChip("Live Crosshair", "#EF4444", settingsStore.chartShowLivePoint) {
            settingsStore.chartShowLivePoint = it
            chartView.showLivePoint = it
        })

        // Shift selector button
        val shiftBtn = Button(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedDrawable(Color.parseColor("#475569"), Color.parseColor("#64748B"), 16f)
            setPadding(dp(10f), dp(3f), dp(10f), dp(3f))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(32f)
            ).apply { rightMargin = dp(6f) }
            text = "Shift: ${shiftOffsets[currentShiftIndex].second}"

            setOnClickListener {
                currentShiftIndex = (currentShiftIndex + 1) % shiftOffsets.size
                val (offsetK, label) = shiftOffsets[currentShiftIndex]
                text = "Shift: $label"
                settingsStore.chartParallelShiftK = offsetK
                chartView.parallelShiftK = offsetK
                val analysis = chartView.empiricalCurve
                if (analysis != null) {
                    val decayStr = analysis.hourlyCoolingRateC?.let { String.format(" • Thermal decay: -%.2f °C/h", it) } ?: ""
                    val warmupStr = analysis.estimatedWarmupMinutes?.let { String.format(" • Warmup ramp: ~%.0f min", it) } ?: ""
                    formulaBottomText.text = String.format(
                        "Bosch HPC 410 Design Curves • Shift: +%.1f K • %d steady-state equilibrium pts (%d ramp pts excluded)%s%s",
                        offsetK,
                        analysis.steadyStateSampleCount,
                        analysis.transientSampleCount,
                        warmupStr,
                        decayStr
                    )
                }
            }
        }
        chipsContainer.addView(shiftBtn)

        chipsScroll.addView(chipsContainer)
        root.addView(chipsScroll)

        // Main Horizontal Chart
        chartView = HeatingCurveChartView(this).apply {
            isDarkMode = dark
            showHpcCurve1 = settingsStore.chartShowHpc1
            showHpcCurve2 = settingsStore.chartShowHpc2
            showHpcCurve3 = settingsStore.chartShowHpc3
            showHpcCurve4 = settingsStore.chartShowHpc4
            showHpcCurve5 = settingsStore.chartShowHpc5
            showEmpiricalCurve = false
            showScatterSteadyState = settingsStore.chartShowScatterSteadyState
            showScatterWarmup = settingsStore.chartShowScatterWarmup
            showLivePoint = settingsStore.chartShowLivePoint
            parallelShiftK = shiftOffsets[currentShiftIndex].first
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(chartView)

        // Bottom Info & Telemetry Summary
        formulaBottomText = TextView(this).apply {
            text = "Loading Bosch HPC 410 design curves and steady-state telemetry..."
            setTextColor(textMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4f), 0, 0)
        }
        root.addView(formulaBottomText)

        return root
    }

    private fun loadTelemetryData() {
        lifecycleScope.launch {
            val snapshots = withContext(Dispatchers.IO) {
                telemetryStore.getRecentSnapshots(limit = 1000)
            }
            val targetTemp = settingsStore.sliderTempC
            val analysis = curveAnalyzer.analyzeHeatingCurve(
                snapshots = snapshots,
                targetSetpointTempC = targetTemp,
                latitude = settingsStore.weatherLatitude,
                longitude = settingsStore.weatherLongitude
            )
            val latest = snapshots.lastOrNull()

            chartView.targetSetpointTempC = targetTemp
            chartView.snapshots = snapshots
            chartView.empiricalCurve = analysis
            chartView.currentOutdoorTempC = latest?.outdoorTempC
            chartView.currentSupplyTempC = latest?.supplyTempC

            if (analysis != null) {
                val decayStr = analysis.hourlyCoolingRateC?.let { String.format(" • Thermal decay: -%.2f °C/h", it) } ?: ""
                val warmupStr = analysis.estimatedWarmupMinutes?.let { String.format(" • Warmup ramp: ~%.0f min", it) } ?: ""
                formulaBottomText.text = String.format(
                    "Bosch HPC 410 Design Curves • Shift: +%.1f K • %d steady-state equilibrium pts (%d ramp pts excluded)%s%s",
                    chartView.parallelShiftK,
                    analysis.steadyStateSampleCount,
                    analysis.transientSampleCount,
                    warmupStr,
                    decayStr
                )
            } else {
                formulaBottomText.text = "Logged ${snapshots.size} snapshots. Central heating steady-state data points will appear as heat pump runs."
            }
        }
    }
}
