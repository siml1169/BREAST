package de.breast.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import de.breast.auth.UserSettingsStore
import de.breast.daemon.BreastNotificationHelper
import de.breast.daemon.SpotScheduleJobService
import de.breast.optimizer.OptimizationPreference
import de.breast.spot.SpotPriceClient
import de.breast.spot.SpotPriceSource
import de.breast.telemetry.SetbackHeatLossEstimator
import de.breast.telemetry.SqliteTelemetryStore
import de.breast.weather.WeatherForecastClient
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {

    private data class UiPalette(
        val pageBg: String,
        val cardBg: String,
        val cardStroke: String,
        val textPrimary: String,
        val textSecondary: String,
        val textMuted: String,
        val accentBlue: String,
        val accentBlueBg: String,
        val accentBlueStroke: String,
        val accentGreen: String,
        val accentGreenBg: String,
        val accentGreenStroke: String,
        val accentAmber: String,
        val accentAmberBg: String,
        val accentAmberStroke: String,
        val secondaryBtnBg: String,
        val secondaryBtnStroke: String,
        val secondaryBtnText: String,
        val badgeBg: String,
        val badgeStroke: String
    )

    private lateinit var palette: UiPalette
    private lateinit var settingsStore: UserSettingsStore
    private lateinit var telemetryStore: SqliteTelemetryStore
    private val httpClient = OkHttpClient()
    private val spotClient = SpotPriceClient(httpClient)
    private val weatherClient by lazy {
        WeatherForecastClient(
            httpClient = httpClient,
            latitude = settingsStore.weatherLatitude,
            longitude = settingsStore.weatherLongitude
        )
    }

    // UI elements to update dynamically
    private lateinit var cascadeOrderText: TextView
    private val sourceStatusViews = mutableMapOf<SpotPriceSource, TextView>()
    private val sourcePrimaryButtons = mutableMapOf<SpotPriceSource, Button>()
    private val sourceSwitches = mutableMapOf<SpotPriceSource, Switch>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = UserSettingsStore(this)
        telemetryStore = SqliteTelemetryStore(this)

        val dark = isSystemInDarkMode()
        palette = createPalette(dark)
        applySystemBarColors(dark)

        setContentView(buildContentView())
    }

    private fun isSystemInDarkMode(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun createPalette(dark: Boolean): UiPalette {
        return if (dark) {
            UiPalette(
                pageBg = "#0B0F19",
                cardBg = "#131B2E",
                cardStroke = "#1E293B",
                textPrimary = "#F8FAFC",
                textSecondary = "#94A3B8",
                textMuted = "#64748B",
                accentBlue = "#38BDF8",
                accentBlueBg = "#0C4A6E",
                accentBlueStroke = "#0284C7",
                accentGreen = "#4ADE80",
                accentGreenBg = "#052E16",
                accentGreenStroke = "#16A34A",
                accentAmber = "#FBBF24",
                accentAmberBg = "#451A03",
                accentAmberStroke = "#F59E0B",
                secondaryBtnBg = "#1E293B",
                secondaryBtnStroke = "#475569",
                secondaryBtnText = "#E2E8F0",
                badgeBg = "#0F172A",
                badgeStroke = "#334155"
            )
        } else {
            UiPalette(
                pageBg = "#F1F5F9",
                cardBg = "#FFFFFF",
                cardStroke = "#E2E8F0",
                textPrimary = "#0F172A",
                textSecondary = "#475569",
                textMuted = "#64748B",
                accentBlue = "#0284C7",
                accentBlueBg = "#E0F2FE",
                accentBlueStroke = "#7DD3FC",
                accentGreen = "#15803D",
                accentGreenBg = "#DCFCE7",
                accentGreenStroke = "#86EFAC",
                accentAmber = "#B45309",
                accentAmberBg = "#FEF3C7",
                accentAmberStroke = "#FCD34D",
                secondaryBtnBg = "#F8FAFC",
                secondaryBtnStroke = "#CBD5E1",
                secondaryBtnText = "#1E293B",
                badgeBg = "#F1F5F9",
                badgeStroke = "#CBD5E1"
            )
        }
    }

    private fun applySystemBarColors(dark: Boolean) {
        val barColor = Color.parseColor(palette.pageBg)
        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appearance = if (dark) 0 else (
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
            window.insetsController?.setSystemBarsAppearance(
                appearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        }
    }

    private fun dp(dpVal: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dpVal.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun roundedBg(bgHex: String, strokeHex: String, cornerDp: Int = 12): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp).toFloat()
            setColor(Color.parseColor(bgHex))
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(palette.cardBg, palette.cardStroke, cornerDp = 16)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
            layoutParams = lp
        }
    }

    private fun cardSectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.parseColor(palette.textPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(2))
        }
    }

    private fun cardSubtitle(subtitle: String): TextView {
        return TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor(palette.textSecondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, 0, 0, dp(12))
        }
    }

    private fun createStyledButton(
        label: String,
        bgHex: String,
        strokeHex: String,
        textHex: String
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.parseColor(textHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(bgHex, strokeHex, cornerDp = 10)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
    }

    private fun createMetricChip(title: String, value: String, subtext: String? = null): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(palette.cardBg, palette.cardStroke, cornerDp = 8)
            setPadding(dp(8), dp(6), dp(8), dp(6))

            addView(TextView(this@SettingsActivity).apply {
                text = title
                setTextColor(Color.parseColor(palette.textMuted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = value
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
            })
            if (subtext != null) {
                addView(TextView(this@SettingsActivity).apply {
                    text = subtext
                    setTextColor(Color.parseColor(palette.accentGreen))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
                })
            }
        }
    }

    private fun createNuanceBox(title: String, items: List<String>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(palette.pageBg, palette.cardStroke, cornerDp = 8)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(6), 0, dp(6))
            layoutParams = lp

            addView(TextView(this@SettingsActivity).apply {
                text = title
                setTextColor(Color.parseColor(palette.accentBlue))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
            })

            for (item in items) {
                addView(TextView(this@SettingsActivity).apply {
                    text = item
                    setTextColor(Color.parseColor(palette.textSecondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                    setLineSpacing(dp(2).toFloat(), 1.0f)
                    setPadding(0, dp(2), 0, dp(2))
                })
            }
        }
    }

    private fun createProjectionTable(projections: List<de.breast.telemetry.OperatingPointProjection>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(palette.pageBg, palette.cardStroke, cornerDp = 8)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(0, dp(6), 0, dp(8))
            layoutParams = lp

            addView(TextView(this@SettingsActivity).apply {
                text = "Dynamic Temperature Operating Corridor"
                setTextColor(Color.parseColor(palette.accentBlue))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
            })

            for (proj in projections) {
                val row = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(3), 0, dp(3))
                    val rLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    layoutParams = rLp

                    addView(TextView(this@SettingsActivity).apply {
                        text = proj.scenarioLabel
                        setTextColor(Color.parseColor(palette.textPrimary))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)
                    })

                    val modStr = String.format(java.util.Locale.US, "%.1f%%", proj.predictedModulationPct) +
                        if (proj.isHardwareClamped) " (Floor)" else ""
                    addView(TextView(this@SettingsActivity).apply {
                        text = modStr
                        setTextColor(Color.parseColor(if (proj.isHardwareClamped) palette.accentAmber else palette.accentGreen))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setTypeface(typeface, Typeface.BOLD)
                        gravity = Gravity.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
                    })

                    val powerStr = String.format(java.util.Locale.US, "%.2f kW", proj.predictedPowerKw)
                    addView(TextView(this@SettingsActivity).apply {
                        text = powerStr
                        setTextColor(Color.parseColor(palette.textSecondary))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        gravity = Gravity.END
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f)
                    })
                }
                addView(row)
            }
        }
    }

    private fun buildContentView(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(palette.pageBg))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        // =========================================================================
        // TOP APP BAR
        // =========================================================================
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(16))
        }

        val backBtn = createStyledButton(
            label = "← Done",
            bgHex = palette.secondaryBtnBg,
            strokeHex = palette.secondaryBtnStroke,
            textHex = palette.accentBlue
        ).apply {
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn)

        val titleView = TextView(this).apply {
            text = "Settings"
            setTextColor(Color.parseColor(palette.textPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), 0, 0, 0)
        }
        topBar.addView(titleView)
        root.addView(topBar)

        // =========================================================================
        // 1. SPOT MARKET DATA SOURCES (MULTIPLE SOURCES + PRIMARY WITH FALLBACK)
        // =========================================================================
        val sourcesCard = createCard().apply {
            addView(cardSectionTitle("⚡ Spot Market Price Sources"))
            addView(cardSubtitle("Choose active exchange data providers and designate primary source. If the primary source is unavailable or delayed, automatic fallback triggers."))

            // Fallback Cascade Banner
            val cascadeBanner = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 10)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
                layoutParams = lp
            }
            cascadeOrderText = TextView(this@SettingsActivity).apply {
                setTextColor(Color.parseColor(palette.accentBlue))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
            }
            cascadeBanner.addView(cascadeOrderText)
            addView(cascadeBanner)

            // Sources List
            for (source in SpotPriceSource.ALL) {
                addView(buildSourceItemView(source))
            }

            // Test Price Sources Button
            val testBtn = createStyledButton(
                label = "Test Price Sources Now",
                bgHex = palette.secondaryBtnBg,
                strokeHex = palette.secondaryBtnStroke,
                textHex = palette.accentBlue
            ).apply {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
                layoutParams = lp
                setOnClickListener { runPriceSourceDiagnostics() }
            }
            addView(testBtn)
        }
        root.addView(sourcesCard)

        // =========================================================================
        // 2. LIVE TELEMETRY & REAL HEATING CURVE RECORDING
        // =========================================================================
        val telemetryCard = createCard().apply {
            addView(cardSectionTitle("📈 Live Telemetry & Heating Curve"))
            addView(cardSubtitle("Record heat pump outdoor, supply, return temperatures and modulation into SQLite database to empirically learn building thermal properties."))

            val recSwitch = Switch(this@SettingsActivity).apply {
                text = "Record Telemetry in Background"
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                isChecked = settingsStore.telemetryCollectionEnabled
                setPadding(0, dp(4), 0, dp(6))
                setOnCheckedChangeListener { _, isChecked ->
                    settingsStore.telemetryCollectionEnabled = isChecked
                    if (isChecked) {
                        SpotScheduleJobService.scheduleNextTelemetryAlarm(
                            this@SettingsActivity,
                            settingsStore.telemetryIntervalMinutes
                        )
                    } else {
                        SpotScheduleJobService.cancelTelemetryAlarm(this@SettingsActivity)
                    }
                    BreastNotificationHelper.updateBackgroundActiveNotification(this@SettingsActivity)
                    val st = if (isChecked) "ENABLED (${settingsStore.telemetryIntervalMinutes}m)" else "DISABLED"
                    Toast.makeText(this@SettingsActivity, "Telemetry recording $st", Toast.LENGTH_SHORT).show()
                }
            }
            addView(recSwitch)

            // Sampling Frequency Slider
            val freqHeaderRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(2))
            }
            freqHeaderRow.addView(TextView(this@SettingsActivity).apply {
                text = "Sampling Frequency"
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val freqBadge = TextView(this@SettingsActivity).apply {
                val m = settingsStore.telemetryIntervalMinutes
                text = if (m == 1) "Every 1 min" else if (m == 60) "Every 1 hour" else "Every $m min"
                setTextColor(Color.parseColor(palette.accentBlue))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 8)
            }
            freqHeaderRow.addView(freqBadge)
            addView(freqHeaderRow)

            val freqSeekBar = SeekBar(this@SettingsActivity).apply {
                max = 59 // 1..60
                progress = settingsStore.telemetryIntervalMinutes - 1
                setPadding(dp(4), dp(4), dp(4), dp(8))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val minutes = progress + 1
                        freqBadge.text = if (minutes == 1) "Every 1 min" else if (minutes == 60) "Every 1 hour" else "Every $minutes min"
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        val minutes = (seekBar?.progress ?: 14) + 1
                        settingsStore.telemetryIntervalMinutes = minutes
                        if (settingsStore.telemetryCollectionEnabled) {
                            SpotScheduleJobService.scheduleNextTelemetryAlarm(this@SettingsActivity, minutes)
                        }
                        BreastNotificationHelper.updateBackgroundActiveNotification(this@SettingsActivity)
                        Toast.makeText(this@SettingsActivity, "Frequency: Every $minutes min", Toast.LENGTH_SHORT).show()
                    }
                })
            }
            addView(freqSeekBar)

            // Telemetry Actions Row
            val actionsRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
                layoutParams = lp
            }

            val exportBtn = createStyledButton(
                label = "Export CSV",
                bgHex = palette.secondaryBtnBg,
                strokeHex = palette.secondaryBtnStroke,
                textHex = palette.accentGreen
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(6)
                }
                setOnClickListener { exportTelemetry() }
            }
            actionsRow.addView(exportBtn)

            val clearBtn = createStyledButton(
                label = "Clear Database",
                bgHex = palette.secondaryBtnBg,
                strokeHex = palette.secondaryBtnStroke,
                textHex = palette.accentAmber
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(6)
                }
                setOnClickListener { confirmClearDatabase() }
            }
            actionsRow.addView(clearBtn)
            addView(actionsRow)
        }
        root.addView(telemetryCard)

        // =========================================================================
        // 3. DEVELOPER / DEBUG MODE
        // =========================================================================
        val devCard = createCard().apply {
            addView(cardSectionTitle("🛠️ Developer & Diagnostics"))
            addView(cardSubtitle("Advanced diagnostic logging, API payload inspection, and test controls."))

            val debugSwitch = Switch(this@SettingsActivity).apply {
                text = "Developer / Debug Mode"
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                isChecked = settingsStore.debugModeEnabled
                setPadding(0, dp(4), 0, dp(6))
                setOnCheckedChangeListener { _, isChecked ->
                    settingsStore.debugModeEnabled = isChecked
                    val st = if (isChecked) "ENABLED (Console visible on main screen)" else "DISABLED"
                    Toast.makeText(this@SettingsActivity, "Debug mode $st", Toast.LENGTH_SHORT).show()
                }
            }
            addView(debugSwitch)

            val debugDesc = TextView(this@SettingsActivity).apply {
                text = "When enabled, live single-key communication logs and telemetry JSON output are rendered in a monospace terminal card at the bottom of the main dashboard."
                setTextColor(Color.parseColor(palette.textMuted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(0, dp(2), 0, dp(10))
            }
            addView(debugDesc)
        }
        root.addView(devCard)

        // =========================================================================
        // 4. HOLISTIC MULTI-CRITERIA & THERMODYNAMIC OPTIMIZATION
        // =========================================================================
        val holisticCard = createCard().apply {
            addView(cardSectionTitle("☀️ Holistic Thermodynamic & Energy Optimization"))
            addView(cardSubtitle("Optimizes for delivered heat cost (c_elec / COP), captures the midday solar Carnot peak, and calculates dynamic daily thermal energy demand."))

            val holisticSwitch = Switch(this@SettingsActivity).apply {
                text = "Holistic Multi-Criteria Optimization"
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                isChecked = settingsStore.holisticOptimizationEnabled
                setPadding(0, dp(4), 0, dp(4))
                setOnCheckedChangeListener { _, isChecked ->
                    settingsStore.holisticOptimizationEnabled = isChecked
                    val st = if (isChecked) "ENABLED" else "DISABLED (Using static hours)"
                    Toast.makeText(this@SettingsActivity, "Holistic optimization $st", Toast.LENGTH_SHORT).show()
                }
            }
            addView(holisticSwitch)

            val holisticDesc = TextView(this@SettingsActivity).apply {
                text = "Replaces rigid hour budgets with dynamic daily kWh demand. Runs during solar noon and 0-cent price windows where COP is highest."
                setTextColor(Color.parseColor(palette.textMuted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(0, 0, 0, dp(8))
            }
            addView(holisticDesc)

            // Optimization Preference Segmented Control (Comfort, Budget, Efficiency, Balanced)
            addView(TextView(this@SettingsActivity).apply {
                text = "Optimization Preference"
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(2))
            })

            var currentPref = OptimizationPreference.fromId(settingsStore.optimizationPreference)
            val prefSubtitle = TextView(this@SettingsActivity).apply {
                text = currentPref.subtitle
                setTextColor(Color.parseColor(palette.accentAmber))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, 0, 0, dp(6))
            }

            val prefBtnRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(8))
            }

            val prefButtons = mutableMapOf<OptimizationPreference, Button>()
            for (p in OptimizationPreference.entries) {
                val btn = Button(this@SettingsActivity).apply {
                    text = when (p) {
                        OptimizationPreference.BALANCED -> "Balanced"
                        OptimizationPreference.COMFORT -> "Comfort"
                        OptimizationPreference.BUDGET -> "Budget"
                        OptimizationPreference.EFFICIENCY -> "Efficiency"
                    }
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    isAllCaps = false
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    lp.setMargins(dp(2), 0, dp(2), 0)
                    layoutParams = lp
                }
                prefButtons[p] = btn
                prefBtnRow.addView(btn)
            }

            fun updatePrefUi(selected: OptimizationPreference) {
                currentPref = selected
                settingsStore.optimizationPreference = selected.id
                prefSubtitle.text = selected.subtitle
                for ((p, btn) in prefButtons) {
                    if (p == selected) {
                        btn.background = roundedBg(palette.accentBlueBg, palette.accentBlueStroke, cornerDp = 8)
                        btn.setTextColor(Color.parseColor(palette.accentBlue))
                        btn.setTypeface(null, Typeface.BOLD)
                    } else {
                        btn.background = roundedBg(palette.secondaryBtnBg, palette.secondaryBtnStroke, cornerDp = 8)
                        btn.setTextColor(Color.parseColor(palette.textPrimary))
                        btn.setTypeface(null, Typeface.NORMAL)
                    }
                }
            }

            for ((p, btn) in prefButtons) {
                btn.setOnClickListener { updatePrefUi(p) }
            }
            updatePrefUi(currentPref)

            addView(prefSubtitle)
            addView(prefBtnRow)

            // Screed Pre-Heat Buffering Slider
            val bufferHeaderRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(2))
            }
            bufferHeaderRow.addView(TextView(this@SettingsActivity).apply {
                text = "Screed Pre-Heat Buffering Limit"
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val bufferBadge = TextView(this@SettingsActivity).apply {
                val b = settingsStore.maxThermalBufferingK
                val kwh = String.format(java.util.Locale.US, "%.1f", b * 5.0)
                text = "+${String.format(java.util.Locale.US, "%.1f", b)} K (${kwh} kWh buffer)"
                setTextColor(Color.parseColor(palette.accentAmber))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 8)
            }
            bufferHeaderRow.addView(bufferBadge)
            addView(bufferHeaderRow)

            val bufferSeekBar = SeekBar(this@SettingsActivity).apply {
                max = 18 // 0..18 corresponds to 0.2K..2.0K (step 0.1K)
                progress = (((settingsStore.maxThermalBufferingK - 0.2) / 0.1).toInt()).coerceIn(0, 18)
                setPadding(dp(4), dp(4), dp(4), dp(6))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val k = 0.2 + progress * 0.1
                        val kwh = String.format(java.util.Locale.US, "%.1f", k * 5.0)
                        bufferBadge.text = "+${String.format(java.util.Locale.US, "%.1f", k)} K (${kwh} kWh buffer)"
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        val k = 0.2 + (seekBar?.progress ?: 10) * 0.1
                        settingsStore.maxThermalBufferingK = Math.round(k * 10.0) / 10.0
                    }
                })
            }
            addView(bufferSeekBar)

            // Building Heat Loss Slider
            val lossHeaderRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(2))
            }
            lossHeaderRow.addView(TextView(this@SettingsActivity).apply {
                text = "Building Heat Loss Estimate"
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val lossBadge = TextView(this@SettingsActivity).apply {
                val w = (settingsStore.buildingLossKwPerK * 1000.0).toInt()
                text = "$w W/K"
                setTextColor(Color.parseColor(palette.accentBlue))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 8)
            }
            lossHeaderRow.addView(lossBadge)
            addView(lossHeaderRow)

            val lossSeekBar = SeekBar(this@SettingsActivity).apply {
                max = 22 // 0..22 corresponds to 80 W/K .. 300 W/K (step 10 W/K)
                progress = ((((settingsStore.buildingLossKwPerK * 1000.0) - 80.0) / 10.0).toInt()).coerceIn(0, 22)
                setPadding(dp(4), dp(4), dp(4), dp(6))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val w = 80 + progress * 10
                        lossBadge.text = "$w W/K"
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        val w = 80 + (seekBar?.progress ?: 7) * 10
                        settingsStore.buildingLossKwPerK = w / 1000.0
                    }
                })
            }
            addView(lossSeekBar)

            // Empirical Heat Loss from Night Setback Telemetry
            val setbackCard = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 8)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, dp(4), 0, dp(8))
                layoutParams = lp
            }

            val setbackStatusText = TextView(this@SettingsActivity).apply {
                text = "Auto-derive building heat loss from return temperature cooldown during night setback."
                setTextColor(Color.parseColor(palette.textMuted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, 0, 0, dp(4))
            }
            setbackCard.addView(setbackStatusText)

            val setbackDetailsContainer = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            setbackCard.addView(setbackDetailsContainer)

            val applySetbackBtn = createStyledButton(
                label = "Apply Measured Value to Slider",
                bgHex = palette.accentGreenBg,
                strokeHex = palette.accentGreenStroke,
                textHex = palette.accentGreen
            ).apply {
                visibility = View.GONE
            }

            var lastMeasuredKw: Double? = null

            val analyzeSetbackBtn = createStyledButton(
                label = "Analyze Night Setback Telemetry",
                bgHex = palette.secondaryBtnBg,
                strokeHex = palette.secondaryBtnStroke,
                textHex = palette.accentBlue
            ).apply {
                setOnClickListener {
                    setbackStatusText.text = "Scanning up to 25,000 telemetry points for nocturnal setback cooling (≥2h after sunset until sunrise)..."
                    setbackStatusText.setTextColor(Color.parseColor(palette.accentAmber))
                    setbackDetailsContainer.visibility = View.GONE
                    applySetbackBtn.visibility = View.GONE
                    lifecycleScope.launch {
                        val snapshots = telemetryStore.getRecentSnapshots(25000)
                        val estimator = de.breast.telemetry.SetbackHeatLossEstimator(
                            defaultIndoorTempC = settingsStore.sliderTempC,
                            targetSetpointTempC = settingsStore.sliderTempC,
                            latitude = settingsStore.weatherLatitude,
                            longitude = settingsStore.weatherLongitude,
                            minHoursAfterSunset = 2.0,
                            enforceNocturnalOnly = true,
                            minDurationHours = 1.0
                        )
                        val priorLoss = if (settingsStore.historicalHeatLoss25kWeight > 0.0) settingsStore.historicalHeatLoss25kWPerK else null
                        val priorWeight = settingsStore.historicalHeatLoss25kWeight
                        val result = estimator.estimateBuildingHeatLoss(snapshots, priorLoss, priorWeight)
                        withContext(Dispatchers.Main) {
                            if (result != null) {
                                val chosenLoss = result.weightedLossWPerK ?: result.estimatedLossWPerK
                                lastMeasuredKw = chosenLoss / 1000.0

                                setbackStatusText.text = "✓ Nocturnal Setback Analysis Complete (25k Data Window)"
                                setbackStatusText.setTextColor(Color.parseColor(palette.accentGreen))

                                setbackDetailsContainer.removeAllViews()

                                // Badge Header
                                val badgeRow = LinearLayout(this@SettingsActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    setPadding(0, dp(4), 0, dp(6))
                                }
                                fun addHeaderBadge(label: String, bgHex: String, strokeHex: String, textHex: String) {
                                    badgeRow.addView(TextView(this@SettingsActivity).apply {
                                        text = label
                                        setTextColor(Color.parseColor(textHex))
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                                        setTypeface(typeface, Typeface.BOLD)
                                        background = roundedBg(bgHex, strokeHex, cornerDp = 6)
                                        setPadding(dp(6), dp(2), dp(6), dp(2))
                                        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                        lp.setMargins(0, 0, dp(6), 0)
                                        layoutParams = lp
                                    })
                                }
                                addHeaderBadge("${chosenLoss} W/K", palette.accentGreenBg, palette.accentGreenStroke, palette.accentGreen)
                                addHeaderBadge("R² = " + String.format(java.util.Locale.US, "%.2f", result.rSquared), palette.badgeBg, palette.badgeStroke, palette.accentBlue)
                                addHeaderBadge("${result.blockCount} Nocturnal Block(s)", palette.badgeBg, palette.badgeStroke, palette.textSecondary)
                                if (result.historicalPriorIncorporated) {
                                    addHeaderBadge("25k Anchor Active", palette.accentBlueBg, palette.accentBlueStroke, palette.accentBlue)
                                }
                                setbackDetailsContainer.addView(badgeRow)

                                // 2x2 Metric Chips Grid
                                val gridRow1 = LinearLayout(this@SettingsActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                    lp.setMargins(0, dp(2), 0, dp(4))
                                    layoutParams = lp
                                }
                                gridRow1.addView(createMetricChip("Heat Loss Rate", "${chosenLoss} W/K", "Weighted Confidence").apply {
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                                })
                                gridRow1.addView(createMetricChip("Cooling Rate", String.format(java.util.Locale.US, "%.2f K/h", result.coolingRateKPerHour), "Drop: " + String.format(java.util.Locale.US, "%.1f K", result.temperatureDropK)).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
                                })
                                setbackDetailsContainer.addView(gridRow1)

                                val gridRow2 = LinearLayout(this@SettingsActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                    lp.setMargins(0, 0, 0, dp(4))
                                    layoutParams = lp
                                }
                                gridRow2.addView(createMetricChip("Outdoor Temp", String.format(java.util.Locale.US, "%.1f°C", result.avgOutdoorTempC), result.formatLocalTimeSpan()).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                                })
                                gridRow2.addView(createMetricChip("Thermal Mass", String.format(java.util.Locale.US, "%.1f kWh/K", result.effectiveCapacityKwhPerK), "Screed Core C_eff").apply {
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
                                })
                                setbackDetailsContainer.addView(gridRow2)

                                // Nuances & Physical Audit Trail Box
                                val priorMsg = if (result.historicalPriorIncorporated) {
                                    "• 25k Historical Anchor: Blended cumulative historical baseline (${settingsStore.historicalHeatLoss25kWPerK} W/K, weight=${settingsStore.historicalHeatLoss25kWeight.toInt()}) with ${result.blockCount} active observation block(s)."
                                } else {
                                    "• 25k Data Window: Scanning up to 25,000 points; established initial multi-night building envelope baseline."
                                }
                                val nuanceItems = listOf(
                                    priorMsg,
                                    "• Solar Insolation Gate: Excluded daylight & dusk; strictly ≥2h post-sunset to sunrise to eliminate solar radiation lag.",
                                    "• System Quiescence: Verified compressor 0% modulation, heating & DHW idle, loop spread |Tsup - Tret| ≤ 1.5 K.",
                                    "• 2R2C State-Space Mass Coupling: Evaluated core discharge factor α (${String.format(java.util.Locale.US, "%.1f", result.effectiveCapacityKwhPerK)} kWh/K dynamic participating capacity).",
                                    "• Multi-Night Bridging: Seamlessly bridged across calendar midnight; confidence-weighted by R² × duration."
                                )
                                setbackDetailsContainer.addView(createNuanceBox("Process Nuances & Physical Adjustments", nuanceItems))
                                setbackDetailsContainer.visibility = View.VISIBLE

                                applySetbackBtn.text = "Apply ${chosenLoss} W/K to Heat Loss Setting"
                                applySetbackBtn.visibility = View.VISIBLE
                            } else {
                                setbackStatusText.text = "No valid nocturnal setback cooldown found (requires warm floor discharging ≥2h after sunset with compressor OFF). Leave heat pump in setback tonight to measure."
                                setbackStatusText.setTextColor(Color.parseColor(palette.accentAmber))
                                setbackDetailsContainer.visibility = View.GONE
                                applySetbackBtn.visibility = View.GONE
                            }
                        }
                    }
                }
            }

            applySetbackBtn.setOnClickListener {
                val kw = lastMeasuredKw ?: return@setOnClickListener
                settingsStore.buildingLossKwPerK = kw
                val w = (kw * 1000.0).roundToInt()
                lossBadge.text = "$w W/K"
                lossSeekBar.progress = (((w - 80) / 10.0).toInt()).coerceIn(0, 22)
                settingsStore.save25kHistoricalHeatLoss(w, 20.0)
                Toast.makeText(this@SettingsActivity, "Building heat loss updated to ${w} W/K and recorded in 25k anchor", Toast.LENGTH_SHORT).show()
                applySetbackBtn.visibility = View.GONE
            }

            setbackCard.addView(analyzeSetbackBtn)
            setbackCard.addView(applySetbackBtn)
            addView(setbackCard)

            // Empirical Ground-Truth Modulation Calibration
            val modulationCard = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 8)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, dp(4), 0, dp(8))
                layoutParams = lp
            }

            val modStatusText = TextView(this@SettingsActivity).apply {
                val currentBase = String.format(java.util.Locale.US, "%.1f", settingsStore.calibratedModulationAt10C)
                val currentSlope = String.format(java.util.Locale.US, "%.1f", settingsStore.calibratedModulationSlopePerK)
                text = "Inverter Modulation Model: ${currentBase}% @ 10°C (+${currentSlope}%/K colder)"
                setTextColor(Color.parseColor(palette.textMuted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, 0, 0, dp(4))
            }
            modulationCard.addView(modStatusText)

            val modulationDetailsContainer = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            modulationCard.addView(modulationDetailsContainer)

            val applyModulationBtn = createStyledButton(
                label = "Apply Calibrated Inverter Model",
                bgHex = palette.accentGreenBg,
                strokeHex = palette.accentGreenStroke,
                textHex = palette.accentGreen
            ).apply {
                visibility = View.GONE
            }

            var lastCalibratedProfile: de.breast.telemetry.EmpiricalModulationProfile? = null

            val analyzeModulationBtn = createStyledButton(
                label = "Analyze Inverter Modulation Telemetry",
                bgHex = palette.secondaryBtnBg,
                strokeHex = palette.secondaryBtnStroke,
                textHex = palette.accentBlue
            ).apply {
                setOnClickListener {
                    modStatusText.text = "Scanning up to 25,000 telemetry points for steady-state compressor modulation..."
                    modStatusText.setTextColor(Color.parseColor(palette.accentAmber))
                    modulationDetailsContainer.visibility = View.GONE
                    applyModulationBtn.visibility = View.GONE
                    lifecycleScope.launch {
                        val snapshots = telemetryStore.getRecentSnapshots(25000)
                        val targetTemp = settingsStore.sliderTempC
                        val priorMod = if (settingsStore.historicalModulation25kWeight > 0) settingsStore.historicalModulation25kAt10C else null
                        val priorSlope = if (settingsStore.historicalModulation25kWeight > 0) settingsStore.historicalModulation25kSlopePerK else null
                        val priorWeight = settingsStore.historicalModulation25kWeight.toDouble()
                        val profile = de.breast.telemetry.EmpiricalModulationAnalyzer().analyzeModulationProfile(
                            snapshots = snapshots,
                            targetSetpointTempC = targetTemp,
                            priorModulationAt10C = priorMod,
                            priorModulationSlopePerK = priorSlope,
                            priorWeight = priorWeight
                        )
                        withContext(Dispatchers.Main) {
                            if (profile != null) {
                                lastCalibratedProfile = profile
                                val baseStr = String.format(java.util.Locale.US, "%.1f", profile.modulationAt10C)
                                val slopeStr = String.format(java.util.Locale.US, "%.2f", profile.modulationSlopePerK)
                                val powerStr = String.format(java.util.Locale.US, "%.2f", profile.inferredSteadyPowerKw)
                                val matchedSp = profile.matchedSetpointTempC ?: targetTemp

                                modStatusText.text = "✓ Inverter Modulation PID Model Calibrated (25k Data Window)"
                                modStatusText.setTextColor(Color.parseColor(palette.accentGreen))

                                modulationDetailsContainer.removeAllViews()

                                // Badge Header
                                val badgeRow = LinearLayout(this@SettingsActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    setPadding(0, dp(4), 0, dp(6))
                                }
                                fun addModBadge(label: String, bgHex: String, strokeHex: String, textHex: String) {
                                    badgeRow.addView(TextView(this@SettingsActivity).apply {
                                        text = label
                                        setTextColor(Color.parseColor(textHex))
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                                        setTypeface(typeface, Typeface.BOLD)
                                        background = roundedBg(bgHex, strokeHex, cornerDp = 6)
                                        setPadding(dp(6), dp(2), dp(6), dp(2))
                                        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                        lp.setMargins(0, 0, dp(6), 0)
                                        layoutParams = lp
                                    })
                                }
                                addModBadge("Target: " + String.format(java.util.Locale.US, "%.1f°C", matchedSp), palette.accentGreenBg, palette.accentGreenStroke, palette.accentGreen)
                                addModBadge("R² = " + String.format(java.util.Locale.US, "%.2f", profile.rSquared), palette.badgeBg, palette.badgeStroke, palette.accentBlue)
                                addModBadge("${profile.detectedRunsCount} Runs (${profile.steadyStateSamples} Steady Samples)", palette.badgeBg, palette.badgeStroke, palette.textSecondary)
                                if (profile.historicalPriorIncorporated) {
                                    addModBadge("25k Anchor Active", palette.accentBlueBg, palette.accentBlueStroke, palette.accentBlue)
                                }
                                modulationDetailsContainer.addView(badgeRow)

                                // 2x2 Metric Chips Grid
                                val gridRow1 = LinearLayout(this@SettingsActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                    lp.setMargins(0, dp(2), 0, dp(4))
                                    layoutParams = lp
                                }
                                gridRow1.addView(createMetricChip("Baseline Mod", "${baseStr}% @ 10°C", "Inverter Frequency").apply {
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                                })
                                gridRow1.addView(createMetricChip("Sensitivity", "+${slopeStr}% / K", "Colder Sensitivity").apply {
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
                                })
                                modulationDetailsContainer.addView(gridRow1)

                                val gridRow2 = LinearLayout(this@SettingsActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                    lp.setMargins(0, 0, 0, dp(4))
                                    layoutParams = lp
                                }
                                gridRow2.addView(createMetricChip("Steady Power", "~${powerStr} kW", "Shaft + 50W Pump").apply {
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                                })
                                gridRow2.addView(createMetricChip("Loop Spread", String.format(java.util.Locale.US, "ΔT = %.1f K", profile.avgSteadyDeltaTC), "Hydraulic Lift").apply {
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
                                })
                                modulationDetailsContainer.addView(gridRow2)

                                // Nuances & Physical Adjustments Audit Box
                                val overshootPct = ((profile.warmupModulationOverheadFactor - 1.0) * 100.0).roundToInt().coerceAtLeast(0)
                                val warmupAvgStr = profile.avgWarmupModulationPct?.let { String.format(java.util.Locale.US, "%.1f%%", it) } ?: "55.0%"
                                val priorModMsg = if (profile.historicalPriorIncorporated) {
                                    "• 25k Historical Anchor: Incorporated cumulative 25,000-point historical baseline (${String.format(java.util.Locale.US, "%.1f%%", settingsStore.historicalModulation25kAt10C)} @ 10°C, weight=${settingsStore.historicalModulation25kWeight}) into new regression to preserve long-term multi-seasonal learning."
                                } else {
                                    "• 25k Data Window: Scanning up to 25,000 points; initializing cumulative 25k historical anchor."
                                }
                                val nuanceItems = listOf(
                                    priorModMsg,
                                    "• PID Startup Transient Isolated: Separated ${profile.warmupSamplesCount} warmup samples (avg $warmupAvgStr during 20m ramp-up) from steady state (+${overshootPct}% PID overshoot).",
                                    "• Observed Outdoor Corridor: Telemetry observed between ${String.format(java.util.Locale.US, "%.1f°C", profile.minObservedOutdoorTempC ?: 0.0)} and ${String.format(java.util.Locale.US, "%.1f°C", profile.maxObservedOutdoorTempC ?: 0.0)}; raw modulation ${String.format(java.util.Locale.US, "%.0f%%", profile.minObservedModulationPct)} to ${String.format(java.util.Locale.US, "%.0f%%", profile.maxObservedModulationPct)}.",
                                    "• Inverter Clamping & Baseline: Enforced 28.0% hardware minimum frequency clamp & 50 W continuous Wilo circulation load."
                                )
                                modulationDetailsContainer.addView(createNuanceBox("Process Nuances & Physical Adjustments", nuanceItems))

                                // Dynamic Projection Table
                                modulationDetailsContainer.addView(createProjectionTable(profile.getOperatingProjections()))

                                modulationDetailsContainer.visibility = View.VISIBLE
                                applyModulationBtn.text = "Apply Model (${baseStr}% @ 10°C, +${slopeStr}%/K)"
                                applyModulationBtn.visibility = View.VISIBLE
                            } else {
                                modStatusText.text = "No active heating runs found matching target setpoint ${String.format(java.util.Locale.US, "%.1f", targetTemp)}°C (≥5 needed). Run heating to collect telemetry."
                                modStatusText.setTextColor(Color.parseColor(palette.accentAmber))
                                modulationDetailsContainer.visibility = View.GONE
                                applyModulationBtn.visibility = View.GONE
                            }
                        }
                    }
                }
            }

            applyModulationBtn.setOnClickListener {
                val p = lastCalibratedProfile ?: return@setOnClickListener
                settingsStore.calibratedModulationAt10C = p.modulationAt10C
                settingsStore.calibratedModulationSlopePerK = p.modulationSlopePerK
                settingsStore.groundTruthModulationEnabled = true
                settingsStore.save25kHistoricalModulation(p.modulationAt10C, p.modulationSlopePerK, p.steadyStateSamples)
                Toast.makeText(this@SettingsActivity, "Ground-truth modulation model updated and recorded in 25k anchor!", Toast.LENGTH_SHORT).show()
                applyModulationBtn.visibility = View.GONE
            }

            modulationCard.addView(analyzeModulationBtn)
            modulationCard.addView(applyModulationBtn)
            addView(modulationCard)

            // Parasitic Electrical Baseline & Submeter Calibration
            val parasiticCard = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 8)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, dp(4), 0, dp(8))
                layoutParams = lp
            }

            val parasiticHeaderRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
            }
            parasiticHeaderRow.addView(TextView(this@SettingsActivity).apply {
                text = "Continuous Parasitic Load & Submeter"
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val parasiticBadge = TextView(this@SettingsActivity).apply {
                val w = settingsStore.parasiticBaselinePowerWatts.roundToInt()
                val kwhDay = String.format(java.util.Locale.US, "%.2f", w * 24.0 / 1000.0)
                text = "$w W (${kwhDay} kWh/d)"
                setTextColor(Color.parseColor(palette.accentBlue))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 8)
            }
            parasiticHeaderRow.addView(parasiticBadge)
            parasiticCard.addView(parasiticHeaderRow)

            val parasiticDesc = TextView(this@SettingsActivity).apply {
                text = "Accounts for secondary underfloor heating pump (~25W) and control boards (~15W) across active and setback hours."
                setTextColor(Color.parseColor(palette.textMuted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, 0, 0, dp(4))
            }
            parasiticCard.addView(parasiticDesc)

            val submeterSwitch = Switch(this@SettingsActivity).apply {
                text = "External Physical Submeter Installed (e.g. Shelly 3EM)"
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                isChecked = settingsStore.externalSubmeterActive
                setPadding(0, dp(2), 0, dp(4))
                setOnCheckedChangeListener { _, isChecked ->
                    settingsStore.externalSubmeterActive = isChecked
                    val st = if (isChecked) "ACTIVE (Using calibrated submeter)" else "OFF (Default rule of thumb)"
                    Toast.makeText(this@SettingsActivity, "Submeter calibration $st", Toast.LENGTH_SHORT).show()
                }
            }
            parasiticCard.addView(submeterSwitch)

            val parasiticSeekBar = SeekBar(this@SettingsActivity).apply {
                max = 20 // 0..20 corresponds to 20W .. 120W (step 5W)
                progress = (((settingsStore.parasiticBaselinePowerWatts - 20.0) / 5.0).toInt()).coerceIn(0, 20)
                setPadding(dp(4), dp(4), dp(4), dp(6))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val w = 20 + progress * 5
                        val kwhDay = String.format(java.util.Locale.US, "%.2f", w * 24.0 / 1000.0)
                        parasiticBadge.text = "$w W (${kwhDay} kWh/d)"
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        val w = 20.0 + (seekBar?.progress ?: 6) * 5.0
                        settingsStore.parasiticBaselinePowerWatts = w
                    }
                })
            }
            parasiticCard.addView(parasiticSeekBar)
            addView(parasiticCard)

            // Weather Diagnostics Button
            val weatherStatusText = TextView(this@SettingsActivity).apply {
                text = "Open-Meteo DWD ICON: Lat ${settingsStore.weatherLatitude}°N, Lon ${settingsStore.weatherLongitude}°E"
                setTextColor(Color.parseColor(palette.textMuted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(0, dp(4), 0, dp(4))
            }
            addView(weatherStatusText)

            val testWeatherBtn = createStyledButton(
                label = "Test Weather Forecast Feed",
                bgHex = palette.secondaryBtnBg,
                strokeHex = palette.secondaryBtnStroke,
                textHex = palette.accentBlue
            ).apply {
                setOnClickListener {
                    weatherStatusText.text = "Fetching 48h DWD ICON forecast..."
                    weatherStatusText.setTextColor(Color.parseColor(palette.accentAmber))
                    lifecycleScope.launch {
                        val forecast = runCatching {
                            weatherClient.fetchHourlyForecast(LocalDate.now(), 2)
                        }.getOrDefault(emptyList())

                        withContext(Dispatchers.Main) {
                            if (forecast.isNotEmpty()) {
                                val minT = forecast.minOf { it.outdoorTempC }
                                val maxT = forecast.maxOf { it.outdoorTempC }
                                val peakSolar = forecast.maxOf { it.totalRadiationWm2 }.toInt()
                                weatherStatusText.text = "✓ Weather OK: ${forecast.size}h forecast (Min: ${minT}°C, Max: ${maxT}°C, Peak Solar: ${peakSolar} W/m²)"
                                weatherStatusText.setTextColor(Color.parseColor(palette.accentGreen))
                            } else {
                                weatherStatusText.text = "✗ Weather offline: using synthetic diurnal curve fallback"
                                weatherStatusText.setTextColor(Color.parseColor(palette.accentAmber))
                            }
                        }
                    }
                }
            }
            addView(testWeatherBtn)

            // Hourly Background Sync
            val bgSyncSwitch = Switch(this@SettingsActivity).apply {
                text = "Automatic Hourly Background Sync"
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                isChecked = settingsStore.autoBackgroundEnabled
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(12)
                }
                layoutParams = lp
                setOnCheckedChangeListener { _, isChecked ->
                    settingsStore.autoBackgroundEnabled = isChecked
                    if (isChecked) {
                        SpotScheduleJobService.scheduleHourlyBackgroundSync(this@SettingsActivity)
                    } else {
                        SpotScheduleJobService.cancelBackgroundSync(this@SettingsActivity)
                    }
                    BreastNotificationHelper.updateBackgroundActiveNotification(this@SettingsActivity)
                }
            }
            addView(bgSyncSwitch)

            val batteryBtn = createStyledButton(
                label = "Request Unrestricted Battery (Background Sync)",
                bgHex = palette.accentAmberBg,
                strokeHex = palette.accentAmberStroke,
                textHex = palette.accentAmber
            ).apply {
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                }
                layoutParams = lp
                setOnClickListener { requestIgnoreBatteryOptimizations() }
            }
            addView(batteryBtn)
        }
        root.addView(holisticCard)

        scroll.addView(root)
        refreshSourceCascadeUi()
        return scroll
    }

    private fun buildSourceItemView(source: SpotPriceSource): View {
        val itemContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(palette.pageBg, palette.cardStroke, cornerDp = 10)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            layoutParams = lp
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@SettingsActivity).apply {
                text = source.displayName
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = source.resolutionDescription
                setTextColor(Color.parseColor(palette.textSecondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(0, dp(1), 0, 0)
            })
        }
        topRow.addView(titleCol)

        // Switch to enable/disable source
        val toggle = Switch(this).apply {
            isChecked = source.id in settingsStore.enabledSpotSources
            setOnCheckedChangeListener { _, isChecked ->
                val current = settingsStore.enabledSpotSources.toMutableSet()
                if (isChecked) {
                    current.add(source.id)
                } else {
                    if (current.size <= 1) {
                        Toast.makeText(this@SettingsActivity, "At least one spot price source must remain enabled.", Toast.LENGTH_SHORT).show()
                        this.isChecked = true
                        return@setOnCheckedChangeListener
                    }
                    current.remove(source.id)
                    // If disabled source was primary, assign another enabled source as primary
                    if (settingsStore.primarySpotSource == source.id) {
                        settingsStore.primarySpotSource = current.first()
                    }
                }
                settingsStore.enabledSpotSources = current
                refreshSourceCascadeUi()
            }
        }
        sourceSwitches[source] = toggle
        topRow.addView(toggle)
        itemContainer.addView(topRow)

        // Bottom row: Primary Badge / Button + Live diagnostic status badge
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        val primaryBtn = createStyledButton(
            label = if (settingsStore.primarySpotSource == source.id) "★ Primary" else "Set Primary",
            bgHex = if (settingsStore.primarySpotSource == source.id) palette.accentBlueBg else palette.badgeBg,
            strokeHex = if (settingsStore.primarySpotSource == source.id) palette.accentBlueStroke else palette.badgeStroke,
            textHex = if (settingsStore.primarySpotSource == source.id) palette.accentBlue else palette.textSecondary
        ).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                // Ensure enabled first
                val current = settingsStore.enabledSpotSources.toMutableSet()
                current.add(source.id)
                settingsStore.enabledSpotSources = current
                sourceSwitches[source]?.isChecked = true

                settingsStore.primarySpotSource = source.id
                refreshSourceCascadeUi()
                Toast.makeText(this@SettingsActivity, "${source.displayName} is now your primary source.", Toast.LENGTH_SHORT).show()
            }
        }
        sourcePrimaryButtons[source] = primaryBtn
        bottomRow.addView(primaryBtn)

        val statusBadge = TextView(this).apply {
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(8) }
            layoutParams = lp
            text = "Ready"
            setTextColor(Color.parseColor(palette.textMuted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 6)
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        sourceStatusViews[source] = statusBadge
        bottomRow.addView(statusBadge)

        itemContainer.addView(bottomRow)
        return itemContainer
    }

    private fun refreshSourceCascadeUi() {
        val ordered = settingsStore.getOrderedSpotSources()
        val cascadeStr = StringBuilder("Priority Cascade:\n")
        ordered.forEachIndexed { index, src ->
            val role = if (index == 0) "Primary" else "Fallback $index"
            cascadeStr.append("${index + 1}. ${src.displayName} ($role)")
            if (index < ordered.size - 1) {
                cascadeStr.append("\n  ↳ If delayed/offline: ")
            }
        }
        cascadeOrderText.text = cascadeStr.toString()

        for (source in SpotPriceSource.ALL) {
            val isPrimary = settingsStore.primarySpotSource == source.id
            val isEnabled = source.id in settingsStore.enabledSpotSources

            sourcePrimaryButtons[source]?.apply {
                text = if (isPrimary) "★ Primary" else "Set Primary"
                background = roundedBg(
                    if (isPrimary) palette.accentBlueBg else palette.badgeBg,
                    if (isPrimary) palette.accentBlueStroke else palette.badgeStroke,
                    cornerDp = 8
                )
                setTextColor(Color.parseColor(if (isPrimary) palette.accentBlue else palette.textSecondary))
            }
            sourceSwitches[source]?.isChecked = isEnabled
        }
    }

    private fun runPriceSourceDiagnostics() {
        sourceStatusViews.values.forEach {
            it.text = "Checking..."
            it.setTextColor(Color.parseColor(palette.accentAmber))
        }

        lifecycleScope.launch {
            val results = spotClient.testPriceSources()
            withContext(Dispatchers.Main) {
                for ((src, res) in results) {
                    val view = sourceStatusViews[src] ?: continue
                    if (res.success) {
                        view.text = "✓ Online (${res.slotCount} slots, ${res.latencyMs}ms)"
                        view.setTextColor(Color.parseColor(palette.accentGreen))
                    } else {
                        view.text = "✗ ${res.message}"
                        view.setTextColor(Color.parseColor(palette.accentAmber))
                    }
                }
            }
        }
    }

    private fun exportTelemetry() {
        lifecycleScope.launch {
            val csv = telemetryStore.exportAsCsv()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "breast_heatpump_telemetry.csv")
                putExtra(Intent.EXTRA_TEXT, csv)
            }
            startActivity(Intent.createChooser(intent, "Export Telemetry CSV"))
        }
    }

    private fun confirmClearDatabase() {
        AlertDialog.Builder(this)
            .setTitle("Clear Telemetry Database?")
            .setMessage("This will permanently delete all recorded temperature snapshots and learned steady-state data points.")
            .setPositiveButton("Clear All") { _, _ ->
                lifecycleScope.launch {
                    telemetryStore.clearAll()
                    Toast.makeText(this@SettingsActivity, "Database cleared.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Unrestricted battery already granted.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
