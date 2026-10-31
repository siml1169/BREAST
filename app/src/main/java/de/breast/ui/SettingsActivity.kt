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
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import de.breast.auth.UserSettingsStore
import de.breast.daemon.BreastNotificationHelper
import de.breast.daemon.SpotScheduleJobService
import de.breast.optimizer.OptimizationPreference
import de.breast.spot.ElectricityTariffConfig
import de.breast.spot.SpotPriceClient
import de.breast.spot.SpotPriceSource
import de.breast.spot.TariffPricingModel
import de.breast.spot.TimeOfUseTimetable
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
        val badgeStroke: String,
        val inputBg: String,
        val inputStroke: String
    )

    private lateinit var palette: UiPalette
    private lateinit var settingsStore: UserSettingsStore
    private lateinit var telemetryStore: SqliteTelemetryStore
    private val httpClient = OkHttpClient()
    private val spotClient get() = SpotPriceClient(httpClient, settingsStore.buildTariffConfig())
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
        requestWindowFeature(Window.FEATURE_NO_TITLE)
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
                badgeStroke = "#334155",
                inputBg = "#0F172A",
                inputStroke = "#334155"
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
                badgeStroke = "#CBD5E1",
                inputBg = "#F8FAFC",
                inputStroke = "#CBD5E1"
            )
        }
    }

    private fun UiPalette.toAnalysisPalette(): AnalysisPalette {
        return AnalysisPalette(
            accentGreen = accentGreen,
            accentGreenBg = accentGreenBg,
            accentGreenStroke = accentGreenStroke,
            accentBlue = accentBlue,
            accentBlueBg = accentBlueBg,
            accentBlueStroke = accentBlueStroke,
            badgeBg = badgeBg,
            badgeStroke = badgeStroke,
            textSecondary = textSecondary
        )
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
            setPadding(dp(18), dp(18), dp(18), dp(22))
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
            stateListAnimator = null
            elevation = 0f
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

            val i18n = BreastI18n.get(settingsStore.appLanguage)
            addView(TextView(this@SettingsActivity).apply {
                text = i18n.projectionTableTitle
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
                        text = BreastI18n.localizeScenarioLabel(proj.scenarioLabel, settingsStore.appLanguage)
                        setTextColor(Color.parseColor(palette.textPrimary))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f)
                    })

                    val modStr = String.format(java.util.Locale.US, "%.1f%%", proj.predictedModulationPct) +
                        if (proj.isHardwareClamped) " ${i18n.projectionFloorBadge}" else ""
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
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            setBackgroundColor(Color.parseColor(palette.pageBg))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Apply window insets for system bars (status bar, display cutout, nav bar) and soft keyboard (IME)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
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

        val i18n = BreastI18n.get(settingsStore.appLanguage)

        val backBtn = createStyledButton(
            label = i18n.done,
            bgHex = palette.secondaryBtnBg,
            strokeHex = palette.secondaryBtnStroke,
            textHex = palette.accentBlue
        ).apply {
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn)

        val titleView = TextView(this).apply {
            text = i18n.settingsTitle
            setTextColor(Color.parseColor(palette.textPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), 0, 0, 0)
        }
        topBar.addView(titleView)
        root.addView(topBar)

        // =========================================================================
        // 0. LANGUAGE SELECTION CARD
        // =========================================================================
        val langCard = createCard().apply {
            addView(cardSectionTitle(i18n.languageSectionTitle))
            addView(cardSubtitle(i18n.languageSectionSubtitle))

            val langBtnRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
                layoutParams = lp
            }

            fun selectLanguage(lang: de.breast.auth.AppLanguage) {
                if (settingsStore.appLanguage != lang) {
                    settingsStore.appLanguage = lang
                    setContentView(buildContentView())
                }
            }

            val curLang = settingsStore.appLanguage

            val enBtn = createStyledButton(
                label = "English",
                bgHex = if (curLang == de.breast.auth.AppLanguage.EN) palette.accentBlueBg else palette.secondaryBtnBg,
                strokeHex = if (curLang == de.breast.auth.AppLanguage.EN) palette.accentBlueStroke else palette.secondaryBtnStroke,
                textHex = if (curLang == de.breast.auth.AppLanguage.EN) palette.accentBlue else palette.secondaryBtnText
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(4)
                }
                setOnClickListener { selectLanguage(de.breast.auth.AppLanguage.EN) }
            }
            langBtnRow.addView(enBtn)

            val deBtn = createStyledButton(
                label = "Deutsch",
                bgHex = if (curLang == de.breast.auth.AppLanguage.DE) palette.accentBlueBg else palette.secondaryBtnBg,
                strokeHex = if (curLang == de.breast.auth.AppLanguage.DE) palette.accentBlueStroke else palette.secondaryBtnStroke,
                textHex = if (curLang == de.breast.auth.AppLanguage.DE) palette.accentBlue else palette.secondaryBtnText
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(2)
                    rightMargin = dp(2)
                }
                setOnClickListener { selectLanguage(de.breast.auth.AppLanguage.DE) }
            }
            langBtnRow.addView(deBtn)

            val zhBtn = createStyledButton(
                label = "中文",
                bgHex = if (curLang == de.breast.auth.AppLanguage.ZH) palette.accentBlueBg else palette.secondaryBtnBg,
                strokeHex = if (curLang == de.breast.auth.AppLanguage.ZH) palette.accentBlueStroke else palette.secondaryBtnStroke,
                textHex = if (curLang == de.breast.auth.AppLanguage.ZH) palette.accentBlue else palette.secondaryBtnText
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(4)
                }
                setOnClickListener { selectLanguage(de.breast.auth.AppLanguage.ZH) }
            }
            langBtnRow.addView(zhBtn)

            addView(langBtnRow)
        }
        root.addView(langCard)

        // =========================================================================
        // 1. ELECTRICITY TARIFF COMPOSITOR & PRICING MODELS
        // =========================================================================
        val tariffCard = buildTariffCompositorCard()
        root.addView(tariffCard)

        // =========================================================================
        // 2. SPOT MARKET DATA SOURCES (MULTIPLE SOURCES + PRIMARY WITH FALLBACK)
        // =========================================================================
        val sourcesCard = createCard().apply {
            addView(cardSectionTitle(i18n.sourcesSectionTitle))
            addView(cardSubtitle(i18n.sourcesSectionSubtitle))

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
                label = i18n.testSourcesBtn,
                bgHex = palette.secondaryBtnBg,
                strokeHex = palette.secondaryBtnStroke,
                textHex = palette.accentBlue
            ).apply {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(6)
                }
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
            addView(cardSectionTitle(i18n.telemetryRecSectionTitle))
            addView(cardSubtitle(i18n.telemetryRecSectionSubtitle))

            val recSwitch = Switch(this@SettingsActivity).apply {
                text = i18n.recordTelemetrySwitch
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
                text = i18n.samplingFreqLabel
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val freqBadge = TextView(this@SettingsActivity).apply {
                val m = settingsStore.telemetryIntervalMinutes
                text = if (m == 1) i18n.freqEveryMinute else if (m == 60) i18n.freqEveryHour else String.format(i18n.freqEveryMinutes, m)
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
                        freqBadge.text = if (minutes == 1) i18n.freqEveryMinute else if (minutes == 60) i18n.freqEveryHour else String.format(i18n.freqEveryMinutes, minutes)
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
                ).apply {
                    topMargin = dp(10)
                    bottomMargin = dp(6)
                }
                layoutParams = lp
            }

            val exportBtn = createStyledButton(
                label = i18n.exportCsvBtn,
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
                label = i18n.clearDbBtn,
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
            addView(cardSectionTitle(i18n.devCardTitle))
            addView(cardSubtitle(i18n.devCardSubtitle))

            val debugSwitch = Switch(this@SettingsActivity).apply {
                text = i18n.debugModeSwitch
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
                text = i18n.debugModeDesc
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
            addView(cardSectionTitle(i18n.holisticSectionTitle))
            addView(cardSubtitle(i18n.holisticSectionSubtitle))

            val holisticSwitch = Switch(this@SettingsActivity).apply {
                text = i18n.holisticSwitchLabel
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
                text = i18n.holisticDesc
                setTextColor(Color.parseColor(palette.textMuted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(0, 0, 0, dp(8))
            }
            addView(holisticDesc)

            // Optimization Preference Segmented Control (Comfort, Budget, Efficiency, Balanced)
            addView(TextView(this@SettingsActivity).apply {
                text = i18n.optPreferenceLabel
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(2))
            })

            fun getPrefSubtitle(pref: OptimizationPreference): String {
                return when (pref) {
                    OptimizationPreference.BALANCED -> i18n.prefBalancedSub
                    OptimizationPreference.COMFORT -> i18n.prefComfortSub
                    OptimizationPreference.BUDGET -> i18n.prefBudgetSub
                    OptimizationPreference.EFFICIENCY -> i18n.prefEfficiencySub
                }
            }

            var currentPref = OptimizationPreference.fromId(settingsStore.optimizationPreference)
            val prefSubtitle = TextView(this@SettingsActivity).apply {
                text = getPrefSubtitle(currentPref)
                setTextColor(Color.parseColor(palette.accentAmber))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, 0, 0, dp(6))
            }

            val prefBtnRow = LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                setPadding(0, 0, 0, dp(8))
            }

            val prefButtons = mutableMapOf<OptimizationPreference, Button>()
            for (p in OptimizationPreference.entries) {
                val btn = Button(this@SettingsActivity).apply {
                    text = when (p) {
                        OptimizationPreference.BALANCED -> i18n.modeBalanced
                        OptimizationPreference.COMFORT -> i18n.modeComfort
                        OptimizationPreference.BUDGET -> i18n.modeBudget
                        OptimizationPreference.EFFICIENCY -> i18n.modeEfficiency
                    }
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    isAllCaps = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    stateListAnimator = null
                    elevation = 0f
                    minHeight = 0
                    minimumHeight = 0
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
                prefSubtitle.text = getPrefSubtitle(selected)
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
                    btn.setPadding(dp(2), dp(8), dp(2), dp(8))
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
                text = i18n.screedBufferLabel
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
                text = i18n.heatLossLabel
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
                text = i18n.setbackStatusDefault
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
                label = String.format(i18n.applySetbackBtnFormat, 150),
                bgHex = palette.accentGreenBg,
                strokeHex = palette.accentGreenStroke,
                textHex = palette.accentGreen
            ).apply {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
                layoutParams = lp
                visibility = View.GONE
            }

            var lastMeasuredKw: Double? = null
            var lastMeasuredCoolingRate: Double? = null

            val analyzeSetbackBtn = createStyledButton(
                label = i18n.analyzeSetbackBtn,
                bgHex = palette.secondaryBtnBg,
                strokeHex = palette.secondaryBtnStroke,
                textHex = palette.accentBlue
            ).apply {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
                layoutParams = lp
                setOnClickListener {
                    setbackStatusText.text = i18n.scanningSetbackMsg
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
                            val uiModel = SettingsAnalysisPresenter.buildSetbackUiModel(
                                result = result,
                                historicalPriorLoss = settingsStore.historicalHeatLoss25kWPerK,
                                historicalWeight = settingsStore.historicalHeatLoss25kWeight,
                                i18n = i18n,
                                palette = palette.toAnalysisPalette()
                            )
                            if (uiModel != null) {
                                lastMeasuredKw = uiModel.chosenLoss / 1000.0
                                lastMeasuredCoolingRate = result?.coolingRateKPerHour

                                setbackStatusText.text = uiModel.statusText
                                setbackStatusText.setTextColor(Color.parseColor(palette.accentGreen))

                                setbackDetailsContainer.removeAllViews()

                                // Badge Header with horizontal scrolling to prevent squishing/wrapping
                                val badgeScroll = HorizontalScrollView(this@SettingsActivity).apply {
                                    isHorizontalScrollBarEnabled = false
                                    val lp = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    ).apply {
                                        topMargin = dp(4)
                                        bottomMargin = dp(6)
                                    }
                                    layoutParams = lp
                                }

                                val badgeRow = LinearLayout(this@SettingsActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER_VERTICAL
                                }
                                badgeScroll.addView(badgeRow)

                                for (b in uiModel.badges) {
                                    badgeRow.addView(TextView(this@SettingsActivity).apply {
                                        text = b.label
                                        isSingleLine = true
                                        setTextColor(Color.parseColor(b.textHex))
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                                        setTypeface(typeface, Typeface.BOLD)
                                        background = roundedBg(b.bgHex, b.strokeHex, cornerDp = 6)
                                        setPadding(dp(7), dp(3), dp(7), dp(3))
                                        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                        lp.setMargins(0, 0, dp(6), 0)
                                        layoutParams = lp
                                    })
                                }
                                setbackDetailsContainer.addView(badgeScroll)

                                // 2x2 Metric Chips Grid
                                val gridRow1 = LinearLayout(this@SettingsActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                    lp.setMargins(0, dp(2), 0, dp(4))
                                    layoutParams = lp
                                }
                                gridRow1.addView(createMetricChip(uiModel.chip1.title, uiModel.chip1.value, uiModel.chip1.subtitle).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                                })
                                gridRow1.addView(createMetricChip(uiModel.chip2.title, uiModel.chip2.value, uiModel.chip2.subtitle).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
                                })
                                setbackDetailsContainer.addView(gridRow1)

                                val gridRow2 = LinearLayout(this@SettingsActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                    lp.setMargins(0, 0, 0, dp(4))
                                    layoutParams = lp
                                }
                                gridRow2.addView(createMetricChip(uiModel.chip3.title, uiModel.chip3.value, uiModel.chip3.subtitle).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                                })
                                gridRow2.addView(createMetricChip(uiModel.chip4.title, uiModel.chip4.value, uiModel.chip4.subtitle).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
                                })
                                setbackDetailsContainer.addView(gridRow2)

                                // Nuances & Physical Audit Trail Box
                                setbackDetailsContainer.addView(createNuanceBox(uiModel.nuanceTitle, uiModel.nuanceItems))
                                setbackDetailsContainer.visibility = View.VISIBLE

                                applySetbackBtn.text = uiModel.applyButtonText
                                applySetbackBtn.visibility = View.VISIBLE
                            } else {
                                setbackStatusText.text = i18n.noSetbackCooldownMsg
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
                val cr = lastMeasuredCoolingRate
                if (cr != null && cr > 0.0) {
                    settingsStore.learnedCoolingRateKPerHour = cr
                }
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
                text = String.format(i18n.modStatusDefault, currentBase, currentSlope)
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
                label = String.format(i18n.applyModulationBtnFormat, "32.0", "1.5"),
                bgHex = palette.accentGreenBg,
                strokeHex = palette.accentGreenStroke,
                textHex = palette.accentGreen
            ).apply {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
                layoutParams = lp
                visibility = View.GONE
            }

            var lastCalibratedProfile: de.breast.telemetry.EmpiricalModulationProfile? = null

            val analyzeModulationBtn = createStyledButton(
                label = i18n.analyzeModulationBtn,
                bgHex = palette.secondaryBtnBg,
                strokeHex = palette.secondaryBtnStroke,
                textHex = palette.accentBlue
            ).apply {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
                layoutParams = lp
                setOnClickListener {
                    modStatusText.text = i18n.scanningModulationMsg
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
                            val uiModel = SettingsAnalysisPresenter.buildModulationUiModel(
                                profile = profile,
                                targetTemp = targetTemp,
                                historicalModAt10C = settingsStore.historicalModulation25kAt10C,
                                historicalWeight = settingsStore.historicalModulation25kWeight,
                                i18n = i18n,
                                palette = palette.toAnalysisPalette()
                            )
                            if (uiModel != null) {
                                lastCalibratedProfile = profile

                                modStatusText.text = uiModel.statusText
                                modStatusText.setTextColor(Color.parseColor(palette.accentGreen))

                                modulationDetailsContainer.removeAllViews()

                                // Badge Header with horizontal scrolling to prevent squishing/wrapping
                                val badgeScroll = HorizontalScrollView(this@SettingsActivity).apply {
                                    isHorizontalScrollBarEnabled = false
                                    val lp = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    ).apply {
                                        topMargin = dp(4)
                                        bottomMargin = dp(6)
                                    }
                                    layoutParams = lp
                                }

                                val badgeRow = LinearLayout(this@SettingsActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER_VERTICAL
                                }
                                badgeScroll.addView(badgeRow)

                                for (b in uiModel.badges) {
                                    badgeRow.addView(TextView(this@SettingsActivity).apply {
                                        text = b.label
                                        isSingleLine = true
                                        setTextColor(Color.parseColor(b.textHex))
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                                        setTypeface(typeface, Typeface.BOLD)
                                        background = roundedBg(b.bgHex, b.strokeHex, cornerDp = 6)
                                        setPadding(dp(7), dp(3), dp(7), dp(3))
                                        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                        lp.setMargins(0, 0, dp(6), 0)
                                        layoutParams = lp
                                    })
                                }
                                modulationDetailsContainer.addView(badgeScroll)

                                // 2x2 Metric Chips Grid
                                val gridRow1 = LinearLayout(this@SettingsActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                    lp.setMargins(0, dp(2), 0, dp(4))
                                    layoutParams = lp
                                }
                                gridRow1.addView(createMetricChip(uiModel.chip1.title, uiModel.chip1.value, uiModel.chip1.subtitle).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                                })
                                gridRow1.addView(createMetricChip(uiModel.chip2.title, uiModel.chip2.value, uiModel.chip2.subtitle).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
                                })
                                modulationDetailsContainer.addView(gridRow1)

                                val gridRow2 = LinearLayout(this@SettingsActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                    lp.setMargins(0, 0, 0, dp(4))
                                    layoutParams = lp
                                }
                                gridRow2.addView(createMetricChip(uiModel.chip3.title, uiModel.chip3.value, uiModel.chip3.subtitle).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                                })
                                gridRow2.addView(createMetricChip(uiModel.chip4.title, uiModel.chip4.value, uiModel.chip4.subtitle).apply {
                                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
                                })
                                modulationDetailsContainer.addView(gridRow2)

                                // Nuances & Physical Adjustments Audit Box
                                modulationDetailsContainer.addView(createNuanceBox(uiModel.nuanceTitle, uiModel.nuanceItems))

                                // Dynamic Projection Table
                                modulationDetailsContainer.addView(createProjectionTable(uiModel.projections))

                                modulationDetailsContainer.visibility = View.VISIBLE
                                applyModulationBtn.text = uiModel.applyButtonText
                                applyModulationBtn.visibility = View.VISIBLE
                            } else {
                                modStatusText.text = String.format(i18n.noHeatingRunsMsg, String.format(java.util.Locale.US, "%.1f", targetTemp))
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
                text = i18n.parasiticSectionTitle
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val parasiticBadge = TextView(this@SettingsActivity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), dp(2), dp(8), dp(2))
            }
            parasiticHeaderRow.addView(parasiticBadge)
            parasiticCard.addView(parasiticHeaderRow)

            val parasiticDesc = TextView(this@SettingsActivity).apply {
                text = i18n.parasiticDesc
                setTextColor(Color.parseColor(palette.textMuted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, 0, 0, dp(4))
            }
            parasiticCard.addView(parasiticDesc)

            val submeterSwitch = Switch(this@SettingsActivity).apply {
                text = i18n.externalSubmeterLabel
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                isChecked = settingsStore.externalSubmeterActive
                setPadding(0, dp(2), 0, dp(2))
            }
            parasiticCard.addView(submeterSwitch)

            val submeterHintView = TextView(this@SettingsActivity).apply {
                text = i18n.submeterHint
                setTextColor(Color.parseColor(palette.accentAmber))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setPadding(0, 0, 0, dp(6))
            }
            parasiticCard.addView(submeterHintView)

            val parasiticSeekBar = SeekBar(this@SettingsActivity).apply {
                max = 20 // 0..20 corresponds to 20W .. 120W (step 5W)
                progress = (((settingsStore.parasiticBaselinePowerWatts - 20.0) / 5.0).toInt()).coerceIn(0, 20)
                setPadding(dp(4), dp(4), dp(4), dp(6))
            }

            fun refreshParasiticUi(isActive: Boolean, watts: Int) {
                if (isActive) {
                    val kwhDay = watts * 24.0 / 1000.0
                    parasiticBadge.text = String.format(java.util.Locale.US, i18n.submeterCalibratedBadgeFormat, watts, kwhDay)
                    parasiticBadge.setTextColor(Color.parseColor(palette.accentBlue))
                    parasiticBadge.background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 8)
                    parasiticSeekBar.alpha = 1.0f
                    parasiticSeekBar.isEnabled = true
                } else {
                    parasiticBadge.text = i18n.submeterDefaultBadge
                    parasiticBadge.setTextColor(Color.parseColor(palette.textMuted))
                    parasiticBadge.background = roundedBg(palette.cardBg, palette.cardStroke, cornerDp = 8)
                    parasiticSeekBar.alpha = 0.45f
                    parasiticSeekBar.isEnabled = false
                }
            }

            submeterSwitch.setOnCheckedChangeListener { _, isChecked ->
                settingsStore.externalSubmeterActive = isChecked
                val w = 20 + parasiticSeekBar.progress * 5
                refreshParasiticUi(isChecked, w)
            }

            parasiticSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val w = 20 + progress * 5
                    refreshParasiticUi(submeterSwitch.isChecked, w)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val w = 20.0 + (seekBar?.progress ?: 6) * 5.0
                    settingsStore.parasiticBaselinePowerWatts = w
                }
            })

            refreshParasiticUi(settingsStore.externalSubmeterActive, settingsStore.parasiticBaselinePowerWatts.roundToInt())
            parasiticCard.addView(parasiticSeekBar)
            addView(parasiticCard)

            // Weather Diagnostics Button
            val weatherStatusText = TextView(this@SettingsActivity).apply {
                text = String.format(java.util.Locale.US, i18n.weatherLocationFormat, settingsStore.weatherLatitude, settingsStore.weatherLongitude)
                setTextColor(Color.parseColor(palette.textMuted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(0, dp(4), 0, dp(4))
            }
            addView(weatherStatusText)

            val testWeatherBtn = createStyledButton(
                label = i18n.testWeatherBtn,
                bgHex = palette.secondaryBtnBg,
                strokeHex = palette.secondaryBtnStroke,
                textHex = palette.accentBlue
            ).apply {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
                layoutParams = lp
                setOnClickListener {
                    weatherStatusText.text = i18n.weatherFetchingMsg
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
                                weatherStatusText.text = String.format(java.util.Locale.US, i18n.weatherSuccessFormat, forecast.size, minT, maxT, peakSolar)
                                weatherStatusText.setTextColor(Color.parseColor(palette.accentGreen))
                            } else {
                                weatherStatusText.text = i18n.weatherOfflineMsg
                                weatherStatusText.setTextColor(Color.parseColor(palette.accentAmber))
                            }
                        }
                    }
                }
            }
            addView(testWeatherBtn)

            // Hourly Background Sync
            val bgSyncSwitch = Switch(this@SettingsActivity).apply {
                text = i18n.autoSwitchLabel
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
                label = i18n.reqBatteryBtn,
                bgHex = palette.accentAmberBg,
                strokeHex = palette.accentAmberStroke,
                textHex = palette.accentAmber
            ).apply {
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(6)
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
        val i18n = BreastI18n.get(settingsStore.appLanguage)
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
            label = if (settingsStore.primarySpotSource == source.id) i18n.primaryLabel else i18n.setPrimaryLabel,
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
            text = i18n.cascadeStatusReady
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
        if (!::cascadeOrderText.isInitialized) return
        val i18n = BreastI18n.get(settingsStore.appLanguage)
        val mode = TariffPricingModel.fromId(settingsStore.tariffPricingModel)
        if (mode != TariffPricingModel.DYNAMIC_SPOT) {
            cascadeOrderText.text = String.format(java.util.Locale.US, i18n.cascadeActivePlanFormat, BreastI18n.localizeScenarioLabel(mode.displayName, settingsStore.appLanguage))
        } else {
            val ordered = settingsStore.getOrderedSpotSources()
            val cascadeStr = StringBuilder(i18n.cascadePriorityTitle)
            ordered.forEachIndexed { index, src ->
                val role = if (index == 0) i18n.cascadePrimaryRole else String.format(java.util.Locale.US, i18n.cascadeFallbackRoleFormat, index)
                cascadeStr.append("${index + 1}. ${src.displayName} ($role)")
                if (index < ordered.size - 1) {
                    cascadeStr.append(i18n.cascadeDelayedLine)
                }
            }
            cascadeOrderText.text = cascadeStr.toString()
        }

        for (source in SpotPriceSource.ALL) {
            val isPrimary = settingsStore.primarySpotSource == source.id
            val isEnabled = source.id in settingsStore.enabledSpotSources

            sourcePrimaryButtons[source]?.apply {
                text = if (isPrimary) i18n.primaryLabel else i18n.setPrimaryLabel
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
        val i18n = BreastI18n.get(settingsStore.appLanguage)
        sourceStatusViews.values.forEach {
            it.text = i18n.cascadeStatusChecking
            it.setTextColor(Color.parseColor(palette.accentAmber))
        }

        lifecycleScope.launch {
            val results = spotClient.testPriceSources()
            withContext(Dispatchers.Main) {
                for ((src, res) in results) {
                    val view = sourceStatusViews[src] ?: continue
                    if (res.success) {
                        view.text = String.format(java.util.Locale.US, i18n.cascadeStatusOnlineFormat, res.slotCount, res.latencyMs)
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
        val i18n = BreastI18n.get(settingsStore.appLanguage)
        AlertDialog.Builder(this)
            .setTitle(i18n.clearDbTitle)
            .setMessage(i18n.clearDbMessage)
            .setPositiveButton(i18n.clearAllBtn) { _, _ ->
                val res = SettingsAnalysisPresenter.executeSafeClearDatabase(
                    isDevMode = settingsStore.debugModeEnabled,
                    originalDbFile = getDatabasePath(de.breast.telemetry.SqliteTelemetryStore.DB_NAME),
                    cacheDir = cacheDir,
                    onClearOriginalDb = { lifecycleScope.launch { telemetryStore.clearAll() } }
                )
                Toast.makeText(this@SettingsActivity, res.message, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(i18n.cancel, null)
            .show()
    }

    private fun formatPriceValue(value: Double, maxDecimals: Int = 4): String {
        return if (value == value.toLong().toDouble()) {
            String.format(java.util.Locale.US, "%.1f", value)
        } else {
            val s = String.format(java.util.Locale.US, "%.${maxDecimals}f", value)
            s.trimEnd('0').let { if (it.endsWith('.')) "${it}0" else it }
        }
    }

    private data class PriceInputField(
        val root: LinearLayout,
        val editText: EditText,
        val badgeView: TextView?
    )

    private fun createPriceInputField(
        label: String,
        sublabel: String? = null,
        initialValue: String,
        unitSuffix: String,
        hintText: String = "0.0",
        showBadgePreview: Boolean = false,
        onValueChanged: (Double?) -> Unit
    ): PriceInputField {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(4)
            }
            layoutParams = lp
        }

        val labelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }

        val titleView = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(palette.textPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        labelRow.addView(titleView)

        var badgeView: TextView? = null
        if (showBadgePreview) {
            badgeView = TextView(this).apply {
                setTextColor(Color.parseColor(palette.accentBlue))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
            }
            labelRow.addView(badgeView)
        }
        container.addView(labelRow)

        if (!sublabel.isNullOrBlank()) {
            val subView = TextView(this).apply {
                text = sublabel
                setTextColor(Color.parseColor(palette.textMuted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(0, 0, 0, dp(4))
            }
            container.addView(subView)
        }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(2)
            }
            layoutParams = lp
        }

        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setText(initialValue)
            hint = hintText
            setTextColor(Color.parseColor(palette.textPrimary))
            setHintTextColor(Color.parseColor(palette.textMuted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(palette.inputBg, palette.inputStroke, cornerDp = 8)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp

            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(v.windowToken, 0)
                    v.clearFocus()
                    true
                } else {
                    false
                }
            }

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val raw = s?.toString()?.trim() ?: ""
                    val parsed = raw.replace(',', '.').toDoubleOrNull()
                    onValueChanged(parsed)
                }
            })
        }
        inputRow.addView(editText)

        val suffixBadge = TextView(this).apply {
            text = unitSuffix
            setTextColor(Color.parseColor(palette.accentBlue))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 8)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(8)
            }
            layoutParams = lp
        }
        inputRow.addView(suffixBadge)

        container.addView(inputRow)
        return PriceInputField(container, editText, badgeView)
    }

    private fun buildTariffCompositorCard(): LinearLayout {
        val i18n = BreastI18n.get(settingsStore.appLanguage)
        val card = createCard()
        card.addView(cardSectionTitle(i18n.tariffSectionTitle))
        card.addView(cardSubtitle(i18n.tariffSectionSubtitle))

        var activeModel = TariffPricingModel.fromId(settingsStore.tariffPricingModel)

        // 1. Segmented Pricing Model Selector
        val modelSelectorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }

        val modelButtons = mutableMapOf<TariffPricingModel, Button>()
        for (m in TariffPricingModel.entries) {
            val btn = Button(this).apply {
                text = when (m) {
                    TariffPricingModel.DYNAMIC_SPOT -> i18n.dynamicSpotModel
                    TariffPricingModel.TIME_OF_USE -> i18n.touModel
                    TariffPricingModel.FLAT_RATE -> i18n.flatRateModel
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                isAllCaps = false
                setPadding(dp(4), dp(8), dp(4), dp(8))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                layoutParams = lp
            }
            modelButtons[m] = btn
            modelSelectorRow.addView(btn)
        }
        card.addView(modelSelectorRow)

        // Panels for each mode
        val dynamicPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val touPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val flatPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        var updateDynamicFormula: (() -> Unit)? = null
        var updateTouSummary: (() -> Unit)? = null

        fun updateModelUi(selected: TariffPricingModel) {
            activeModel = selected
            settingsStore.tariffPricingModel = selected.id
            for ((m, btn) in modelButtons) {
                if (m == selected) {
                    btn.background = roundedBg(palette.accentBlueBg, palette.accentBlueStroke, cornerDp = 8)
                    btn.setTextColor(Color.parseColor(palette.accentBlue))
                    btn.setTypeface(null, Typeface.BOLD)
                } else {
                    btn.background = roundedBg(palette.secondaryBtnBg, palette.secondaryBtnStroke, cornerDp = 8)
                    btn.setTextColor(Color.parseColor(palette.textPrimary))
                    btn.setTypeface(null, Typeface.NORMAL)
                }
            }
            dynamicPanel.visibility = if (selected == TariffPricingModel.DYNAMIC_SPOT) View.VISIBLE else View.GONE
            touPanel.visibility = if (selected == TariffPricingModel.TIME_OF_USE) View.VISIBLE else View.GONE
            flatPanel.visibility = if (selected == TariffPricingModel.FLAT_RATE) View.VISIBLE else View.GONE

            refreshSourceCascadeUi()
        }

        for ((m, btn) in modelButtons) {
            btn.setOnClickListener { updateModelUi(m) }
        }

        // =========================================================================
        // PANEL A: DYNAMIC SPOT PRICE COMPOSITOR (UTILITY CONTRACT LETTER FORMAT)
        // =========================================================================
        // 1. Base Working Price (Fester Arbeitspreis / Netzentgelte + Steuern/Umlagen)
        lateinit var surchargeField: PriceInputField
        fun updateSurchargeGrossBadge() {
            val net = settingsStore.tariffNetSurchargeCtPerKwh
            val gross = net * (1.0 + settingsStore.tariffVatRatePercent / 100.0)
            surchargeField.badgeView?.text = String.format(java.util.Locale.US, "%.2f ct gross", gross)
        }

        surchargeField = createPriceInputField(
            label = i18n.baseWorkingPriceLabel,
            sublabel = i18n.baseWorkingPriceSub,
            initialValue = formatPriceValue(settingsStore.tariffNetSurchargeCtPerKwh, 4),
            unitSuffix = "ct/kWh net",
            showBadgePreview = true
        ) { parsed ->
            if (parsed != null && parsed >= 0.0) {
                settingsStore.tariffNetSurchargeCtPerKwh = parsed
                updateSurchargeGrossBadge()
                updateDynamicFormula?.invoke()
            }
        }
        updateSurchargeGrossBadge()
        dynamicPanel.addView(surchargeField.root)

        // 2. Monthly Base Fee (Grundpreis)
        val baseFeeField = createPriceInputField(
            label = i18n.monthlyBaseFeeLabel,
            sublabel = i18n.monthlyBaseFeeSub,
            initialValue = formatPriceValue(settingsStore.tariffBaseFeeMonthlyGross, 2),
            unitSuffix = "€ / month"
        ) { parsed ->
            if (parsed != null && parsed >= 0.0) {
                settingsStore.tariffBaseFeeMonthlyGross = parsed
                updateDynamicFormula?.invoke()
            }
        }
        dynamicPanel.addView(baseFeeField.root)

        // 3. Value Added Tax (VAT / MwSt)
        lateinit var vatField: PriceInputField
        vatField = createPriceInputField(
            label = i18n.vatLabel,
            sublabel = i18n.vatSub,
            initialValue = formatPriceValue(settingsStore.tariffVatRatePercent, 2),
            unitSuffix = "%"
        ) { parsed ->
            if (parsed != null && parsed >= 0.0) {
                settingsStore.tariffVatRatePercent = parsed
                updateSurchargeGrossBadge()
                updateDynamicFormula?.invoke()
            }
        }
        dynamicPanel.addView(vatField.root)

        // Preset VAT chips row
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(6))
        }
        val vatPresets = listOf(
            0.0 to "0% Exempt",
            7.0 to "7% Reduced",
            19.0 to "19% DE",
            20.0 to "20% AT"
        )
        for ((rate, label) in vatPresets) {
            val chip = createStyledButton(
                label = label,
                bgHex = palette.secondaryBtnBg,
                strokeHex = palette.secondaryBtnStroke,
                textHex = palette.secondaryBtnText
            ).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                layoutParams = lp
                setOnClickListener {
                    vatField.editText.setText(String.format(java.util.Locale.US, "%.1f", rate))
                    vatField.editText.setSelection(vatField.editText.text.length)
                }
            }
            presetRow.addView(chip)
        }
        dynamicPanel.addView(presetRow)

        // Live Dynamic Formula Box (matching utility contract letter)
        val formulaBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 8)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
            layoutParams = lp
        }
        val formulaTitleText = TextView(this).apply {
            text = i18n.contractFormulaTitle
            setTextColor(Color.parseColor(palette.accentBlue))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        val formulaDetailText = TextView(this).apply {
            setTextColor(Color.parseColor(palette.textSecondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }
        formulaBox.addView(formulaTitleText)
        formulaBox.addView(formulaDetailText)
        dynamicPanel.addView(formulaBox)

        updateDynamicFormula = {
            val cfg = settingsStore.buildTariffConfig()
            val netWorkingPrice = cfg.totalNetSurchargeCtPerKwh
            val vatMultiplier = 1.0 + cfg.vatRate
            val floorGross = cfg.computeGrossCtPerKwh(0.0)
            val sampleSpotNet = 5.0
            val sampleGross = cfg.computeGrossCtPerKwh(sampleSpotNet * 10.0)

            formulaDetailText.text = String.format(
                java.util.Locale.US,
                i18n.contractFormulaDetail,
                netWorkingPrice,
                vatMultiplier,
                cfg.vatRate * 100.0,
                floorGross,
                sampleGross,
                cfg.baseFeeMonthlyGross
            )
        }
        updateDynamicFormula.invoke()
        card.addView(dynamicPanel)

        // =========================================================================
        // PANEL B: TIME OF USE (PEAK / OFF-PEAK HT/NT)
        // =========================================================================
        // Peak Price (HT)
        val peakField = createPriceInputField(
            label = i18n.peakPriceLabel,
            sublabel = i18n.peakPriceSub,
            initialValue = formatPriceValue(settingsStore.tariffTouPeakPriceGrossCt, 2),
            unitSuffix = "ct/kWh gross"
        ) { parsed ->
            if (parsed != null && parsed >= 0.0) {
                settingsStore.tariffTouPeakPriceGrossCt = parsed
                updateTouSummary?.invoke()
            }
        }
        touPanel.addView(peakField.root)

        // Off-Peak Price (NT)
        val offPeakField = createPriceInputField(
            label = i18n.offPeakPriceLabel,
            sublabel = i18n.offPeakPriceSub,
            initialValue = formatPriceValue(settingsStore.tariffTouOffPeakPriceGrossCt, 2),
            unitSuffix = "ct/kWh gross"
        ) { parsed ->
            if (parsed != null && parsed >= 0.0) {
                settingsStore.tariffTouOffPeakPriceGrossCt = parsed
                updateTouSummary?.invoke()
            }
        }
        touPanel.addView(offPeakField.root)

        // Timetable Start and End Hours
        val timePickerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(4))
        }
        timePickerRow.addView(TextView(this).apply {
            text = i18n.offPeakHoursLabel
            setTextColor(Color.parseColor(palette.textPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        val startBtn = createStyledButton(
            label = String.format(java.util.Locale.US, "%02d:00", settingsStore.tariffTouOffPeakStartHour),
            bgHex = palette.secondaryBtnBg,
            strokeHex = palette.secondaryBtnStroke,
            textHex = palette.accentGreen
        ).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        val arrowText = TextView(this).apply {
            text = " → "
            setTextColor(Color.parseColor(palette.textMuted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }

        val endBtn = createStyledButton(
            label = String.format(java.util.Locale.US, "%02d:00", settingsStore.tariffTouOffPeakEndHour),
            bgHex = palette.secondaryBtnBg,
            strokeHex = palette.secondaryBtnStroke,
            textHex = palette.accentGreen
        ).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        startBtn.setOnClickListener {
            var h = settingsStore.tariffTouOffPeakStartHour
            h = (h + 1) % 24
            settingsStore.tariffTouOffPeakStartHour = h
            startBtn.text = String.format(java.util.Locale.US, "%02d:00", h)
            updateTouSummary?.invoke()
        }

        endBtn.setOnClickListener {
            var h = settingsStore.tariffTouOffPeakEndHour
            h = (h + 1) % 24
            settingsStore.tariffTouOffPeakEndHour = h
            endBtn.text = String.format(java.util.Locale.US, "%02d:00", h)
            updateTouSummary?.invoke()
        }

        timePickerRow.addView(startBtn)
        timePickerRow.addView(arrowText)
        timePickerRow.addView(endBtn)
        touPanel.addView(timePickerRow)

        // Weekend All-Day Off-Peak Switch
        val weekendSwitch = Switch(this).apply {
            text = i18n.weekendOffPeakLabel
            setTextColor(Color.parseColor(palette.textPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            isChecked = settingsStore.tariffTouWeekendAllOffPeak
            setPadding(0, dp(4), 0, dp(6))
            setOnCheckedChangeListener { _, isChecked ->
                settingsStore.tariffTouWeekendAllOffPeak = isChecked
                updateTouSummary?.invoke()
            }
        }
        touPanel.addView(weekendSwitch)

        // TOU Live Summary Box
        val touSummaryBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 8)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
            layoutParams = lp
        }
        val touSummaryTitle = TextView(this).apply {
            text = i18n.touScheduleTitle
            setTextColor(Color.parseColor(palette.accentBlue))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        }
        val touSummaryDetail = TextView(this).apply {
            setTextColor(Color.parseColor(palette.textSecondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }
        touSummaryBox.addView(touSummaryTitle)
        touSummaryBox.addView(touSummaryDetail)
        touPanel.addView(touSummaryBox)

        updateTouSummary = {
            val peak = settingsStore.tariffTouPeakPriceGrossCt
            val offPeak = settingsStore.tariffTouOffPeakPriceGrossCt
            val spread = peak - offPeak
            val startH = settingsStore.tariffTouOffPeakStartHour
            val endH = settingsStore.tariffTouOffPeakEndHour
            val durationHours = if (startH > endH) (24 - startH + endH) else if (startH < endH) (endH - startH) else 24
            val weekendStr = if (settingsStore.tariffTouWeekendAllOffPeak) i18n.touWeekendAllDay else i18n.touWeekendStandard

            val timetable = TimeOfUseTimetable(
                peakPriceGrossCtPerKwh = peak,
                offPeakPriceGrossCtPerKwh = offPeak,
                offPeakStartHour = startH,
                offPeakEndHour = endH,
                weekendAllOffPeak = settingsStore.tariffTouWeekendAllOffPeak
            )
            val nowBerlin = java.time.ZonedDateTime.now(SpotPriceClient.BERLIN_ZONE)
            val currentlyOffPeak = timetable.isOffPeak(nowBerlin)
            val curRate = timetable.getGrossPrice(nowBerlin)
            val statusLabel = if (currentlyOffPeak) i18n.touStatusOffPeak else i18n.touStatusPeak

            touSummaryDetail.text = String.format(
                java.util.Locale.US,
                i18n.touSummaryDetail,
                statusLabel,
                curRate,
                startH,
                endH,
                durationHours,
                weekendStr,
                spread,
                (spread / offPeak.coerceAtLeast(0.1)) * 100.0
            )
        }
        updateTouSummary.invoke()
        card.addView(touPanel)

        // =========================================================================
        // PANEL C: FLAT RATE (FESTPREIS)
        // =========================================================================
        val flatField = createPriceInputField(
            label = i18n.flatRateLabel,
            sublabel = i18n.flatRateSub,
            initialValue = formatPriceValue(settingsStore.tariffFlatPriceGrossCt, 2),
            unitSuffix = "ct/kWh gross"
        ) { parsed ->
            if (parsed != null && parsed >= 0.0) {
                settingsStore.tariffFlatPriceGrossCt = parsed
            }
        }
        flatPanel.addView(flatField.root)

        val flatInfoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 8)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
            layoutParams = lp

            addView(TextView(this@SettingsActivity).apply {
                text = i18n.flatRateNoteTitle
                setTextColor(Color.parseColor(palette.accentBlue))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
            })
            addView(TextView(this@SettingsActivity).apply {
                text = i18n.flatRateNoteDesc
                setTextColor(Color.parseColor(palette.textSecondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setLineSpacing(dp(2).toFloat(), 1.0f)
            })
        }
        flatPanel.addView(flatInfoBox)
        card.addView(flatPanel)

        // Initialize active panel visibility
        updateModelUi(activeModel)

        return card
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
