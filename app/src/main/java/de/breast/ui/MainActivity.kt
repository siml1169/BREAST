package de.breast.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import de.breast.api.PointTApiClient
import de.breast.auth.AndroidPrefsScheduleStateStore
import de.breast.auth.AndroidPrefsTokenStore
import de.breast.auth.SingleKeyAuthManager
import de.breast.auth.UserSettingsStore
import de.breast.daemon.AutoScheduleDaemon
import de.breast.daemon.BreastNotificationHelper
import de.breast.daemon.SpotScheduleJobService
import de.breast.optimizer.DailySwitchSchedulePlan
import de.breast.optimizer.HeatingTemperatureSlider
import de.breast.optimizer.OperationDurationSlider
import de.breast.optimizer.SwitchProgramScheduler
import de.breast.spot.SpotPriceClient
import de.breast.telemetry.HeatPumpTelemetrySnapshot
import de.breast.telemetry.HeatingCurveAnalyzer
import de.breast.telemetry.SqliteTelemetryStore
import de.breast.weather.WeatherForecastClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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

    companion object {
        const val ACTION_TRIGGER_SYNC = "de.breast.ACTION_TRIGGER_SYNC"
    }

    private lateinit var httpClient: OkHttpClient
    private lateinit var tokenStore: AndroidPrefsTokenStore
    private lateinit var stateStore: AndroidPrefsScheduleStateStore
    private lateinit var settingsStore: UserSettingsStore
    private lateinit var authManager: SingleKeyAuthManager
    private lateinit var pointTClient: PointTApiClient
    private lateinit var spotClient: SpotPriceClient
    private lateinit var weatherClient: WeatherForecastClient
    private lateinit var telemetryStore: SqliteTelemetryStore
    private val curveAnalyzer = HeatingCurveAnalyzer()

    private var sliderModel = HeatingTemperatureSlider()
    private var durationSliderModel = OperationDurationSlider()

    // Dashboard UI elements
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var authBadgeText: TextView
    private lateinit var bgActiveBadgeText: TextView
    private lateinit var lastSyncSubText: TextView
    private lateinit var loginBtn: Button

    private lateinit var todayWindowTitle: TextView
    private lateinit var todayWindowBody: TextView
    private lateinit var tomorrowWindowTitle: TextView
    private lateinit var tomorrowWindowBody: TextView
    private lateinit var todayPhysicsBtn: TextView
    private lateinit var tomorrowPhysicsBtn: TextView
    private var latestSyncState: de.breast.daemon.ScheduleSyncState? = null
    private lateinit var manualAdjustmentBanner: TextView
    private lateinit var syncNowBtn: Button
    private lateinit var optimizationModeBadge: TextView
    private lateinit var inspectScheduleBtn: Button
    private lateinit var weeklyScheduleCard: LinearLayout
    private lateinit var weeklyScheduleText: TextView

    // Double-confirmation state for overwriting manually modified schedule
    private var overwriteConfirmTaps = 0
    private var overwriteResetJob: Job? = null

    // System Telemetry & Heating Curve UI elements
    private lateinit var telemetryOutdoorBadge: TextView
    private lateinit var telemetrySupplyBadge: TextView
    private lateinit var telemetryReturnBadge: TextView
    private lateinit var telemetryDeltaBadge: TextView
    private lateinit var telemetryModulationBadge: TextView
    private lateinit var telemetryDemandBadge: TextView
    private lateinit var heatingCurveEquationText: TextView
    private lateinit var heatingCurveEvaluationText: TextView
    private lateinit var heatingCurveChartView: HeatingCurveChartView
    private lateinit var telemetryCountText: TextView
    private lateinit var refreshTelemetryBtn: Button
    private lateinit var exportTelemetryBtn: Button

    private lateinit var sliderValueBadge: TextView
    private lateinit var tempSeekBar: SeekBar
    private lateinit var durationValueBadge: TextView
    private lateinit var durationSeekBar: SeekBar

    private lateinit var budgetInput: EditText
    private lateinit var powerInput: EditText

    private lateinit var bgSwitch: Switch
    private lateinit var cronStatusText: TextView
    private lateinit var batteryBtn: Button

    // Developer / Diagnostic elements (controlled via Settings)
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
        telemetryStore = SqliteTelemetryStore(this)
        authManager = SingleKeyAuthManager(httpClient, tokenStore)
        pointTClient = PointTApiClient(httpClient, authManager)
        spotClient = SpotPriceClient(httpClient)
        weatherClient = WeatherForecastClient(
            httpClient = httpClient,
            latitude = settingsStore.weatherLatitude,
            longitude = settingsStore.weatherLongitude
        )

        sliderModel = sliderModel.withValue(settingsStore.sliderTempC)
        durationSliderModel = durationSliderModel.withHours(settingsStore.targetOperationHours)

        BreastNotificationHelper.ensureChannels(this)
        requestNotificationPermissionIfNeeded()

        setContentView(buildContentView())

        if (settingsStore.autoBackgroundEnabled) {
            SpotScheduleJobService.scheduleHourlyBackgroundSync(this)
        }
        if (settingsStore.telemetryCollectionEnabled) {
            SpotScheduleJobService.scheduleNextTelemetryAlarm(this, settingsStore.telemetryIntervalMinutes)
        }

        handleIncomingOAuthIntent(intent)
        if (intent?.action == ACTION_TRIGGER_SYNC) {
            runManualScheduleSync(forcePush = true)
        }
        refreshUiStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::debugContainer.isInitialized) {
            debugContainer.visibility = if (settingsStore.debugModeEnabled) View.VISIBLE else View.GONE
        }
        refreshHeatingCurveAnalysis()
        refreshUiStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingOAuthIntent(intent)
        if (intent?.action == ACTION_TRIGGER_SYNC) {
            runManualScheduleSync(forcePush = true)
        }
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

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            BreastNotificationHelper.updateBackgroundActiveNotification(this)
            refreshUiStatus()
        }
    }

    private fun buildContentView(): View {
        swipeRefreshLayout = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(
                Color.parseColor(palette.accentBlue),
                Color.parseColor(palette.accentGreen),
                Color.parseColor(palette.accentPurple)
            )
            setProgressBackgroundColorSchemeColor(
                Color.parseColor(palette.cardBg)
            )
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnRefreshListener {
                performSwipeToRefresh()
            }
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            setBackgroundColor(Color.parseColor(palette.pageBg))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Apply window insets so soft keyboard (IME) pushes ScrollView content up instead of covering it
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val imeOrNav = insets.getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, imeOrNav.bottom)
            insets
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        // =========================================================================
        // TOP APP BAR WITH PROMINENT SETTINGS BUTTON
        // =========================================================================
        val topNavBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val appTitleText = TextView(this).apply {
            text = "BREAST Heat Pump"
            setTextColor(Color.parseColor(palette.textPrimary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topNavBar.addView(appTitleText)

        val topSettingsBtn = createStyledButton(
            label = "⚙ Settings",
            bgHex = palette.accentBlueBg,
            strokeHex = palette.accentBlueStroke,
            textHex = palette.accentBlue
        ).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        topNavBar.addView(topSettingsBtn)
        root.addView(topNavBar)

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

            val badgesCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
            }

            authBadgeText = TextView(this@MainActivity).apply {
                text = "Checking..."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = roundedBg(palette.accentBlueBg, palette.accentBlueStroke, cornerDp = 16)
                setTextColor(Color.parseColor(palette.accentBlue))
            }
            badgesCol.addView(authBadgeText)

            bgActiveBadgeText = TextView(this@MainActivity).apply {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                layoutParams = lp
                text = "● Background Active"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = roundedBg(palette.accentGreenBg, palette.accentGreenStroke, cornerDp = 16)
                setTextColor(Color.parseColor(palette.accentGreen))
            }
            badgesCol.addView(bgActiveBadgeText)
            topRow.addView(badgesCol)
            addView(topRow)

            lastSyncSubText = TextView(this@MainActivity).apply {
                text = "Last sync: Never"
                setTextColor(Color.parseColor(palette.textSecondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(10), 0, dp(10))
            }
            addView(lastSyncSubText)

            val headerBtnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                layoutParams = lp
            }

            loginBtn = createStyledButton(
                label = "Connect Bosch SingleKey ID",
                bgHex = palette.secondaryBtnBg,
                strokeHex = palette.secondaryBtnStroke,
                textHex = palette.secondaryBtnText
            ).apply {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f).apply {
                    rightMargin = dp(6)
                }
                layoutParams = lp
                setOnClickListener { openSingleKeyWebViewDialog() }
            }
            headerBtnRow.addView(loginBtn)

            val heroSettingsBtn = createStyledButton(
                label = "⚙ Settings",
                bgHex = palette.secondaryBtnBg,
                strokeHex = palette.secondaryBtnStroke,
                textHex = palette.accentBlue
            ).apply {
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f).apply {
                    leftMargin = dp(6)
                }
                layoutParams = lp
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
            }
            headerBtnRow.addView(heroSettingsBtn)
            addView(headerBtnRow)
        }
        root.addView(headerCard)

        // =========================================================================
        // 2. ACTIVE SCHEDULE WINDOWS CARD (TODAY & TOMORROW)
        // =========================================================================
        val scheduleCard = createCard().apply {
            addView(cardSectionTitle("Scheduled Lowest-Price Windows"))
            addView(cardSubtitle("Shared single contiguous window for Space Heating (HC1) & Warm Water (DHW1)"))

            manualAdjustmentBanner = TextView(this@MainActivity).apply {
                text = "⚠ manual adjustment detected, no further change"
                setTextColor(Color.parseColor(palette.accentAmber))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                background = roundedBg(palette.accentAmberBg, palette.accentAmberStroke, cornerDp = 8)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(4)
                }
                layoutParams = lp
                visibility = View.GONE
            }
            addView(manualAdjustmentBanner)

            optimizationModeBadge = TextView(this@MainActivity).apply {
                setTextColor(Color.parseColor(palette.accentBlue))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTypeface(typeface, Typeface.BOLD)
                background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 6)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(8)
                }
                layoutParams = lp
                visibility = View.GONE
            }
            addView(optimizationModeBadge)

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

                todayPhysicsBtn = TextView(this@MainActivity).apply {
                    text = "🔬 View Physics & Thermodynamic Audit"
                    setTextColor(Color.parseColor(palette.accentBlue))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(8), 0, 0)
                    visibility = View.GONE
                    setOnClickListener {
                        showPhysicsAuditDialog("Today's Schedule Physics Audit", latestSyncState?.todayPhysicsBreakdown)
                    }
                }
                addView(todayPhysicsBtn)
            }
            todayTile.setOnClickListener {
                latestSyncState?.todayPhysicsBreakdown?.let { breakdown ->
                    showPhysicsAuditDialog("Today's Schedule Physics Audit", breakdown)
                }
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

                tomorrowPhysicsBtn = TextView(this@MainActivity).apply {
                    text = "🔬 View Physics & Thermodynamic Audit"
                    setTextColor(Color.parseColor(palette.accentPurple))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(8), 0, 0)
                    visibility = View.GONE
                    setOnClickListener {
                        showPhysicsAuditDialog("Tomorrow's Schedule Physics Audit", latestSyncState?.tomorrowPhysicsBreakdown)
                    }
                }
                addView(tomorrowPhysicsBtn)
            }
            tomorrowTile.setOnClickListener {
                latestSyncState?.tomorrowPhysicsBreakdown?.let { breakdown ->
                    showPhysicsAuditDialog("Tomorrow's Schedule Physics Audit", breakdown)
                }
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
                    handleSyncButtonClick()
                }
            }
            addView(syncNowBtn)

            inspectScheduleBtn = createStyledButton(
                label = "Inspect 7-Day Heat Pump Schedule",
                bgHex = palette.secondaryBtnBg,
                strokeHex = palette.secondaryBtnStroke,
                textHex = palette.secondaryBtnText
            ).apply {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                layoutParams = lp
                setOnClickListener {
                    if (weeklyScheduleCard.visibility == View.VISIBLE) {
                        weeklyScheduleCard.visibility = View.GONE
                        text = "Inspect 7-Day Heat Pump Schedule"
                    } else {
                        weeklyScheduleCard.visibility = View.VISIBLE
                        text = "Hide 7-Day Heat Pump Schedule"
                        weeklyScheduleText.text = "Reading 7-day schedule directly from Bosch gateway..."
                        readCurrentHeatPumpSchedule(weeklyScheduleText)
                    }
                }
            }
            addView(inspectScheduleBtn)

            weeklyScheduleCard = createInnerTile().apply {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
                layoutParams = lp
                visibility = View.GONE

                val header = TextView(this@MainActivity).apply {
                    text = "ACTIVE 7-DAY HEAT PUMP SWITCH PROGRAM"
                    setTextColor(Color.parseColor(palette.accentGreen))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTypeface(typeface, Typeface.BOLD)
                }
                addView(header)

                weeklyScheduleText = TextView(this@MainActivity).apply {
                    text = "Tap to inspect."
                    setTextColor(Color.parseColor(palette.textPrimary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTypeface(Typeface.MONOSPACE)
                    setPadding(0, dp(6), 0, 0)
                }
                addView(weeklyScheduleText)
            }
            addView(weeklyScheduleCard)
        }
        root.addView(scheduleCard)

        // =========================================================================
        // 3. SYSTEM TELEMETRY & HEATING CURVE ANALYSIS CARD
        // =========================================================================
        val telemetryCard = createCard().apply {
            addView(cardSectionTitle("Live Telemetry & Real Heating Curve"))
            addView(cardSubtitle("Empirical flow curve extrapolation & full-day thermal profile"))

            // Row 1 of live readings (Outdoor, Supply, Return)
            val row1 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(6))
            }
            telemetryOutdoorBadge = createTelemetryBadge(defaultValue = "Out: --", accentHex = palette.accentBlue)
            telemetrySupplyBadge = createTelemetryBadge(defaultValue = "Flow: --", accentHex = palette.accentAmber)
            telemetryReturnBadge = createTelemetryBadge(defaultValue = "Ret: --", accentHex = palette.accentPurple)
            row1.addView(wrapBadgeCol(telemetryOutdoorBadge))
            row1.addView(wrapBadgeCol(telemetrySupplyBadge))
            row1.addView(wrapBadgeCol(telemetryReturnBadge))
            addView(row1)

            // Row 2 of live readings (Delta T, Modulation, Demand)
            val row2 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(10))
            }
            telemetryDeltaBadge = createTelemetryBadge(defaultValue = "ΔT: --", accentHex = palette.accentGreen)
            telemetryModulationBadge = createTelemetryBadge(defaultValue = "Mod: --", accentHex = palette.accentBlue)
            telemetryDemandBadge = createTelemetryBadge(defaultValue = "Mode: --", accentHex = palette.accentAmber)
            row2.addView(wrapBadgeCol(telemetryDeltaBadge))
            row2.addView(wrapBadgeCol(telemetryModulationBadge))
            row2.addView(wrapBadgeCol(telemetryDemandBadge))
            addView(row2)

            // Analysis Tile (Heating Curve Extrapolation)
            val curveTile = createInnerTile().apply {
                val curveHeaderRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }

                val curveTitle = TextView(this@MainActivity).apply {
                    text = "HEATING CURVE & OPERATING TELEMETRY (BOSCH HPC 410)"
                    setTextColor(Color.parseColor(palette.accentBlue))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                curveHeaderRow.addView(curveTitle)

                val fullscreenPill = TextView(this@MainActivity).apply {
                    text = "⛶ Fullscreen"
                    setTextColor(Color.parseColor(palette.accentBlue))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTypeface(typeface, Typeface.BOLD)
                    background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 12)
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    setOnClickListener { openFullscreenHeatingCurve() }
                }
                curveHeaderRow.addView(fullscreenPill)
                addView(curveHeaderRow)

                heatingCurveEquationText = TextView(this@MainActivity).apply {
                    text = "Awaiting central heating run data points..."
                    setTextColor(Color.parseColor(palette.textPrimary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(4), 0, dp(2))
                }
                addView(heatingCurveEquationText)

                heatingCurveEvaluationText = TextView(this@MainActivity).apply {
                    text = "Collects steady-state data points when central heating runs to learn your true building heat curve and compare against Bosch documentation."
                    setTextColor(Color.parseColor(palette.textSecondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                }
                addView(heatingCurveEvaluationText)

                heatingCurveChartView = HeatingCurveChartView(this@MainActivity).apply {
                    isDarkMode = isSystemInDarkMode()
                    onChartClickListener = { openFullscreenHeatingCurve() }
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(220)
                    ).apply {
                        topMargin = dp(8)
                        bottomMargin = dp(4)
                    }
                    layoutParams = lp
                }
                addView(heatingCurveChartView)

                telemetryCountText = TextView(this@MainActivity).apply {
                    text = "0 snapshots logged in local database"
                    setTextColor(Color.parseColor(palette.textMuted))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setPadding(0, dp(6), 0, 0)
                }
                addView(telemetryCountText)
            }
            addView(curveTile)

            // Button actions row
            val actionsRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(12) }
                layoutParams = lp
            }

            refreshTelemetryBtn = createStyledButton(
                label = "Refresh Telemetry Now",
                bgHex = palette.secondaryBtnBg,
                strokeHex = palette.secondaryBtnStroke,
                textHex = palette.accentBlue
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(6)
                }
                setOnClickListener { fetchAndStoreLiveTelemetry() }
            }
            actionsRow.addView(refreshTelemetryBtn)

            exportTelemetryBtn = createStyledButton(
                label = "Export CSV",
                bgHex = palette.secondaryBtnBg,
                strokeHex = palette.secondaryBtnStroke,
                textHex = palette.accentGreen
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(6)
                }
                setOnClickListener { exportTelemetryDialog() }
            }
            actionsRow.addView(exportTelemetryBtn)

            addView(actionsRow)
        }
        root.addView(telemetryCard)

        // =========================================================================
        // 4. COMFORT TEMPERATURE & RUNTIME SLIDERS CARD
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
                        if (fromUser && overwriteConfirmTaps > 0) {
                            resetOverwriteConfirmation()
                        }
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
        // 4. OPERATING BUDGET & HEAT PUMP POWER
        // =========================================================================
        val budgetCard = createCard().apply {
            addView(cardSectionTitle("Daily Budget & Heat Pump Power"))
            addView(cardSubtitle("Economic constraints and electrical power consumption for cost calculation"))

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
                ).apply {
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            saveInputsToSettings()
                        }
                    })
                    setOnFocusChangeListener { v, hasFocus ->
                        if (hasFocus) {
                            scroll.postDelayed({
                                val r = Rect()
                                v.getDrawingRect(r)
                                v.requestRectangleOnScreen(r, false)
                            }, 150)
                        } else {
                            saveInputsToSettings()
                        }
                    }
                }
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
                    text = "Power Model / Draw (kW)"
                    setTextColor(Color.parseColor(palette.textSecondary))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, 0, 0, dp(4))
                })
                powerInput = createStyledEditText(
                    hintText = "Auto (~0.6kW)",
                    initialText = if (settingsStore.autoPowerModulationEnabled) "" else String.format(java.util.Locale.US, "%.1f", settingsStore.estimatedPowerKw)
                ).apply {
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            saveInputsToSettings()
                        }
                    })
                    setOnFocusChangeListener { v, hasFocus ->
                        if (hasFocus) {
                            scroll.postDelayed({
                                val r = Rect()
                                v.getDrawingRect(r)
                                v.requestRectangleOnScreen(r, false)
                            }, 150)
                        } else {
                            saveInputsToSettings()
                        }
                    }
                }
                addView(powerInput)
            }
            inputsRow.addView(powerCol)
            addView(inputsRow)

            val budgetExplanationText = TextView(this@MainActivity).apply {
                text = "• Hard Budget Cap: If set, the optimizer restricts runtime so daily electricity spend never exceeds this limit.\n• Avg Power Draw: Used to multiply dynamic spot tariff rates (€/kWh) into actual daily euro expenditure."
                setTextColor(Color.parseColor(palette.textMuted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(0, 0, 0, dp(4))
            }
            addView(budgetExplanationText)
        }
        root.addView(budgetCard)

        // =========================================================================
        // 5. BACKGROUND AUTOMATION & SPOT PRICE SYNC
        // =========================================================================
        val automationCard = createCard().apply {
            addView(cardSectionTitle("Background Automation & EPEX Spot Sync"))
            addView(cardSubtitle("Hourly daemon pulls EPEX spot prices (12:00–15:00 CET) and pushes schedules"))

            bgSwitch = Switch(this@MainActivity).apply {
                text = "Automatic Background Sync (Hourly)"
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                isChecked = settingsStore.autoBackgroundEnabled
                setPadding(0, dp(6), 0, dp(6))
                setOnCheckedChangeListener { _, isChecked ->
                    settingsStore.autoBackgroundEnabled = isChecked
                    if (isChecked) {
                        requestNotificationPermissionIfNeeded()
                        SpotScheduleJobService.scheduleHourlyBackgroundSync(this@MainActivity)
                        appendLog("Background hourly schedule check ENABLED (JobScheduler).")
                    } else {
                        SpotScheduleJobService.cancelBackgroundSync(this@MainActivity)
                        appendLog("Background hourly schedule check DISABLED.")
                    }
                    BreastNotificationHelper.updateBackgroundActiveNotification(this@MainActivity)
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
        // 5. DIAGNOSTIC CONSOLE (CONTROLLED VIA SETTINGS)
        // =========================================================================
        debugContainer = createCard().apply {
            visibility = if (settingsStore.debugModeEnabled) View.VISIBLE else View.GONE

            addView(cardSectionTitle("🛠️ Diagnostic Console"))
            addView(cardSubtitle("Live SingleKey ID communication & daemon schedule logs"))

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
        root.addView(debugContainer)

        scroll.addView(root)
        swipeRefreshLayout.addView(scroll)
        return swipeRefreshLayout
    }

    private fun saveInputsToSettings() {
        settingsStore.sliderTempC = sliderModel.snappedTempC
        settingsStore.targetOperationHours = durationSliderModel.snappedHours
        if (::budgetInput.isInitialized) {
            val budgetVal = budgetInput.text?.toString()?.replace(',', '.')?.toDoubleOrNull()
            settingsStore.dailyBudgetEuro = budgetVal
        }
        if (::powerInput.isInitialized) {
            val raw = powerInput.text?.toString()?.trim()?.replace(',', '.')
            val powerVal = raw?.toDoubleOrNull()
            if (powerVal == null || powerVal <= 0.1) {
                settingsStore.autoPowerModulationEnabled = true
                settingsStore.estimatedPowerKw = 0.7
            } else {
                settingsStore.autoPowerModulationEnabled = false
                settingsStore.estimatedPowerKw = powerVal.coerceIn(0.2, 10.0)
            }
        }
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

            val bgRunning = settingsStore.autoBackgroundEnabled || settingsStore.telemetryCollectionEnabled
            if (bgRunning) {
                bgActiveBadgeText.text = "● Background Active"
                bgActiveBadgeText.setTextColor(Color.parseColor(palette.accentGreen))
                bgActiveBadgeText.background = roundedBg(palette.accentGreenBg, palette.accentGreenStroke, cornerDp = 16)
                BreastNotificationHelper.updateBackgroundActiveNotification(this@MainActivity)
            } else {
                bgActiveBadgeText.text = "○ Background Off"
                bgActiveBadgeText.setTextColor(Color.parseColor(palette.textMuted))
                bgActiveBadgeText.background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 16)
                BreastNotificationHelper.clearBackgroundActiveNotification(this@MainActivity)
            }

            val lastSyncFormatted = syncState.lastSyncTimestampIso
                ?.replace("T", " ")
                ?.substringBefore("+")
                ?.substringBefore(".")
                ?: "Never"

            val currentSetpoint = sliderModel.snappedTempC
            val appliedSetpoint = syncState.lastAppliedSliderTempC
            val setpointModified = appliedSetpoint != null && Math.abs(currentSetpoint - appliedSetpoint) >= 0.1

            if (syncState.manualAdjustmentDetected) {
                manualAdjustmentBanner.visibility = View.VISIBLE
                val hint = syncState.manualAdjustmentHint ?: "manual adjustment detected, no further change"
                manualAdjustmentBanner.text = "⚠ $hint\n(Heat pump schedule was modified on the controller. Auto-sync is paused so your manual settings are preserved. Tap 'Sync Lowest-Price Schedule Now' to override and re-enable auto-sync)."
                lastSyncSubText.text = "Last schedule sync: $lastSyncFormatted  •  ⚠ $hint"
            } else if (setpointModified && (syncState.lastSyncedTodayDate != null || syncState.lastSyncedTomorrowDate != null)) {
                manualAdjustmentBanner.visibility = View.VISIBLE
                manualAdjustmentBanner.text = "ℹ Schedule manually modified (Target: ${currentSetpoint}°C vs applied ${appliedSetpoint}°C). Press 'Sync' again twice to overwrite."
                lastSyncSubText.text = "Last schedule sync: $lastSyncFormatted  •  Target modified (${appliedSetpoint}°C -> ${currentSetpoint}°C)"
            } else {
                if (overwriteConfirmTaps == 0) {
                    manualAdjustmentBanner.visibility = View.GONE
                }
                lastSyncSubText.text = "Last schedule sync: $lastSyncFormatted"
            }

            // Populate Today & Tomorrow visual schedule cards
            if (settingsStore.holisticOptimizationEnabled) {
                val pref = de.breast.optimizer.OptimizationPreference.fromId(settingsStore.optimizationPreference)
                optimizationModeBadge.visibility = View.VISIBLE
                optimizationModeBadge.text = "OPTIMIZATION MODE: ${pref.title.uppercase()} (${pref.subtitle.substringBefore(" — ")})"
            } else {
                optimizationModeBadge.visibility = View.GONE
            }

            todayWindowTitle.text = if (syncState.lastSyncedTodayDate != null) {
                val setpointStr = syncState.lastAppliedSliderTempC?.let { " • Target: ${it}°C" } ?: ""
                "TODAY (${syncState.lastSyncedTodayDate})  •  APPLIED SCHEDULE$setpointStr"
            } else {
                "TODAY'S SCHEDULE"
            }
            todayWindowBody.text = syncState.todayWindowSummary
                ?: "No schedule synced yet today. Tap 'Sync Lowest-Price Schedule Now'."

            tomorrowWindowTitle.text = if (syncState.lastSyncedTomorrowDate != null) {
                val setpointStr = syncState.lastAppliedSliderTempC?.let { " • Target: ${it}°C" } ?: ""
                "TOMORROW (${syncState.lastSyncedTomorrowDate})  •  APPLIED SCHEDULE$setpointStr"
            } else {
                "TOMORROW (DAY-AHEAD)"
            }
            tomorrowWindowBody.text = syncState.tomorrowWindowSummary
                ?: "Spot prices publish daily between 12:00 and 15:00 CET. Auto-sync will push automatically."

            latestSyncState = syncState
            todayPhysicsBtn.visibility = if (!syncState.todayPhysicsBreakdown.isNullOrBlank()) View.VISIBLE else View.GONE
            tomorrowPhysicsBtn.visibility = if (!syncState.tomorrowPhysicsBreakdown.isNullOrBlank()) View.VISIBLE else View.GONE

            val jobActive = SpotScheduleJobService.isHourlyJobScheduled(this@MainActivity)
            val batteryUnrestricted = isIgnoringBatteryOptimizations()
            cronStatusText.text = "Hourly Cron: ${if (jobActive) "Active" else "Paused"}  •  " +
                "Battery: ${if (batteryUnrestricted) "Unrestricted (Optimal)" else "Optimized (May delay background sync)"}"
            batteryBtn.visibility = if (batteryUnrestricted) View.GONE else View.VISIBLE

            if (!syncState.lastRunLog.isNullOrBlank()) {
                logOutputText.text = syncState.lastRunLog
            }

            refreshHeatingCurveAnalysis()
        }
    }

    private fun showPhysicsAuditDialog(title: String, breakdown: String?) {
        if (breakdown.isNullOrBlank()) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("No physical breakdown available yet. Tap 'Sync Lowest-Price Schedule Now' to generate.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val scroll = ScrollView(this).apply {
            setPadding(dp(20), dp(16), dp(20), dp(16))
            val tv = TextView(this@MainActivity).apply {
                text = breakdown
                setTextColor(Color.parseColor(palette.textPrimary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
                setLineSpacing(dp(3).toFloat(), 1.0f)
            }
            addView(tv)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun createTelemetryBadge(defaultValue: String, accentHex: String): TextView {
        return TextView(this).apply {
            text = defaultValue
            setTextColor(Color.parseColor(accentHex))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
            background = roundedBg(palette.badgeBg, palette.badgeStroke, cornerDp = 8)
        }
    }

    private fun wrapBadgeCol(badgeView: TextView): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(3), 0, dp(3), 0)
            }
            addView(badgeView)
        }
    }

    private fun performSwipeToRefresh() {
        lifecycleScope.launch {
            try {
                appendLog("Pull-to-refresh: Updating live telemetry & checking Day-Ahead spot market prices...")

                // 1. Update live telemetry from heat pump
                val tokens = tokenStore.loadTokens()
                var latestSnap: HeatPumpTelemetrySnapshot? = null
                if (tokens != null) {
                    runCatching {
                        val gwId = pointTClient.getGateways().firstOrNull()?.deviceId ?: "101845229"
                        val snap = pointTClient.getHeatPumpTelemetry(gatewayId = gwId)
                        telemetryStore.recordSnapshot(snap)
                        latestSnap = snap
                        updateTelemetryUi(snap)
                        appendLog("Live telemetry updated: Outdoor=${snap.outdoorTempC ?: "?"}°C, Supply=${snap.supplyTempC ?: "?"}°C, Return=${snap.returnTempC ?: "?"}°C, Mod=${snap.modulationPct?.toInt() ?: 0}%")
                    }.onFailure { err ->
                        appendLog("Live telemetry update notice: ${err.message}")
                    }
                } else {
                    appendLog("SingleKey ID not connected. Skipping gateway live telemetry.")
                }

                // 2. Run hourly schedule check & adaptive sync cycle via daemon
                val recentSnap = latestSnap ?: telemetryStore.getRecentSnapshots(limit = 1).lastOrNull()
                val cfg = settingsStore.buildScheduleConfig(averageOutdoorTempC = recentSnap?.outdoorTempC)
                val scheduler = SwitchProgramScheduler(cfg)
                val holisticConfig = if (settingsStore.holisticOptimizationEnabled) {
                    settingsStore.buildHolisticOptimizationConfig(liveStartingReturnTempC = recentSnap?.returnTempC)
                } else null

                val daemon = AutoScheduleDaemon(
                    spotClient = spotClient,
                    pointTClient = pointTClient,
                    scheduler = scheduler,
                    stateStore = stateStore,
                    dryRun = tokens == null,
                    telemetryStore = if (settingsStore.telemetryCollectionEnabled) telemetryStore else null,
                    spotSources = settingsStore.getOrderedSpotSources(),
                    weatherClient = weatherClient,
                    holisticConfig = holisticConfig,
                    settingsStore = settingsStore
                )

                val res = withContext(Dispatchers.IO) {
                    daemon.runHourlyCheckCycle(forcePush = false)
                }
                for (l in res.logs) {
                    appendLog(l)
                }

                if (res.pushedToHeatPump && res.syncedDates.isNotEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Schedule applied for ${res.syncedDates.joinToString(", ")} & telemetry updated.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val statusMsg = if (res.tomorrowPricesAvailable) {
                        "Tomorrow's Day-Ahead schedule up-to-date. Live telemetry logged."
                    } else {
                        "Tomorrow's Day-Ahead prices not yet published (expected 12:00-15:00 CET). Live telemetry logged."
                    }
                    Toast.makeText(this@MainActivity, statusMsg, Toast.LENGTH_SHORT).show()
                }

                refreshHeatingCurveAnalysis()
                refreshUiStatus()
            } catch (e: Exception) {
                appendLog("Refresh error: ${e.message}")
                Toast.makeText(this@MainActivity, "Refresh error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    private fun fetchAndStoreLiveTelemetry() {
        lifecycleScope.launch {
            val tokens = tokenStore.loadTokens()
            if (tokens == null) {
                Toast.makeText(this@MainActivity, "Connect SingleKey ID first", Toast.LENGTH_SHORT).show()
                return@launch
            }
            refreshTelemetryBtn.isEnabled = false
            refreshTelemetryBtn.text = "Fetching..."
            runCatching {
                val gwId = pointTClient.getGateways().firstOrNull()?.deviceId ?: "101845229"
                val snap = pointTClient.getHeatPumpTelemetry(gatewayId = gwId)
                telemetryStore.recordSnapshot(snap)
                updateTelemetryUi(snap)
                refreshHeatingCurveAnalysis()
                Toast.makeText(this@MainActivity, "Telemetry snapshot saved!", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(this@MainActivity, "Telemetry failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
            refreshTelemetryBtn.isEnabled = true
            refreshTelemetryBtn.text = "Refresh Telemetry Now"
        }
    }

    private fun refreshHeatingCurveAnalysis() {
        lifecycleScope.launch {
            val count = telemetryStore.getSnapshotCount()
            val statusSuffix = if (settingsStore.telemetryCollectionEnabled) {
                val m = settingsStore.telemetryIntervalMinutes
                "● Logging (${if (m == 1) "1 min" else if (m == 60) "1 hr" else "${m}m"})"
            } else {
                "○ Paused (Dev Mode)"
            }
            telemetryCountText.text = "$count snapshots logged  •  $statusSuffix"

            val snapshots = telemetryStore.getRecentSnapshots(limit = 1000)
            val latest = snapshots.lastOrNull()
            if (latest != null) {
                updateTelemetryUi(latest)
            }

            val targetTemp = settingsStore.sliderTempC
            val analysis = curveAnalyzer.analyzeHeatingCurve(
                snapshots = snapshots,
                targetSetpointTempC = targetTemp,
                latitude = settingsStore.weatherLatitude,
                longitude = settingsStore.weatherLongitude
            )
            val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            heatingCurveChartView.isDarkMode = isDark
            heatingCurveChartView.targetSetpointTempC = targetTemp
            heatingCurveChartView.snapshots = snapshots
            heatingCurveChartView.empiricalCurve = analysis
            heatingCurveChartView.currentOutdoorTempC = latest?.outdoorTempC
            heatingCurveChartView.currentSupplyTempC = latest?.supplyTempC

            // Synchronize overlay selections and shift from UserSettingsStore
            heatingCurveChartView.showHpcCurve1 = settingsStore.chartShowHpc1
            heatingCurveChartView.showHpcCurve2 = settingsStore.chartShowHpc2
            heatingCurveChartView.showHpcCurve3 = settingsStore.chartShowHpc3
            heatingCurveChartView.showHpcCurve4 = settingsStore.chartShowHpc4
            heatingCurveChartView.showHpcCurve5 = settingsStore.chartShowHpc5
            heatingCurveChartView.showEmpiricalCurve = false // Empirical regression disabled
            heatingCurveChartView.showScatterSteadyState = settingsStore.chartShowScatterSteadyState
            heatingCurveChartView.showScatterWarmup = settingsStore.chartShowScatterWarmup
            heatingCurveChartView.showLivePoint = settingsStore.chartShowLivePoint
            heatingCurveChartView.parallelShiftK = settingsStore.chartParallelShiftK

            val activeCurves = mutableListOf<String>()
            if (settingsStore.chartShowHpc1) activeCurves.add("HPC[1] 45°")
            if (settingsStore.chartShowHpc2) activeCurves.add("HPC[2] 40°")
            if (settingsStore.chartShowHpc3) activeCurves.add("HPC[3] 35° Low-E")
            if (settingsStore.chartShowHpc4) activeCurves.add("HPC[4] 35° FBH")
            if (settingsStore.chartShowHpc5) activeCurves.add("HPC[5] 60° Rad")
            val curvesHeader = if (activeCurves.isEmpty()) "Bosch HPC 410 Curves" else activeCurves.joinToString(", ")
            val shiftStr = if (settingsStore.chartParallelShiftK != 0.0) String.format(" (Shift +%.1f K)", settingsStore.chartParallelShiftK) else " (+0 K Base)"

            if (analysis != null) {
                heatingCurveEquationText.text = "$curvesHeader$shiftStr"
                val coolingText = analysis.hourlyCoolingRateC?.let {
                    String.format("  •  Thermal decay: -%.2f °C/h", it)
                } ?: ""
                val warmupText = analysis.estimatedWarmupMinutes?.let {
                    String.format("  •  Warm-up ramp: ~%.0f min", it)
                } ?: ""
                val filterText = if (analysis.transientSampleCount > 0) {
                    String.format("Telemetry: %d equilibrium pts (%d warm-up ramp pts excluded)",
                        analysis.steadyStateSampleCount, analysis.transientSampleCount)
                } else {
                    String.format("Telemetry: %d steady-state points", analysis.sampleCount)
                }
                heatingCurveEvaluationText.text = String.format(
                    "%s\n%s  •  Avg Heating ΔT: %.1f K%s%s",
                    analysis.evaluationSummary,
                    filterText,
                    analysis.avgHeatingDeltaT,
                    coolingText,
                    warmupText
                )
            } else {
                heatingCurveEquationText.text = if (count > 0) "$curvesHeader$shiftStr ($count logged)" else "Awaiting central heating run data points..."
                heatingCurveEvaluationText.text = "Plots real operating telemetry directly against Bosch HPC 410 factory heating curves (Bild 2 & 4). Tap chart or Fullscreen to customize overlays."
            }
        }
    }

    private fun updateTelemetryUi(snap: HeatPumpTelemetrySnapshot) {
        telemetryOutdoorBadge.text = snap.outdoorTempC?.let { String.format("Out: %.1f°C", it) } ?: "Out: --"
        telemetrySupplyBadge.text = snap.supplyTempC?.let { String.format("Flow: %.1f°C", it) } ?: "Flow: --"
        telemetryReturnBadge.text = snap.returnTempC?.let { String.format("Ret: %.1f°C", it) } ?: "Ret: --"
        telemetryDeltaBadge.text = snap.deltaTempC?.let { String.format("ΔT: %.1fK", it) } ?: "ΔT: --"
        telemetryModulationBadge.text = snap.modulationPct?.let { String.format("Mod: %.0f%%", it) } ?: "Mod: 0%"
        telemetryDemandBadge.text = when {
            snap.isDhwActive -> "DHW"
            snap.isHeatingActive -> "Heating"
            else -> "Idle"
        }
        if (::heatingCurveChartView.isInitialized) {
            heatingCurveChartView.currentOutdoorTempC = snap.outdoorTempC
            heatingCurveChartView.currentSupplyTempC = snap.supplyTempC
        }
    }

    private fun exportTelemetryDialog() {
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

    private fun openFullscreenHeatingCurve() {
        lifecycleScope.launch {
            val latest = telemetryStore.getRecentSnapshots(limit = 1).lastOrNull()
            val intent = Intent(this@MainActivity, HeatingCurveFullscreenActivity::class.java).apply {
                putExtra(HeatingCurveFullscreenActivity.EXTRA_OUTDOOR_TEMP, latest?.outdoorTempC ?: -999.0)
                putExtra(HeatingCurveFullscreenActivity.EXTRA_SUPPLY_TEMP, latest?.supplyTempC ?: -999.0)
                putExtra(HeatingCurveFullscreenActivity.EXTRA_RETURN_TEMP, latest?.returnTempC ?: -999.0)
                putExtra(HeatingCurveFullscreenActivity.EXTRA_SLIDER_TEMP, settingsStore.sliderTempC)
            }
            startActivity(intent)
        }
    }

    private fun handleSyncButtonClick() {
        lifecycleScope.launch {
            saveInputsToSettings()
            val state = stateStore.loadState()
            val currentSetpoint = sliderModel.snappedTempC
            val appliedSetpoint = state.lastAppliedSliderTempC
            val hasAppliedSchedule = (state.lastSyncedTodayDate != null || state.lastSyncedTomorrowDate != null) && appliedSetpoint != null
            val setpointModified = appliedSetpoint != null && Math.abs(currentSetpoint - appliedSetpoint) >= 0.1
            val isManuallyModified = setpointModified || state.manualAdjustmentDetected

            if (hasAppliedSchedule && isManuallyModified) {
                when (overwriteConfirmTaps) {
                    0 -> {
                        overwriteConfirmTaps = 1
                        val hintMsg = "Schedule manually modified, press the button again twice to overwrite the value"
                        manualAdjustmentBanner.visibility = View.VISIBLE
                        manualAdjustmentBanner.text = "⚠ $hintMsg\n(Applied schedule setpoint was ${appliedSetpoint}°C, current is ${currentSetpoint}°C)"
                        syncNowBtn.text = "Press again twice to overwrite (2 remaining)"
                        syncNowBtn.setTextColor(Color.parseColor(palette.accentAmber))
                        syncNowBtn.background = roundedBg(palette.accentAmberBg, palette.accentAmberStroke, cornerDp = 8)
                        Toast.makeText(this@MainActivity, hintMsg, Toast.LENGTH_LONG).show()

                        overwriteResetJob?.cancel()
                        overwriteResetJob = lifecycleScope.launch {
                            delay(10_000L)
                            resetOverwriteConfirmation()
                        }
                    }
                    1 -> {
                        overwriteConfirmTaps = 2
                        val hintMsg = "Schedule manually modified, press once more to overwrite the value"
                        manualAdjustmentBanner.visibility = View.VISIBLE
                        manualAdjustmentBanner.text = "⚠ $hintMsg"
                        syncNowBtn.text = "Press once more to overwrite (1 remaining)"
                        Toast.makeText(this@MainActivity, hintMsg, Toast.LENGTH_SHORT).show()

                        overwriteResetJob?.cancel()
                        overwriteResetJob = lifecycleScope.launch {
                            delay(10_000L)
                            resetOverwriteConfirmation()
                        }
                    }
                    else -> {
                        // User pressed again twice: overwrite confirmed!
                        resetOverwriteConfirmation()
                        manualAdjustmentBanner.visibility = View.GONE
                        Toast.makeText(this@MainActivity, "Overwriting schedule with ${currentSetpoint}°C...", Toast.LENGTH_SHORT).show()
                        runManualScheduleSync(forcePush = true)
                    }
                }
            } else {
                resetOverwriteConfirmation()
                runManualScheduleSync(forcePush = true)
            }
        }
    }

    private fun resetOverwriteConfirmation() {
        overwriteConfirmTaps = 0
        overwriteResetJob?.cancel()
        overwriteResetJob = null
        syncNowBtn.text = "Sync Lowest-Price Schedule Now"
        syncNowBtn.setTextColor(Color.parseColor(palette.primaryBtnText))
        syncNowBtn.background = roundedBg(palette.primaryBtnBg, palette.primaryBtnStroke, cornerDp = 8)
    }

    private fun runManualScheduleSync(forcePush: Boolean) {
        lifecycleScope.launch {
            saveInputsToSettings()
            val hasToken = tokenStore.loadTokens() != null
            val recentSnap = telemetryStore.getRecentSnapshots(limit = 1).lastOrNull()
            val outdoorTempC = recentSnap?.outdoorTempC
            val cfg = settingsStore.buildScheduleConfig(averageOutdoorTempC = outdoorTempC)
            val scheduler = SwitchProgramScheduler(cfg)

            if (forcePush) {
                stateStore.saveState(
                    stateStore.loadState().copy(
                        lastAppliedSliderTempC = null,
                        lastSyncedTodayDate = null,
                        lastSyncedTomorrowDate = null,
                        lastAppliedSettingsSignature = null,
                        manualAdjustmentDetected = false,
                        manualAdjustmentHint = null
                    )
                )
            }

            val holisticConfig = if (settingsStore.holisticOptimizationEnabled) {
                settingsStore.buildHolisticOptimizationConfig()
            } else null

            val daemon = AutoScheduleDaemon(
                spotClient = spotClient,
                pointTClient = pointTClient,
                scheduler = scheduler,
                stateStore = stateStore,
                dryRun = !hasToken,
                telemetryStore = if (settingsStore.telemetryCollectionEnabled) telemetryStore else null,
                spotSources = settingsStore.getOrderedSpotSources(),
                weatherClient = weatherClient,
                holisticConfig = holisticConfig,
                settingsStore = settingsStore
            )

            syncNowBtn.isEnabled = false
            syncNowBtn.text = "Syncing Schedule with Heat Pump..."

            val prefNote = holisticConfig?.preference?.title?.let { " [$it]" } ?: ""
            appendLog(
                String.format(
                    "Fetching Vattenfall / EPEX Spot DE-LU Day-Ahead prices & computing holistic%s schedule...",
                    prefNote
                )
            )
            runCatching {
                val res = daemon.runHourlyCheckCycle(forcePush = forcePush)
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
            resetOverwriteConfirmation()
        }
    }

    private fun readCurrentHeatPumpSchedule(targetView: TextView? = null) {
        lifecycleScope.launch {
            if (tokenStore.loadTokens() == null) {
                val msg = "Please login with Bosch SingleKey ID first to read gateway schedules."
                targetView?.text = msg
                appendLog(msg)
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
                sb.appendLine("\n--- Space Heating (hc1) SwitchProgram [$hcName] ---")
                val days = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
                val hcByDay = hcProg?.switchPoints.orEmpty().groupBy { it.dayOfWeek }
                val dhwByDay = dhwProg?.switchPoints.orEmpty().groupBy { it.dayOfWeek }

                for (d in days) {
                    val hcPts = hcByDay[d].orEmpty()
                    val dhwPts = dhwByDay[d].orEmpty()
                    val hcStr = if (hcPts.isEmpty()) "Setback (eco)" else hcPts.joinToString(", ") { "${it.formatTimeHm()} -> ${it.setpoint}" }
                    val dhwStr = if (dhwPts.isEmpty()) "" else " | DHW: " + dhwPts.joinToString(", ") { "${it.formatTimeHm()} -> ${it.setpoint}" }
                    sb.appendLine("  $d: $hcStr$dhwStr")
                }

                sb.appendLine("\n--- Warm Water (dhw1) SwitchProgram [$dhwName] ---")
                for (d in days) {
                    val dhwPts = dhwByDay[d].orEmpty()
                    val dhwStr = if (dhwPts.isEmpty()) "off" else dhwPts.joinToString(", ") { "${it.formatTimeHm()} -> ${it.setpoint}" }
                    sb.appendLine("  $d: $dhwStr")
                }

                val outStr = sb.toString()
                if (targetView != null) {
                    targetView.text = outStr
                }
                logOutputText.text = outStr
            }.onFailure { e ->
                val err = "Failed to read schedule from gateway: ${e.message}"
                targetView?.text = err
                appendLog(err)
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
            isSingleLine = true
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = roundedBg(palette.inputBg, palette.inputStroke, cornerDp = 10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
            )
            setText(initialText)

            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    v.clearFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(v.windowToken, 0)
                    saveInputsToSettings()
                    true
                } else {
                    false
                }
            }
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
