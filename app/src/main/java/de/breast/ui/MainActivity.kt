package de.breast.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import de.breast.api.PointTApiClient
import de.breast.auth.AndroidPrefsScheduleStateStore
import de.breast.auth.AndroidPrefsTokenStore
import de.breast.auth.SingleKeyAuthManager
import de.breast.auth.UserSettingsStore
import de.breast.daemon.AutoScheduleDaemon
import de.breast.daemon.BreastNotificationHelper
import de.breast.daemon.SpotScheduleJobService
import de.breast.optimizer.HeatingTemperatureSlider
import de.breast.optimizer.OperationDurationSlider
import de.breast.optimizer.SwitchProgramScheduler
import de.breast.spot.SpotPriceClient
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private data class UiPalette(
        val pageBg: String,
        val cardBg: String,
        val cardStroke: String,
        val tileBg: String,
        val tileStroke: String,
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
        val accentPurple: String,
        val primaryBtnBg: String,
        val primaryBtnStroke: String,
        val primaryBtnText: String,
        val secondaryBtnBg: String,
        val secondaryBtnStroke: String,
        val secondaryBtnText: String,
        val inputBg: String,
        val inputStroke: String,
        val badgeBg: String,
        val badgeStroke: String,
        val terminalBg: String,
        val terminalStroke: String,
        val terminalText: String
    )

    private lateinit var palette: UiPalette

    private lateinit var httpClient: OkHttpClient
    private lateinit var tokenStore: AndroidPrefsTokenStore
    private lateinit var stateStore: AndroidPrefsScheduleStateStore
    private lateinit var settingsStore: UserSettingsStore
    private lateinit var authManager: SingleKeyAuthManager
    private lateinit var pointTClient: PointTApiClient
    private lateinit var spotClient: SpotPriceClient

    private var sliderModel = HeatingTemperatureSlider()
    private var durationSliderModel = OperationDurationSlider()

    // Dashboard UI elements
    private lateinit var authBadgeText: TextView
    private lateinit var lastSyncSubText: TextView
    private lateinit var loginBtn: Button

    private lateinit var todayWindowTitle: TextView
    private lateinit var todayWindowBody: TextView
    private lateinit var tomorrowWindowTitle: TextView
    private lateinit var tomorrowWindowBody: TextView
    private lateinit var syncNowBtn: Button

    private lateinit var sliderValueBadge: TextView
    private lateinit var tempSeekBar: SeekBar
    private lateinit var durationValueBadge: TextView
    private lateinit var durationSeekBar: SeekBar

    private lateinit var budgetInput: EditText
    private lateinit var powerInput: EditText

    private lateinit var bgSwitch: Switch
    private lateinit var cronStatusText: TextView
    private lateinit var batteryBtn: Button

    // Developer / Debug Mode elements (hidden in normal daily usage)
    private lateinit var debugSwitch: Switch
    private lateinit var debugContainer: LinearLayout
    private lateinit var logOutputText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dark = isSystemInDarkMode()
        palette = buildPalette(dark)
        applySystemBarColors(dark)

        httpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

        tokenStore = AndroidPrefsTokenStore(this)
        stateStore = AndroidPrefsScheduleStateStore(this)
        settingsStore = UserSettingsStore(this)
        authManager = SingleKeyAuthManager(httpClient, tokenStore)
        pointTClient = PointTApiClient(httpClient, authManager)
        spotClient = SpotPriceClient(httpClient)

        sliderModel = sliderModel.withValue(settingsStore.sliderTempC)
        durationSliderModel = durationSliderModel.withHours(settingsStore.targetOperationHours)

        BreastNotificationHelper.ensureChannels(this)
        requestNotificationPermissionIfNeeded()

        setContentView(buildContentView())

        if (settingsStore.autoBackgroundEnabled) {
            SpotScheduleJobService.scheduleHourlyBackgroundSync(this)
        }

        handleIncomingOAuthIntent(intent)
        refreshUiStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshUiStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingOAuthIntent(intent)
    }

    private fun isSystemInDarkMode(): Boolean {
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES
    }

    private fun buildPalette(dark: Boolean): UiPalette {
        return if (dark) {
            UiPalette(
                pageBg = "#0F172A",
                cardBg = "#1E293B",
                cardStroke = "#334155",
                tileBg = "#0F172A",
                tileStroke = "#334155",
                textPrimary = "#F8FAFC",
                textSecondary = "#94A3B8",
                textMuted = "#64748B",
                accentBlue = "#38BDF8",
                accentBlueBg = "#1E3A5F",
                accentBlueStroke = "#38BDF8",
                accentGreen = "#4ADE80",
                accentGreenBg = "#052E16",
                accentGreenStroke = "#22C55E",
                accentAmber = "#FBBF24",
                accentAmberBg = "#451A03",
                accentAmberStroke = "#F59E0B",
                accentPurple = "#A78BFA",
                primaryBtnBg = "#0284C7",
                primaryBtnStroke = "#38BDF8",
                primaryBtnText = "#FFFFFF",
                secondaryBtnBg = "#1E293B",
                secondaryBtnStroke = "#475569",
                secondaryBtnText = "#E2E8F0",
                inputBg = "#0F172A",
                inputStroke = "#334155",
                badgeBg = "#0F172A",
                badgeStroke = "#334155",
                terminalBg = "#090D16",
                terminalStroke = "#1E293B",
                terminalText = "#4ADE80"
            )
        } else {
            UiPalette(
                pageBg = "#F1F5F9",
                cardBg = "#FFFFFF",
                cardStroke = "#E2E8F0",
                tileBg = "#F8FAFC",
                tileStroke = "#E2E8F0",
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
                accentPurple = "#6D28D9",
                primaryBtnBg = "#0284C7",
                primaryBtnStroke = "#0369A1",
                primaryBtnText = "#FFFFFF",
                secondaryBtnBg = "#F8FAFC",
                secondaryBtnStroke = "#CBD5E1",
                secondaryBtnText = "#1E293B",
                inputBg = "#F8FAFC",
                inputStroke = "#CBD5E1",
                badgeBg = "#F1F5F9",
                badgeStroke = "#CBD5E1",
                terminalBg = "#0F172A",
                terminalStroke = "#334155",
                terminalText = "#4ADE80"
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
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !dark) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun buildContentView(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(palette.pageBg))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }

        // =========================================================================
        // 1. HERO HEADER & CONNECTION CARD
        // =========================================================================
        val headerCard = createCard().apply {
            val topRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val titleCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = "Smart Heat Pump"
                    setTextColor(Color.parseColor(palette.textPrimary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    text = "EPEX Spot DE-LU  •  Bosch K30"
                    setTextColor(Color.parseColor(palette.textSecondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setPadding(0, dp(2), 0, 0)
                })
            }
            topRow.addView(titleCol)

            authBadgeText = TextView(this@MainActivity).apply {
                text = "Checking..."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = roundedBg(palette.accentBlueBg, palette.accentBlueStroke, cornerDp = 20)
                setTextColor(Color.parseColor(palette.accentBlue))
            }
            topRow.addView(authBadgeText)
            addView(topRow)

            lastSyncSubText = TextView(this@MainActivity).apply {
                text = "Last sync: Never"
                setTextColor(Color.parseColor(palette.textSecondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(10), 0, dp(10))
            }
            addView(lastSyncSubText)

            loginBtn = createStyledButton(
                label = "Connect Bosch SingleKey ID",
                bgHex = palette.secondaryBtnBg,
                strokeHex = palette.secondaryBtnStroke,
                textHex = palette.secondaryBtnText
            ).apply {
                setOnClickListener { openSingleKeyWebViewDialog() }
            }
            addView(loginBtn)
        }
        root.addView(headerCard)

        // =========================================================================
        // 2. ACTIVE SCHEDULE WINDOWS CARD (TODAY & TOMORROW)
        // =========================================================================
        val scheduleCard = createCard().apply {
            addView(cardSectionTitle("Scheduled Lowest-Price Windows"))
            addView(cardSubtitle("Shared single contiguous window for Space Heating (HC1) & Warm Water (DHW1)"))

            // Today tile
            val todayTile = createInnerTile().apply {
                todayWindowTitle = TextView(this@MainActivity).apply {
                    text = "TODAY"
                    setTextColor(Color.parseColor(palette.accentBlue))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTypeface(typeface, Typeface.BOLD)
                }
                addView(todayWindowTitle)

                todayWindowBody = TextView(this@MainActivity).apply {
                    text = "Tap 'Sync Lowest-Price Schedule Now' to compute today's window."
                    setTextColor(Color.parseColor(palette.textPrimary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                    setPadding(0, dp(4), 0, 0)
                }
                addView(todayWindowBody)
            }
            addView(todayTile)

            // Tomorrow tile
            val tomorrowTile = createInnerTile().apply {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
                layoutParams = lp

                tomorrowWindowTitle = TextView(this@MainActivity).apply {
                    text = "TOMORROW (DAY-AHEAD)"
                    setTextColor(Color.parseColor(palette.accentPurple))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTypeface(typeface, Typeface.BOLD)
                }
                addView(tomorrowWindowTitle)

                tomorrowWindowBody = TextView(this@MainActivity).apply {
                    text = "Published daily between 12:00 and 15:00 CET."
                    setTextColor(Color.parseColor(palette.textSecondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(0, dp(4), 0, 0)
                }
                addView(tomorrowWindowBody)
            }
            addView(tomorrowTile)

            syncNowBtn = createStyledButton(
                label = "Sync Lowest-Price Schedule Now",
                bgHex = palette.primaryBtnBg,
                strokeHex = palette.primaryBtnStroke,
                textHex = palette.primaryBtnText
            ).apply {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(14) }
                layoutParams = lp
                setOnClickListener {
                    saveInputsToSettings()
                    runManualScheduleSync(forcePush = true)
                }
            }
            addView(syncNowBtn)
        }
        root.addView(scheduleCard)

        // =========================================================================
        // 3. COMFORT TEMPERATURE & RUNTIME SLIDERS CARD
        // =========================================================================
        val slidersCard = createCard().apply {
            addView(cardSectionTitle("Comfort & Runtime Controls"))
            addView(cardSubtitle("Adjust target room comfort temperature and daily operating duration"))

            // --- Temperature Slider Header ---
            val tempHeaderRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(4))
            }
            tempHeaderRow.addView(TextView(this@MainActivity).apply {
                text = "Heating Target Temperature"
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            sliderValueBadge = TextView(this@MainActivity).apply {
                text = String.format("%.1f °C", sliderModel.snappedTempC)
                setTextColor(Color.parseColor(palette.accentBlue))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 12)
            }
            tempHeaderRow.addView(sliderValueBadge)
            addView(tempHeaderRow)

            tempSeekBar = SeekBar(this@MainActivity).apply {
                max = sliderModel.totalSteps
                progress = sliderModel.stepIndexFor(sliderModel.snappedTempC)
                setPadding(dp(4), dp(6), dp(4), dp(2))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val temp = sliderModel.tempForStepIndex(progress)
                        sliderModel = sliderModel.withValue(temp)
                        sliderValueBadge.text = String.format("%.1f °C", sliderModel.snappedTempC)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        settingsStore.sliderTempC = sliderModel.snappedTempC
                        pushSliderTemperatureIfAuthenticated()
                    }
                })
            }
            addView(tempSeekBar)

            addView(TextView(this@MainActivity).apply {
                text = "Applies to Space Heating (HC1 comfort2). Setback (17°C) & Warm Water (42°C) remain untouched."
                setTextColor(Color.parseColor(palette.textMuted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(0, 0, 0, dp(14))
            })

            // --- Runtime Duration Slider Header ---
            val durHeaderRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            durHeaderRow.addView(TextView(this@MainActivity).apply {
                text = "Target Daily Runtime"
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            durationValueBadge = TextView(this@MainActivity).apply {
                text = String.format("%.1f h (%d min)", durationSliderModel.snappedHours, durationSliderModel.snappedMinutes)
                setTextColor(Color.parseColor(palette.accentGreen))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(10), dp(3), dp(10), dp(3))
                background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 12)
            }
            durHeaderRow.addView(durationValueBadge)
            addView(durHeaderRow)

            durationSeekBar = SeekBar(this@MainActivity).apply {
                max = durationSliderModel.totalSteps
                progress = durationSliderModel.stepIndexFor(durationSliderModel.snappedHours)
                setPadding(dp(4), dp(6), dp(4), dp(2))
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        val hours = durationSliderModel.hoursForStepIndex(progress)
                        durationSliderModel = durationSliderModel.withHours(hours)
                        durationValueBadge.text = String.format(
                            "%.1f h (%d min)",
                            durationSliderModel.snappedHours,
                            durationSliderModel.snappedMinutes
                        )
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        settingsStore.targetOperationHours = durationSliderModel.snappedHours
                    }
                })
            }
            addView(durationSeekBar)
        }
        root.addView(slidersCard)

        // =========================================================================
        // 4. BUDGET & AUTOMATION CARD
        // =========================================================================
        val automationCard = createCard().apply {
            addView(cardSectionTitle("Budget & Background Automation"))
            addView(cardSubtitle("Optional daily EUR cap and automatic 12:00–15:00 spot price sync"))

            val inputsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(10))
            }

            // Left column: Daily Budget
            val budgetCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(8)
                }
                addView(TextView(this@MainActivity).apply {
                    text = "Daily Budget (€/day)"
                    setTextColor(Color.parseColor(palette.textSecondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, 0, 0, dp(4))
                })
                budgetInput = createStyledEditText(
                    hintText = "No limit",
                    initialText = settingsStore.dailyBudgetEuro?.let { String.format("%.2f", it) }.orEmpty()
                )
                addView(budgetInput)
            }
            inputsRow.addView(budgetCol)

            // Right column: Power Draw
            val powerCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(8)
                }
                addView(TextView(this@MainActivity).apply {
                    text = "Avg Power Draw (kW)"
                    setTextColor(Color.parseColor(palette.textSecondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, 0, 0, dp(4))
                })
                powerInput = createStyledEditText(
                    hintText = "2.0",
                    initialText = String.format("%.1f", settingsStore.estimatedPowerKw)
                )
                addView(powerInput)
            }
            inputsRow.addView(powerCol)
            addView(inputsRow)

            bgSwitch = Switch(this@MainActivity).apply {
                text = "Automatic Background Sync (Hourly)"
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                isChecked = settingsStore.autoBackgroundEnabled
                setPadding(0, dp(6), 0, dp(6))
                setOnCheckedChangeListener { _, isChecked ->
                    settingsStore.autoBackgroundEnabled = isChecked
                    if (isChecked) {
                        SpotScheduleJobService.scheduleHourlyBackgroundSync(this@MainActivity)
                        appendLog("Background hourly schedule check ENABLED (JobScheduler).")
                    } else {
                        SpotScheduleJobService.cancelBackgroundSync(this@MainActivity)
                        appendLog("Background hourly schedule check DISABLED.")
                    }
                    refreshUiStatus()
                }
            }
            addView(bgSwitch)

            cronStatusText = TextView(this@MainActivity).apply {
                text = "Checking background schedule status..."
                setTextColor(Color.parseColor(palette.textSecondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(2), 0, dp(6))
            }
            addView(cronStatusText)

            batteryBtn = createStyledButton(
                label = "Grant Unrestricted Battery (Recommended)",
                bgHex = palette.accentAmberBg,
                strokeHex = palette.accentAmberStroke,
                textHex = palette.accentAmber
            ).apply {
                setOnClickListener { requestIgnoreBatteryOptimizations() }
            }
            addView(batteryBtn)
        }
        root.addView(automationCard)

        // =========================================================================
        // 5. DEVELOPER / DEBUG MODE CARD (COLLAPSED BY DEFAULT)
        // =========================================================================
        val debugCard = createCard().apply {
            debugSwitch = Switch(this@MainActivity).apply {
                text = "Developer / Debug Mode (API Telemetry & Console)"
                setTextColor(Color.parseColor(palette.textSecondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                isChecked = settingsStore.debugModeEnabled
                setOnCheckedChangeListener { _, isChecked ->
                    settingsStore.debugModeEnabled = isChecked
                    debugContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
                }
            }
            addView(debugSwitch)

            debugContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (settingsStore.debugModeEnabled) View.VISIBLE else View.GONE
                setPadding(0, dp(12), 0, 0)

                val readScheduleBtn = createStyledButton(
                    label = "Inspect Raw Heat Pump Schedule & Endpoints",
                    bgHex = palette.secondaryBtnBg,
                    strokeHex = palette.secondaryBtnStroke,
                    textHex = palette.accentBlue
                ).apply {
                    setOnClickListener { readCurrentHeatPumpSchedule() }
                }
                addView(readScheduleBtn)

                logOutputText = TextView(this@MainActivity).apply {
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(10) }
                    layoutParams = lp
                    typeface = Typeface.MONOSPACE
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    background = roundedBg(palette.terminalBg, palette.terminalStroke, cornerDp = 10)
                    setTextColor(Color.parseColor(palette.terminalText))
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    text = "Ready. Telemetry logs will appear here."
                }
                addView(logOutputText)
            }
            addView(debugContainer)
        }
        root.addView(debugCard)

        scroll.addView(root)
        return scroll
    }

    private fun saveInputsToSettings() {
        settingsStore.sliderTempC = sliderModel.snappedTempC
        settingsStore.targetOperationHours = durationSliderModel.snappedHours
        val budgetVal = budgetInput.text?.toString()?.replace(',', '.')?.toDoubleOrNull()
        settingsStore.dailyBudgetEuro = budgetVal
        val powerVal = powerInput.text?.toString()?.replace(',', '.')?.toDoubleOrNull() ?: 2.0
        settingsStore.estimatedPowerKw = powerVal.coerceAtLeast(0.2)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (isIgnoringBatteryOptimizations()) {
                Toast.makeText(
                    this,
                    "Unrestricted Background Battery is already active!",
                    Toast.LENGTH_SHORT
                ).show()
                refreshUiStatus()
                return
            }
            runCatching {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }.onFailure {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(fallback)
            }
        }
    }

    private fun refreshUiStatus() {
        lifecycleScope.launch {
            val tokens = tokenStore.loadTokens()
            val syncState = stateStore.loadState()

            if (tokens != null) {
                authBadgeText.text = "● Connected"
                authBadgeText.setTextColor(Color.parseColor(palette.accentGreen))
                authBadgeText.background = roundedBg(palette.accentGreenBg, palette.accentGreenStroke, cornerDp = 20)
                loginBtn.text = "Reconnect Bosch SingleKey ID"
            } else {
                authBadgeText.text = "● Login Needed"
                authBadgeText.setTextColor(Color.parseColor(palette.accentAmber))
                authBadgeText.background = roundedBg(palette.accentAmberBg, palette.accentAmberStroke, cornerDp = 20)
                loginBtn.text = "Sign In with Bosch SingleKey ID"
            }

            val lastSyncFormatted = syncState.lastSyncTimestampIso
                ?.replace("T", " ")
                ?.substringBefore("+")
                ?.substringBefore(".")
                ?: "Never"
            lastSyncSubText.text = "Last schedule sync: $lastSyncFormatted"

            // Populate Today & Tomorrow visual schedule cards
            todayWindowTitle.text = if (syncState.lastSyncedTodayDate != null) {
                "TODAY (${syncState.lastSyncedTodayDate})  •  SYNCED"
            } else {
                "TODAY'S SCHEDULE"
            }
            todayWindowBody.text = syncState.todayWindowSummary
                ?: "No schedule synced yet today. Tap 'Sync Lowest-Price Schedule Now'."

            tomorrowWindowTitle.text = if (syncState.lastSyncedTomorrowDate != null) {
                "TOMORROW (${syncState.lastSyncedTomorrowDate})  •  SYNCED"
            } else {
                "TOMORROW (DAY-AHEAD)"
            }
            tomorrowWindowBody.text = syncState.tomorrowWindowSummary
                ?: "Spot prices publish daily between 12:00 and 15:00 CET. Auto-sync will push automatically."

            val jobActive = SpotScheduleJobService.isHourlyJobScheduled(this@MainActivity)
            val batteryUnrestricted = isIgnoringBatteryOptimizations()
            cronStatusText.text = "Hourly Cron: ${if (jobActive) "Active" else "Paused"}  •  " +
                "Battery: ${if (batteryUnrestricted) "Unrestricted (Optimal)" else "Optimized (May delay background sync)"}"
            batteryBtn.visibility = if (batteryUnrestricted) View.GONE else View.VISIBLE

            if (!syncState.lastRunLog.isNullOrBlank()) {
                logOutputText.text = syncState.lastRunLog
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openSingleKeyWebViewDialog() {
        lifecycleScope.launch {
            val authReq = authManager.createAuthorizationRequest()
            CookieManager.getInstance().setAcceptCookie(true)

            val webView = object : WebView(this@MainActivity) {
                override fun onCheckIsTextEditor(): Boolean = true
            }.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(520)
                )
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Bosch SingleKey ID Login")
                .setView(webView)
                .setNegativeButton("Cancel", null)
                .create()

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    if (url.startsWith("com.bosch.tt.dashtt.pointt://app/login")) {
                        dialog.dismiss()
                        completeOAuthExchange(url)
                        return true
                    }
                    return false
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (url != null && url.startsWith("com.bosch.tt.dashtt.pointt://app/login")) {
                        dialog.dismiss()
                        completeOAuthExchange(url)
                        return true
                    }
                    return false
                }
            }

            dialog.show()
            dialog.window?.clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
            )
            webView.loadUrl(authReq.authorizationUrl)
        }
    }

    private fun handleIncomingOAuthIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(BreastNotificationHelper.EXTRA_PROMPT_LOGIN, false)) {
            intent.removeExtra(BreastNotificationHelper.EXTRA_PROMPT_LOGIN)
            openSingleKeyWebViewDialog()
            return
        }
        val dataUri = intent.data?.toString() ?: return
        if (dataUri.startsWith("com.bosch.tt.dashtt.pointt://app/login")) {
            completeOAuthExchange(dataUri)
        }
    }

    private fun completeOAuthExchange(redirectUrlOrCode: String) {
        lifecycleScope.launch {
            appendLog("Intercepted SingleKey OAuth callback! Exchanging authorization code for tokens...")
            runCatching {
                val tokens = authManager.exchangeCodeForTokens(redirectUrlOrCode)
                BreastNotificationHelper.clearCredentialIssueNotification(this@MainActivity)
                appendLog("Authenticated! Token expiresIn=${tokens.expiresIn}s")
                val gateways = pointTClient.getGateways()
                appendLog("Discovered ${gateways.size} Bosch Gateway(s): ${gateways.map { "${it.deviceId} (${it.deviceType})" }}")
                Toast.makeText(
                    this@MainActivity,
                    "Connected to Bosch Gateway ${gateways.firstOrNull()?.deviceId.orEmpty()}",
                    Toast.LENGTH_SHORT
                ).show()
                refreshUiStatus()
            }.onFailure { e ->
                appendLog("OAuth exchange failed: ${e.message}")
                Toast.makeText(this@MainActivity, "Login failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun pushSliderTemperatureIfAuthenticated() {
        lifecycleScope.launch {
            tokenStore.loadTokens() ?: return@launch
            runCatching {
                val gw = pointTClient.getGateways().firstOrNull() ?: return@launch
                val scheduler = SwitchProgramScheduler(settingsStore.buildScheduleConfig())
                val applied = scheduler.applyTemperatureSliderValue(pointTClient, gw.deviceId, sliderModel.snappedTempC)
                Toast.makeText(
                    this@MainActivity,
                    "Heating Target set to ${applied}°C",
                    Toast.LENGTH_SHORT
                ).show()
                appendLog("Pushed Heating Slider Target Temperature -> ${applied}°C on gateway ${gw.deviceId}")
            }.onFailure { e ->
                appendLog("Failed to push slider temperature: ${e.message}")
            }
        }
    }

    private fun runManualScheduleSync(forcePush: Boolean) {
        lifecycleScope.launch {
            val hasToken = tokenStore.loadTokens() != null
            val cfg = settingsStore.buildScheduleConfig()
            val scheduler = SwitchProgramScheduler(cfg)

            if (forcePush) {
                stateStore.saveState(stateStore.loadState().copy(lastAppliedSliderTempC = null))
            }

            val daemon = AutoScheduleDaemon(
                spotClient = spotClient,
                pointTClient = pointTClient,
                scheduler = scheduler,
                stateStore = stateStore,
                dryRun = !hasToken
            )

            syncNowBtn.isEnabled = false
            syncNowBtn.text = "Syncing Schedule with Heat Pump..."

            appendLog(
                String.format(
                    "Fetching Vattenfall / EPEX Spot DE-LU Day-Ahead prices & computing single-window (%.1f h) schedule...",
                    cfg.targetOperationHoursPerDay
                )
            )
            runCatching {
                val res = daemon.runHourlyCheckCycle()
                logOutputText.text = res.logs.joinToString("\n")
                refreshUiStatus()
                Toast.makeText(
                    this@MainActivity,
                    if (res.pushedToHeatPump) "Schedule synced to heat pump!" else "Schedule updated!",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { e ->
                appendLog("Schedule sync error: ${e.message}")
                Toast.makeText(this@MainActivity, "Sync error: ${e.message}", Toast.LENGTH_LONG).show()
            }

            syncNowBtn.isEnabled = true
            syncNowBtn.text = "Sync Lowest-Price Schedule Now"
        }
    }

    private fun readCurrentHeatPumpSchedule() {
        lifecycleScope.launch {
            if (tokenStore.loadTokens() == null) {
                appendLog("Please login with Bosch SingleKey ID first to read gateway schedules.")
                return@launch
            }
            runCatching {
                val gw = pointTClient.getGateways().firstOrNull()
                    ?: error("No gateways found on account.")
                val scheduler = SwitchProgramScheduler(settingsStore.buildScheduleConfig())
                val hwSlider = scheduler.fetchHardwareTemperatureSlider(pointTClient, gw.deviceId)
                sliderModel = hwSlider
                settingsStore.sliderTempC = hwSlider.snappedTempC
                tempSeekBar.max = hwSlider.totalSteps
                tempSeekBar.progress = hwSlider.stepIndexFor(hwSlider.snappedTempC)

                val hcName = pointTClient.getActiveSwitchProgramName(gw.deviceId, "/heatingCircuits/hc1")
                val hcProg = pointTClient.getSwitchProgram(gw.deviceId, "/heatingCircuits/hc1/switchPrograms/$hcName")
                val dhwName = pointTClient.getActiveSwitchProgramName(gw.deviceId, "/dhwCircuits/dhw1")
                val dhwProg = pointTClient.getSwitchProgram(gw.deviceId, "/dhwCircuits/dhw1/switchPrograms/$dhwName")
                val hcTempLevels = pointTClient.getResource(gw.deviceId, "/heatingCircuits/hc1/temperatureLevels")

                val sb = StringBuilder()
                sb.appendLine("Gateway: ${gw.deviceId} (${gw.deviceType})")
                sb.appendLine("Hardware Heating Slider: ${hwSlider.snappedTempC}°C (range ${hwSlider.minTempC}..${hwSlider.maxTempC}°C, step ${hwSlider.stepSizeC}°C)")
                hcTempLevels?.references?.forEach { ref ->
                    val lvlNode = pointTClient.getResource(gw.deviceId, ref.id)
                    sb.appendLine("  Level ${ref.id.substringAfterLast("/")}: ${lvlNode?.value}°C (writable=${lvlNode?.writeable})")
                }
                sb.appendLine("\n--- Heating (hc1) SwitchProgram [$hcName] (writable=${hcProg?.writeable}, raster=${hcProg?.switchPointTimeRaster}m, maxPerDay=${hcProg?.maxNbOfSwitchPointsPerDay}) ---")
                hcProg?.switchPoints?.forEach { sp ->
                    sb.appendLine("  ${sp.dayOfWeek} ${sp.formatTimeHm()} (t=${sp.time}) -> ${sp.setpoint}")
                }
                sb.appendLine("\n--- Warm Water (dhw1) SwitchProgram [$dhwName] (writable=${dhwProg?.writeable}, raster=${dhwProg?.switchPointTimeRaster}m, maxPerDay=${dhwProg?.maxNbOfSwitchPointsPerDay}) ---")
                dhwProg?.switchPoints?.forEach { sp ->
                    sb.appendLine("  ${sp.dayOfWeek} ${sp.formatTimeHm()} (t=${sp.time}) -> ${sp.setpoint}")
                }
                logOutputText.text = sb.toString()
            }.onFailure { e ->
                appendLog("Failed to read schedule from gateway: ${e.message}")
            }
        }
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(palette.cardBg, palette.cardStroke, cornerDp = 16)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        }
    }

    private fun createInnerTile(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(palette.tileBg, palette.tileStroke, cornerDp = 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun cardSectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.parseColor(palette.textPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f)
            setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun cardSubtitle(subtitle: String): TextView {
        return TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor(palette.textSecondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(2), 0, dp(12))
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            background = roundedBg(bgHex, strokeHex, cornerDp = 12)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
    }

    private fun createStyledEditText(hintText: String, initialText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.parseColor(palette.textMuted))
            setTextColor(Color.parseColor(palette.textPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = roundedBg(palette.inputBg, palette.inputStroke, cornerDp = 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setText(initialText)
        }
    }

    private fun roundedBg(fillHex: String, strokeHex: String, cornerDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp).toFloat()
            setColor(Color.parseColor(fillHex))
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun appendLog(msg: String) {
        val current = logOutputText.text?.toString().orEmpty()
        logOutputText.text = if (current.isBlank()) msg else "$current\n$msg"
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
}
