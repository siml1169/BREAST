package de.breast.ui

import de.breast.auth.AppLanguage

/**
 * Centralized, type-safe internationalization (i18n) provider for BREAST Heatpump
 * (Bosch Reverse Engineered API Smart Tool for Heatpump).
 * Supports English (en), German (de), and Chinese (zh).
 */
data class AppStrings(
    val appTitle: String,
    val appSubtitle: String,
    val settingsTitle: String,
    val done: String,
    val save: String,
    val cancel: String,
    val editNameTitle: String,
    val editNamePrompt: String,
    val defaultHeatPumpName: String,
    val subtitleHeader: String,
    val connected: String,
    val loginNeeded: String,
    val checking: String,
    val bgActive: String,
    val bgInactive: String,
    val lastSyncNever: String,
    val lastSyncPrefix: String,
    val connectSingleKey: String,
    val reconnectSingleKey: String,
    val scheduledWindowsTitle: String,
    val scheduledWindowsSubtitle: String,
    val manualAdjustmentDetected: String,
    val manualAdjustmentDetail: String,
    val optModePrefix: String,
    val modeComfort: String,
    val modeBalanced: String,
    val modeEfficiency: String,
    val modeBudget: String,
    val todayAppliedTitle: String,
    val todayDefaultTitle: String,
    val todayNoSchedule: String,
    val tomorrowScheduledTitle: String,
    val tomorrowDayAheadTitle: String,
    val tomorrowNotice: String,
    val viewAuditBtn: String,
    val syncScheduleBtn: String,
    val inspectScheduleBtn: String,
    val hideScheduleBtn: String,
    val weeklyScheduleHeader: String,
    val readingScheduleText: String,
    val tapToInspect: String,
    val telemetryTitle: String,
    val telemetrySubtitle: String,
    val badgeOutdoor: String,
    val badgeSupply: String,
    val badgeReturn: String,
    val badgeDelta: String,
    val badgeMod: String,
    val badgeDemand: String,
    val curveCardTitle: String,
    val fullscreenBtn: String,
    val awaitingRunData: String,
    val curveEvaluationDesc: String,
    val snapshotsCountFormat: String,
    val refreshTelemetryBtn: String,
    val exportCsvBtn: String,
    val comfortTitle: String,
    val comfortSubtitle: String,
    val heatingTargetLabel: String,
    val heatingTargetDesc: String,
    val runtimeTargetLabel: String,
    val budgetTitle: String,
    val budgetSubtitle: String,
    val dailyBudgetLabel: String,
    val noLimit: String,
    val powerModelLabel: String,
    val powerHint: String,
    val budgetExplanation: String,
    val autoTitle: String,
    val autoSubtitle: String,
    val autoSwitchLabel: String,
    val cronStatusFormat: String,
    val cronActive: String,
    val cronPaused: String,
    val batteryUnrestricted: String,
    val batteryOptimized: String,
    val reqBatteryBtn: String,
    val languageSectionTitle: String,
    val languageSectionSubtitle: String,
    val tariffSectionTitle: String,
    val tariffSectionSubtitle: String,
    val dynamicSpotModel: String,
    val touModel: String,
    val flatRateModel: String,
    val baseWorkingPriceLabel: String,
    val baseWorkingPriceSub: String,
    val monthlyBaseFeeLabel: String,
    val monthlyBaseFeeSub: String,
    val vatLabel: String,
    val vatSub: String,
    val contractFormulaTitle: String,
    val peakPriceLabel: String,
    val peakPriceSub: String,
    val offPeakPriceLabel: String,
    val offPeakPriceSub: String,
    val offPeakHoursLabel: String,
    val weekendOffPeakLabel: String,
    val touScheduleTitle: String,
    val flatRateLabel: String,
    val flatRateSub: String,
    val sourcesSectionTitle: String,
    val sourcesSectionSubtitle: String,
    val testSourcesBtn: String,
    val primaryLabel: String,
    val setPrimaryLabel: String,
    val telemetryRecSectionTitle: String,
    val telemetryRecSectionSubtitle: String,
    val recordTelemetrySwitch: String,
    val samplingFreqLabel: String,
    val freqEveryMinute: String,
    val freqEveryMinutes: String,
    val freqEveryHour: String,
    val clearDbBtn: String,
    val clearDbTitle: String,
    val clearDbMessage: String,
    val clearAllBtn: String,
    val devCardTitle: String,
    val devCardSubtitle: String,
    val debugModeSwitch: String,
    val debugModeDesc: String,
    val inspectRawScheduleBtn: String,
    val holisticSectionTitle: String,
    val holisticSectionSubtitle: String,
    val holisticSwitchLabel: String,
    val holisticDesc: String,
    val optPreferenceLabel: String,
    val prefBalancedSub: String,
    val prefComfortSub: String,
    val prefBudgetSub: String,
    val prefEfficiencySub: String,
    val screedBufferLabel: String,
    val heatLossLabel: String,
    val setbackStatusDefault: String,
    val analyzeSetbackBtn: String,
    val applySetbackBtnFormat: String,
    val modStatusDefault: String,
    val analyzeModulationBtn: String,
    val applyModulationBtnFormat: String,
    val parasiticSectionTitle: String,
    val parasiticDesc: String,
    val externalSubmeterLabel: String,
    val submeterHint: String,
    val submeterDefaultBadge: String,
    val submeterCalibratedBadgeFormat: String,
    val testWeatherBtn: String,
    val todayPhysicsAuditTitle: String,
    val tomorrowPhysicsAuditTitle: String,
    val noPhysicsAuditMessage: String,
    val close: String,
    val overwriteHintTwice: String,
    val overwriteHintOnce: String,
    val overwriteBtnTwice: String,
    val overwriteBtnOnce: String,
    val overwritingToast: String,
    val syncingScheduleToast: String,
    val contractFormulaDetail: String,
    val touSummaryDetail: String,
    val touStatusOffPeak: String,
    val touStatusPeak: String,
    val touWeekendAllDay: String,
    val touWeekendStandard: String,
    val flatRateNoteTitle: String,
    val flatRateNoteDesc: String,
    val scanningSetbackMsg: String,
    val setbackCompleteMsg: String,
    val noSetbackCooldownMsg: String,
    val scanningModulationMsg: String,
    val modCalibratedMsg: String,
    val noHeatingRunsMsg: String,
    val telemetryDefaultDesc: String,
    val telemetryShiftKFormat: String,
    val telemetryShiftBase: String,
    val telemetryLoggedCountFormat: String,
    val telemetryEquilibriumFormat: String,
    val telemetrySteadyStateFormat: String,
    val telemetryAvgDeltaTFormat: String,
    val telemetryThermalDecayFormat: String,
    val telemetryWarmupRampFormat: String,
    val modeDhw: String,
    val modeHeating: String,
    val modeIdle: String,
    val cascadeActivePlanFormat: String,
    val cascadePriorityTitle: String,
    val cascadePrimaryRole: String,
    val cascadeFallbackRoleFormat: String,
    val cascadeDelayedLine: String,
    val cascadeStatusReady: String,
    val cascadeStatusChecking: String,
    val cascadeStatusOnlineFormat: String,
    val anchorActiveBadge: String,
    val blockSingle: String,
    val blockPlural: String,
    val chipHeatLossRate: String,
    val chipWeightedConfidence: String,
    val chipCoolingRate: String,
    val chipTempDropFormat: String,
    val chipOutdoorTemp: String,
    val chipThermalMass: String,
    val chipScreedCore: String,
    val nuanceBoxTitle: String,
    val nuanceSetbackPriorFormat: String,
    val nuanceSetbackWindow: String,
    val nuanceSolarGate: String,
    val nuanceQuiescence: String,
    val nuanceMassCouplingFormat: String,
    val nuanceMultiNight: String,
    val badgeTargetFormat: String,
    val badgeRunsCountFormat: String,
    val chipBaselineMod: String,
    val chipInverterFreq: String,
    val chipSensitivity: String,
    val chipColderSens: String,
    val chipSteadyPower: String,
    val chipShaftPump: String,
    val chipLoopSpread: String,
    val chipHydraulicLift: String,
    val nuanceModPriorFormat: String,
    val nuanceModWindow: String,
    val nuancePidStartupFormat: String,
    val nuanceObservedCorridorFormat: String,
    val nuanceInverterClamping: String,
    val projectionTableTitle: String,
    val projectionFloorBadge: String,
    val weatherLocationFormat: String,
    val weatherFetchingMsg: String,
    val weatherSuccessFormat: String,
    val weatherOfflineMsg: String
)

data class NotificationStrings(
    val channelAuthName: String,
    val channelAuthDesc: String,
    val channelSyncName: String,
    val channelSyncDesc: String,
    val channelBgName: String,
    val channelBgDesc: String,
    val authAlertTitle: String,
    val authAlertTapPrompt: String,
    val syncStatusTitle: String,
    val syncStatusSummaryFormat: String,
    val bgTitleTelemetryAndAuto: String,
    val bgContentTelemetryAndAuto: String,
    val bgBigTextTelemetryAndAuto: String,
    val bgTitleTelemetryOnly: String,
    val bgContentTelemetryOnly: String,
    val bgBigTextTelemetryOnly: String,
    val bgTitleSyncOnly: String,
    val bgContentSyncOnly: String,
    val bgBigTextSyncOnly: String,
    val interval1Min: String,
    val interval1Hour: String,
    val intervalNMinFormat: String
)

object BreastI18n {
    private val EN = AppStrings(
        appTitle = "BREAST Heatpump",
        appSubtitle = "Autonomous Day-Ahead Spot Optimizer",
        settingsTitle = "Settings",
        done = "← Done",
        save = "Save",
        cancel = "Cancel",
        editNameTitle = "Name Your Heat Pump",
        editNamePrompt = "Enter a custom name for this heat pump appliance:",
        defaultHeatPumpName = "Smart Heat Pump",
        subtitleHeader = "EPEX Spot DE-LU  •  Bosch K30",
        connected = "● Connected",
        loginNeeded = "● Login Needed",
        checking = "● Checking...",
        bgActive = "● Background Active",
        bgInactive = "○ Background Off",
        lastSyncNever = "Last sync: Never",
        lastSyncPrefix = "Last schedule sync: %s",
        connectSingleKey = "Connect Bosch SingleKey ID",
        reconnectSingleKey = "Reconnect Bosch SingleKey ID",
        scheduledWindowsTitle = "Scheduled Lowest-Price Windows",
        scheduledWindowsSubtitle = "Shared single contiguous window for Space Heating (HC1) & Warm Water (DHW1)",
        manualAdjustmentDetected = "⚠ manual adjustment detected, no further change",
        manualAdjustmentDetail = "Heat pump schedule was modified on the controller. Auto-sync is paused so your manual settings are preserved. Tap 'Sync Lowest-Price Schedule Now' to override and re-enable auto-sync.",
        optModePrefix = "OPTIMIZATION MODE: %s",
        modeComfort = "COMFORT",
        modeBalanced = "BALANCED",
        modeEfficiency = "EFFICIENCY",
        modeBudget = "BUDGET",
        todayAppliedTitle = "TODAY (%s)  •  APPLIED SCHEDULE • Target: %s",
        todayDefaultTitle = "TODAY'S SCHEDULE",
        todayNoSchedule = "No schedule synced yet today. Tap 'Sync Lowest-Price Schedule Now'.",
        tomorrowScheduledTitle = "TOMORROW (%s)  •  SCHEDULED",
        tomorrowDayAheadTitle = "TOMORROW (DAY-AHEAD)",
        tomorrowNotice = "Spot prices publish daily between 12:00 and 15:00 CET. Auto-sync will push automatically.",
        viewAuditBtn = "🔬 View Physics & Thermodynamic Audit",
        syncScheduleBtn = "Sync Lowest-Price Schedule Now",
        inspectScheduleBtn = "Inspect 7-Day Heat Pump Schedule",
        hideScheduleBtn = "Hide 7-Day Heat Pump Schedule",
        weeklyScheduleHeader = "ACTIVE 7-DAY HEAT PUMP SWITCH PROGRAM",
        readingScheduleText = "Reading 7-day schedule directly from Bosch gateway...",
        tapToInspect = "Tap to inspect.",
        telemetryTitle = "Live Telemetry & Real Heating Curve",
        telemetrySubtitle = "Empirical flow curve extrapolation & full-day thermal profile",
        badgeOutdoor = "Out: %s",
        badgeSupply = "Flow: %s",
        badgeReturn = "Ret: %s",
        badgeDelta = "ΔT: %s",
        badgeMod = "Mod: %s",
        badgeDemand = "Mode: %s",
        curveCardTitle = "HEATING CURVE & OPERATING TELEMETRY (BOSCH HPC 410)",
        fullscreenBtn = "⛶ Fullscreen",
        awaitingRunData = "Awaiting central heating run data points...",
        curveEvaluationDesc = "Collects steady-state data points when central heating runs to learn your true building heat curve and compare against Bosch documentation.",
        snapshotsCountFormat = "%d snapshots logged in local database",
        refreshTelemetryBtn = "Refresh Telemetry",
        exportCsvBtn = "Export CSV",
        comfortTitle = "Comfort & Runtime Controls",
        comfortSubtitle = "Adjust target room comfort temperature and daily operating duration",
        heatingTargetLabel = "Heating Target Temperature",
        heatingTargetDesc = "Applies to Space Heating (HC1 comfort2). Setback (17°C) & Warm Water (42°C) remain untouched.",
        runtimeTargetLabel = "Target Daily Runtime",
        budgetTitle = "Daily Budget & Heat Pump Power",
        budgetSubtitle = "Economic constraints and electrical power consumption for cost calculation",
        dailyBudgetLabel = "Daily Budget (€/day)",
        noLimit = "No limit",
        powerModelLabel = "Power Model / Draw (kW)",
        powerHint = "Auto (~0.6kW)",
        budgetExplanation = "• Hard Budget Cap: If set, the optimizer restricts runtime so daily electricity spend never exceeds this limit.\n• Avg Power Draw: Used to multiply dynamic spot tariff rates (€/kWh) into actual daily euro expenditure.",
        autoTitle = "Background Automation & EPEX Spot Sync",
        autoSubtitle = "Hourly daemon pulls EPEX spot prices (12:00–15:00 CET) and pushes schedules",
        autoSwitchLabel = "Automatic Background Sync (Hourly)",
        cronStatusFormat = "Hourly Cron: %s  •  Battery: %s",
        cronActive = "Active",
        cronPaused = "Paused",
        batteryUnrestricted = "Unrestricted (Optimal)",
        batteryOptimized = "Optimized (May delay background sync)",
        reqBatteryBtn = "Grant Unrestricted Battery (Recommended)",
        languageSectionTitle = "🌐 Display Language / Sprache / 语言",
        languageSectionSubtitle = "Choose the display language for interface controls, labels, and telemetry cards.",
        tariffSectionTitle = "⚡ Electricity Tariff & Compositor",
        tariffSectionSubtitle = "Customize tariff components (VAT, grid fees, supplier margin) or switch to a simplified Peak / Off-Peak (ToU) timetable or Flat-rate contract.",
        dynamicSpotModel = "Dynamic Spot",
        touModel = "Peak / Off-Peak",
        flatRateModel = "Flat Rate",
        baseWorkingPriceLabel = "Base Working Price (Fester Arbeitspreis)",
        baseWorkingPriceSub = "Non-spot component printed on your contract letter (Netzentgelte, Steuern, Umlagen & Vertrieb).",
        monthlyBaseFeeLabel = "Monthly Base Fee (Grundpreis)",
        monthlyBaseFeeSub = "Standing monthly charge printed on your utility confirmation letter.",
        vatLabel = "Value Added Tax (VAT / MwSt)",
        vatSub = "Standard electricity sales tax in your jurisdiction (DE 19%, AT 20%, Solar 0%).",
        contractFormulaTitle = "📄 Utility Contract Letter Formula",
        peakPriceLabel = "Peak Price (Hochtarif / HT)",
        peakPriceSub = "Daytime rate from your dual-tariff utility contract letter.",
        offPeakPriceLabel = "Off-Peak Price (Niedertarif / NT)",
        offPeakPriceSub = "Nighttime discounted rate from your utility contract letter.",
        offPeakHoursLabel = "Off-Peak Hours (Night):",
        weekendOffPeakLabel = "All-Day Off-Peak on Weekends (Sat/Sun)",
        touScheduleTitle = "🕒 Time-of-Use Schedule",
        flatRateLabel = "Flat Electricity Rate (Festpreis)",
        flatRateSub = "Fixed all-in price per kWh from your utility contract letter.",
        sourcesSectionTitle = "🌐 Spot Market Data Sources",
        sourcesSectionSubtitle = "Exchange data providers for dynamic spot market pricing. Automatically queried when Dynamic Spot pricing is active.",
        testSourcesBtn = "Test Price Sources Now",
        primaryLabel = "★ Primary",
        setPrimaryLabel = "Set Primary",
        telemetryRecSectionTitle = "📈 Live Telemetry & Heating Curve",
        telemetryRecSectionSubtitle = "Record heat pump outdoor, supply, return temperatures and modulation into SQLite database to empirically learn building thermal properties.",
        recordTelemetrySwitch = "Record Telemetry in Background",
        samplingFreqLabel = "Sampling Frequency",
        freqEveryMinute = "Every 1 min",
        freqEveryMinutes = "Every %d min",
        freqEveryHour = "Every 1 hour",
        clearDbBtn = "Clear Database",
        clearDbTitle = "Clear Telemetry Database?",
        clearDbMessage = "This will permanently delete all recorded temperature snapshots and learned steady-state data points.",
        clearAllBtn = "Clear All",
        devCardTitle = "🛠️ Developer & Diagnostics",
        devCardSubtitle = "Advanced diagnostic logging, API payload inspection, and test controls.",
        debugModeSwitch = "Developer / Debug Mode",
        debugModeDesc = "When enabled, live single-key communication logs and telemetry JSON output are rendered in a monospace terminal card at the bottom of the main dashboard.",
        inspectRawScheduleBtn = "Inspect Raw Heat Pump Schedule & Endpoints",
        holisticSectionTitle = "☀️ Holistic Thermodynamic & Energy Optimization",
        holisticSectionSubtitle = "Optimizes for delivered heat cost (c_elec / COP), captures the midday solar Carnot peak, and calculates dynamic daily thermal energy demand.",
        holisticSwitchLabel = "Holistic Multi-Criteria Optimization",
        holisticDesc = "Replaces rigid hour budgets with dynamic daily kWh demand. Runs during solar noon and 0-cent price windows where COP is highest.",
        optPreferenceLabel = "Optimization Preference",
        prefBalancedSub = "Optimal balance between electricity price savings and steady screed temperature",
        prefComfortSub = "Maximum room comfort; runs extended hours even at slightly higher spot rates",
        prefBudgetSub = "Strict cost adherence; operates exclusively during lowest-cost hours",
        prefEfficiencySub = "Prioritizes high COP solar hours and steep price valleys",
        screedBufferLabel = "Screed Pre-Heat Buffering Limit",
        heatLossLabel = "Building Heat Loss Estimate",
        setbackStatusDefault = "Auto-derive building heat loss from return temperature cooldown during night setback.",
        analyzeSetbackBtn = "Analyze Night Setback Telemetry",
        applySetbackBtnFormat = "Apply %d W/K to Heat Loss Setting",
        modStatusDefault = "Inverter Modulation Model: %s%% @ 10°C (+%s%%/K colder)",
        analyzeModulationBtn = "Analyze Inverter Modulation Telemetry",
        applyModulationBtnFormat = "Apply Model (%s%% @ 10°C, +%s%%/K)",
        parasiticSectionTitle = "Continuous Parasitic Load & Submeter",
        parasiticDesc = "Accounts for secondary underfloor heating pump (~25W) and control boards (~15W) across active and setback hours.",
        externalSubmeterLabel = "External Physical Submeter Installed (e.g. Shelly 3EM)",
        submeterHint = "ℹ Note: Direct meter communication (Wi-Fi/API) is not implemented. When turned ON, the optimizer overrides the ~40W theoretical default with your calibrated slider value below.",
        submeterDefaultBadge = "~40 W (0.96 kWh/d) • Default",
        submeterCalibratedBadgeFormat = "%d W (%.2f kWh/d) • Active",
        testWeatherBtn = "Test Weather Forecast Feed",
        todayPhysicsAuditTitle = "Today's Schedule Physics Audit",
        tomorrowPhysicsAuditTitle = "Tomorrow's Schedule Physics Audit",
        noPhysicsAuditMessage = "No physical breakdown available yet. Tap 'Sync Lowest-Price Schedule Now' to generate.",
        close = "Close",
        overwriteHintTwice = "Schedule manually modified, press the button again twice to overwrite the value",
        overwriteHintOnce = "Schedule manually modified, press once more to overwrite the value",
        overwriteBtnTwice = "Press again twice to overwrite (2 remaining)",
        overwriteBtnOnce = "Press once more to overwrite (1 remaining)",
        overwritingToast = "Overwriting schedule with %s°C...",
        syncingScheduleToast = "Syncing Schedule with Heat Pump...",
        contractFormulaDetail = "Gross Price = (Spot_net + %.2f ct Working Price) × %.2f (%.1f%% VAT)\n• Contract Floor Price (at 0 ct Spot): %.2f ct/kWh gross\n• Sample Delivered Rate (at 5.0 ct Spot): %.2f ct/kWh gross\n• Monthly Base Fee: %.2f €/month",
        touSummaryDetail = "• Current Status: %s (%.1f ct/kWh)\n• Night Off-Peak Window: %02d:00 → %02d:00 (%dh duration)\n• Weekend Off-Peak: %s\n• Peak Surcharge: +%.1f ct/kWh (%.0f%% higher than off-peak)\n• Scheduler shifts heat pump run into off-peak hours automatically.",
        touStatusOffPeak = "🟢 NT Off-Peak Active",
        touStatusPeak = "🟠 HT Peak Active",
        touWeekendAllDay = "Included (24h)",
        touWeekendStandard = "Standard hours",
        flatRateNoteTitle = "💡 Flat-Rate Optimization Note",
        flatRateNoteDesc = "Under a fixed flat-rate tariff, the MPC scheduler optimizes heat pump operation based purely on outdoor temperature Carnot lift, daytime solar radiation, and floor screed thermal buffering.",
        scanningSetbackMsg = "Scanning up to 25,000 telemetry points for nocturnal setback cooling (≥2h after sunset until sunrise)...",
        setbackCompleteMsg = "✓ Nocturnal Setback Analysis Complete (25k Data Window)",
        noSetbackCooldownMsg = "No valid nocturnal setback cooldown found (requires warm floor discharging ≥2h after sunset with compressor OFF). Leave heat pump in setback tonight to measure.",
        scanningModulationMsg = "Scanning up to 25,000 telemetry points for steady-state compressor modulation...",
        modCalibratedMsg = "✓ Inverter Modulation PID Model Calibrated (25k Data Window)",
        noHeatingRunsMsg = "No active heating runs found matching target setpoint %s°C (≥5 needed). Run heating to collect telemetry.",
        telemetryDefaultDesc = "Plots real operating telemetry directly against Bosch HPC 410 factory heating curves (Bild 2 & 4). Tap chart or Fullscreen to customize overlays.",
        telemetryShiftKFormat = " (Shift +%.1f K)",
        telemetryShiftBase = " (+0 K Base)",
        telemetryLoggedCountFormat = " (%d logged)",
        telemetryEquilibriumFormat = "Telemetry: %d equilibrium pts (%d warm-up ramp pts excluded)",
        telemetrySteadyStateFormat = "Telemetry: %d steady-state points",
        telemetryAvgDeltaTFormat = "Avg Heating ΔT: %.1f K",
        telemetryThermalDecayFormat = "  •  Thermal decay: -%.2f °C/h",
        telemetryWarmupRampFormat = "  •  Warm-up ramp: ~%.0f min",
        modeDhw = "DHW",
        modeHeating = "Heating",
        modeIdle = "Idle",
        cascadeActivePlanFormat = "Active Plan: %s\n(Synthetic slots compiled directly from timetable; external exchange fallback bypassed)",
        cascadePriorityTitle = "Priority Cascade:\n",
        cascadePrimaryRole = "Primary",
        cascadeFallbackRoleFormat = "Fallback %d",
        cascadeDelayedLine = "\n  ↳ If delayed/offline: ",
        cascadeStatusReady = "Ready",
        cascadeStatusChecking = "Checking...",
        cascadeStatusOnlineFormat = "✓ Online (%d slots, %dms)",
        anchorActiveBadge = "⚓ 25k Anchor Active",
        blockSingle = "%d Block",
        blockPlural = "%d Blocks",
        chipHeatLossRate = "Heat Loss Rate",
        chipWeightedConfidence = "Weighted Confidence",
        chipCoolingRate = "Cooling Rate",
        chipTempDropFormat = "Drop: %.1f K",
        chipOutdoorTemp = "Outdoor Temp",
        chipThermalMass = "Thermal Mass",
        chipScreedCore = "Screed Core C_eff",
        nuanceBoxTitle = "Process Nuances & Physical Adjustments",
        nuanceSetbackPriorFormat = "• 25k Historical Anchor: Blended cumulative historical baseline (%d W/K, weight=%d) with %d active observation block(s).",
        nuanceSetbackWindow = "• 25k Data Window: Scanning up to 25,000 points; established initial multi-night building envelope baseline.",
        nuanceSolarGate = "• Solar Insolation Gate: Excluded daylight & dusk; strictly ≥2h post-sunset to sunrise to eliminate solar radiation lag.",
        nuanceQuiescence = "• System Quiescence: Verified compressor 0% modulation, heating & DHW idle, loop spread |Tsup - Tret| ≤ 1.5 K.",
        nuanceMassCouplingFormat = "• 2R2C State-Space Mass Coupling: Evaluated core discharge factor α (%.1f kWh/K dynamic participating capacity).",
        nuanceMultiNight = "• Multi-Night Bridging: Seamlessly bridged across calendar midnight; confidence-weighted by R² × duration.",
        badgeTargetFormat = "Target: %.1f°C",
        badgeRunsCountFormat = "%d Runs (%d pts)",
        chipBaselineMod = "Baseline Mod",
        chipInverterFreq = "Inverter Frequency",
        chipSensitivity = "Sensitivity",
        chipColderSens = "Colder Sensitivity",
        chipSteadyPower = "Steady Power",
        chipShaftPump = "Shaft + 50W Pump",
        chipLoopSpread = "Loop Spread",
        chipHydraulicLift = "Hydraulic Lift",
        nuanceModPriorFormat = "• 25k Historical Anchor: Incorporated cumulative 25,000-point historical baseline (%.1f%% @ 10°C, weight=%d) into new regression to preserve long-term multi-seasonal learning.",
        nuanceModWindow = "• 25k Data Window: Scanning up to 25,000 points; initializing cumulative 25k historical anchor.",
        nuancePidStartupFormat = "• PID Startup Transient Isolated: Separated %d warmup samples (avg %s during 20m ramp-up) from steady state (+%d%% PID overshoot).",
        nuanceObservedCorridorFormat = "• Observed Outdoor Corridor: Telemetry observed between %.1f°C and %.1f°C; raw modulation %.0f%% to %.0f%%.",
        nuanceInverterClamping = "• Inverter Clamping & Baseline: Enforced 28.0% hardware minimum frequency clamp & 50 W continuous Wilo circulation load.",
        projectionTableTitle = "Dynamic Temperature Operating Corridor",
        projectionFloorBadge = "(Floor)",
        weatherLocationFormat = "Open-Meteo DWD ICON: Lat %.2f°N, Lon %.2f°E",
        weatherFetchingMsg = "Fetching 48h DWD ICON forecast...",
        weatherSuccessFormat = "✓ Weather OK: %dh forecast (Min: %.1f°C, Max: %.1f°C, Peak Solar: %d W/m²)",
        weatherOfflineMsg = "✗ Weather offline: using synthetic diurnal curve fallback"
    )

    private val DE = AppStrings(
        appTitle = "BREAST Wärmepumpe",
        appSubtitle = "Autonome Börsenstrom-Heizungsoptimierung",
        settingsTitle = "Einstellungen",
        done = "← Fertig",
        save = "Speichern",
        cancel = "Abbrechen",
        editNameTitle = "Wärmepumpenname festlegen",
        editNamePrompt = "Geben Sie einen individuellen Namen für Ihre Wärmepumpe ein:",
        defaultHeatPumpName = "Smarte Wärmepumpe",
        subtitleHeader = "EPEX Spot DE-LU  •  Bosch K30",
        connected = "● Verbunden",
        loginNeeded = "● Login nötig",
        checking = "● Prüfe...",
        bgActive = "● Hintergrund aktiv",
        bgInactive = "○ Hintergrund aus",
        lastSyncNever = "Letzter Sync: Nie",
        lastSyncPrefix = "Letzter Zeitplan-Sync: %s",
        connectSingleKey = "Bosch SingleKey ID verbinden",
        reconnectSingleKey = "Bosch SingleKey ID erneut verbinden",
        scheduledWindowsTitle = "Günstigste Schaltzeitfenster",
        scheduledWindowsSubtitle = "Gemeinsames zusammenhängendes Fenster für Heizung (HC1) & Warmwasser (DHW1)",
        manualAdjustmentDetected = "⚠ Manuelle Anpassung erkannt, Auto-Sync pausiert",
        manualAdjustmentDetail = "Der Heizplan wurde am Regler geändert. Auto-Sync ist pausiert, um manuelle Einstellungen zu erhalten. Tippen Sie auf 'Jetzt synchronisieren', um zu überschreiben.",
        optModePrefix = "OPTIMIERUNGSMODUS: %s",
        modeComfort = "KOMFORT",
        modeBalanced = "AUSGEWOGEN",
        modeEfficiency = "EFFIZIENZ",
        modeBudget = "BUDGET",
        todayAppliedTitle = "HEUTE (%s)  •  AKTIVER ZEITPLAN • Ziel: %s",
        todayDefaultTitle = "HEUTIGER ZEITPLAN",
        todayNoSchedule = "Heute noch kein Zeitplan synchronisiert. Tippen Sie auf 'Jetzt synchronisieren'.",
        tomorrowScheduledTitle = "MORGEN (%s)  •  GEPLANT",
        tomorrowDayAheadTitle = "MORGEN (DAY-AHEAD)",
        tomorrowNotice = "Börsenpreise erscheinen täglich zwischen 12:00 und 15:00 Uhr CET. Der Auto-Sync übernimmt dies automatisch.",
        viewAuditBtn = "🔬 Thermodynamik-Audit ansehen",
        syncScheduleBtn = "Jetzt günstigsten Zeitplan synchronisieren",
        inspectScheduleBtn = "7-Tage-Heizplan prüfen",
        hideScheduleBtn = "7-Tage-Heizplan ausblenden",
        weeklyScheduleHeader = "AKTIVES 7-TAGE-SCHALTPROGRAMM",
        readingScheduleText = "Lese 7-Tage-Plan direkt vom Bosch-Gateway...",
        tapToInspect = "Tippen zum Prüfen.",
        telemetryTitle = "Live-Telemetrie & Reale Heizkurve",
        telemetrySubtitle = "Empirische Heizkurven-Extrapolation & thermisches Tagesprofil",
        badgeOutdoor = "Außen: %s",
        badgeSupply = "Vorlauf: %s",
        badgeReturn = "Rücklauf: %s",
        badgeDelta = "ΔT: %s",
        badgeMod = "Mod: %s",
        badgeDemand = "Modus: %s",
        curveCardTitle = "HEIZKURVE & BETRIEBSTELEMETRIE (BOSCH HPC 410)",
        fullscreenBtn = "⛶ Vollbild",
        awaitingRunData = "Warte auf Messpunkte während des Heizbetriebs...",
        curveEvaluationDesc = "Erfasst stationäre Messpunkte während des Heizbetriebs, um die reale Heizkurve Ihres Gebäudes zu lernen und mit den Bosch-Dokumenten abzugleichen.",
        snapshotsCountFormat = "%d Messungen in lokaler Datenbank erfasst",
        refreshTelemetryBtn = "Telemetrie aktualisieren",
        exportCsvBtn = "CSV exportieren",
        comfortTitle = "Komfort- & Laufzeitsteuerung",
        comfortSubtitle = "Ziel-Raumtemperatur und tägliche Betriebszeit einstellen",
        heatingTargetLabel = "Ziel-Heiztemperatur",
        heatingTargetDesc = "Gilt für Raumheizung (HC1 comfort2). Absenkung (17°C) & Warmwasser (42°C) bleiben unberührt.",
        runtimeTargetLabel = "Tägliche Ziel-Laufzeit",
        budgetTitle = "Tagesbudget & Wärmepumpenleistung",
        budgetSubtitle = "Wirtschaftliche Obergrenzen und Leistungsaufnahme zur Kostenberechnung",
        dailyBudgetLabel = "Tagesbudget (€/Tag)",
        noLimit = "Kein Limit",
        powerModelLabel = "Leistungsmodell / Last (kW)",
        powerHint = "Auto (~0.6kW)",
        budgetExplanation = "• Festes Budgetlimit: Begrenzt die Laufzeit so, dass die täglichen Stromkosten dieses Limit nie überschreiten.\n• Durchschn. Leistung: Dient zur Multiplikation der Spotpreise (€/kWh) in tägliche Euro-Ausgaben.",
        autoTitle = "Hintergrund-Automatisierung & EPEX Spot Sync",
        autoSubtitle = "Stündlicher Daemon ruft EPEX-Spotpreise ab (12:00–15:00 CET) und sendet Zeitpläne",
        autoSwitchLabel = "Automatische Hintergrund-Synchronisation (stündlich)",
        cronStatusFormat = "Stündlicher Cron: %s  •  Akku: %s",
        cronActive = "Aktiv",
        cronPaused = "Pausiert",
        batteryUnrestricted = "Uneingeschränkt (Optimal)",
        batteryOptimized = "Optimiert (Kann Sync verzögern)",
        reqBatteryBtn = "Unbeschränkte Akkunutzung gewähren (Empfohlen)",
        languageSectionTitle = "🌐 Anzeigesprache / Language / 语言",
        languageSectionSubtitle = "Wählen Sie die Sprache für Bedienelemente, Statusanzeigen und Telemetriedaten.",
        tariffSectionTitle = "⚡ Strompreis-Compositor & Tarife",
        tariffSectionSubtitle = "Tarifbestandteile (MwSt., Netzentgelte, Vertriebsmarge) anpassen oder auf vereinfachten Hoch-/Niedertarif (HT/NT) oder Festpreis umschalten.",
        dynamicSpotModel = "Dynamischer Spot",
        touModel = "Hoch- / Niedertarif",
        flatRateModel = "Festpreis",
        baseWorkingPriceLabel = "Fester Arbeitspreis (Netto)",
        baseWorkingPriceSub = "Nicht-börsenbasierter Anteil aus Ihrem Vertrag (Netzentgelte, Steuern, Umlagen & Vertrieb).",
        monthlyBaseFeeLabel = "Monatlicher Grundpreis",
        monthlyBaseFeeSub = "Monatliche Grundgebühr laut Vertragsbestätigung.",
        vatLabel = "Mehrwertsteuer (MwSt)",
        vatSub = "Gesetzliche Mehrwertsteuer auf Strom (DE 19%, AT 20%, Solar 0%).",
        contractFormulaTitle = "📄 Formel laut Stromvertragsbrief",
        peakPriceLabel = "Hochtarif (HT)",
        peakPriceSub = "Tagsüber geltender Tarif laut Zweitarifvertrag.",
        offPeakPriceLabel = "Niedertarif (NT)",
        offPeakPriceSub = "Nachts geltender Sparpreis laut Vertrag.",
        offPeakHoursLabel = "Niedertarif-Zeiten (Nacht):",
        weekendOffPeakLabel = "Ganztägig Niedertarif am Wochenende (Sa/So)",
        touScheduleTitle = "🕒 Zeitplan Hoch-/Niedertarif",
        flatRateLabel = "Fester Strompreis (Festpreis)",
        flatRateSub = "Fester Bruttopreis pro kWh laut Stromvertrag.",
        sourcesSectionTitle = "🌐 Börsen-Strompreisquellen",
        sourcesSectionSubtitle = "Börsendatenanbieter für dynamische Stromtarife. Werden bei aktivem dynamischen Tarif automatisch abgefragt.",
        testSourcesBtn = "Preisquellen jetzt testen",
        primaryLabel = "★ Primär",
        setPrimaryLabel = "Als Primär setzen",
        telemetryRecSectionTitle = "📈 Live-Telemetrie & Heizkurve",
        telemetryRecSectionSubtitle = "Zeichnet Außen-, Vorlauf-, Rücklauftemperatur und Modulation in SQLite-Datenbank auf, um Gebäude-Wärmeeigenschaften empirisch zu lernen.",
        recordTelemetrySwitch = "Telemetrie im Hintergrund aufzeichnen",
        samplingFreqLabel = "Abtastrate",
        freqEveryMinute = "Jede Minute",
        freqEveryMinutes = "Alle %d Min",
        freqEveryHour = "Jede Stunde",
        clearDbBtn = "Datenbank leeren",
        clearDbTitle = "Telemetriedatenbank leeren?",
        clearDbMessage = "Dadurch werden alle aufgezeichneten Temperaturdaten und gelernten stationären Messpunkte unwiderruflich gelöscht.",
        clearAllBtn = "Alles löschen",
        devCardTitle = "🛠️ Entwickler & Diagnose",
        devCardSubtitle = "Detaillierte Diagnoseprotokolle, API-Nutzlastprüfung und Testwerkzeuge.",
        debugModeSwitch = "Entwickler- / Debug-Modus",
        debugModeDesc = "Zeigt bei Aktivierung Live-Kommunikationsprotokolle und Telemetrie-JSON in einer Terminal-Karte auf dem Hauptbildschirm an.",
        inspectRawScheduleBtn = "Rohdaten des Heizplans & Endpunkte prüfen",
        holisticSectionTitle = "☀️ Ganzheitliche thermodynamische Optimierung",
        holisticSectionSubtitle = "Optimiert nach Nutzwärmekosten (c_Strom / COP), nutzt den mittäglichen solaren Carnot-Peak und ermittelt dynamischen thermischen Tagesbedarf.",
        holisticSwitchLabel = "Ganzheitliche Mehrziel-Optimierung",
        holisticDesc = "Ersetzt starre Stundenbudgets durch dynamischen kWh-Wärmebedarf. Läuft während solarer Mittagsstunden und 0-Cent-Fenstern, wenn der COP am höchsten ist.",
        optPreferenceLabel = "Optimierungs-Präferenz",
        prefBalancedSub = "Optimale Balance zwischen Stromkostenersparnis und stabiler Estrichtemperatur",
        prefComfortSub = "Höchster Raumkomfort; heizt länger, auch bei leicht höheren Spotpreisen",
        prefBudgetSub = "Strikte Kostenkontrolle; heizt ausschließlich in den günstigsten Stunden",
        prefEfficiencySub = "Priorisiert hohe COP-Sonnenstunden und steile Preistäler",
        screedBufferLabel = "Estrich-Pufferüberhöhung",
        heatLossLabel = "Gebäude-Wärmeverlust",
        setbackStatusDefault = "Gebäude-Wärmeverlust automatisch aus der Abkühlung des Rücklaufs während der Nachtabsenkung ableiten.",
        analyzeSetbackBtn = "Nachtabsenkungs-Telemetrie analysieren",
        applySetbackBtnFormat = "%d W/K als Gebäude-Wärmeverlust übernehmen",
        modStatusDefault = "Inverter-Modulationsmodell: %s%% bei 10°C (+%s%%/K kälter)",
        analyzeModulationBtn = "Inverter-Modulationstelemetrie analysieren",
        applyModulationBtnFormat = "Modell übernehmen (%s%% bei 10°C, +%s%%/K)",
        parasiticSectionTitle = "Parasitäre Dauerlast & Submeter",
        parasiticDesc = "Berücksichtigt die sekundäre Fußbodenheizungspumpe (~25W) und Steuerplatinen (~15W) im Heiz- und Absenkbetrieb.",
        externalSubmeterLabel = "Externer physikalischer Submeter installiert (z.B. Shelly 3EM)",
        submeterHint = "ℹ Hinweis: Keine direkte Zähler-Kommunikation (WLAN/API) implementiert. Ist der Schalter AN, überschreibt der Optimierer den Standard (~40W) mit Ihrem kalibrierten Schieberegler unten.",
        submeterDefaultBadge = "~40 W (0,96 kWh/d) • Standard",
        submeterCalibratedBadgeFormat = "%d W (%.2f kWh/d) • Aktiv",
        testWeatherBtn = "Wettervorhersage testen",
        todayPhysicsAuditTitle = "Thermodynamik-Audit Heutiger Zeitplan",
        tomorrowPhysicsAuditTitle = "Thermodynamik-Audit Morgiger Zeitplan",
        noPhysicsAuditMessage = "Noch keine thermodynamische Aufschlüsselung verfügbar. Tippen Sie auf 'Jetzt synchronisieren', um sie zu generieren.",
        close = "Schließen",
        overwriteHintTwice = "Zeitplan wurde manuell geändert. Tippen Sie noch zweimal, um zu überschreiben.",
        overwriteHintOnce = "Zeitplan wurde manuell geändert. Tippen Sie noch einmal, um zu überschreiben.",
        overwriteBtnTwice = "Noch 2x tippen zum Überschreiben",
        overwriteBtnOnce = "Noch 1x tippen zum Überschreiben",
        overwritingToast = "Überschreibe Zeitplan mit %s°C...",
        syncingScheduleToast = "Synchronisiere Zeitplan mit Wärmepumpe...",
        contractFormulaDetail = "Bruttopreis = (Börsenpreis_netto + %.2f ct Arbeitspreis) × %.2f (%.1f%% MwSt.)\n• Vertraglicher Mindestpreis (bei 0 ct Spot): %.2f ct/kWh brutto\n• Beispiel-Arbeitspreis (bei 5,0 ct Spot): %.2f ct/kWh brutto\n• Monatlicher Grundpreis: %.2f €/Monat",
        touSummaryDetail = "• Aktueller Status: %s (%.1f ct/kWh)\n• NT-Nachtzeitfenster: %02d:00 → %02d:00 (%d Std. Dauer)\n• Wochenend-Niedertarif: %s\n• HT-Aufschlag: +%.1f ct/kWh (%.0f%% teurer als NT)\n• MPC-Planer verschiebt Wärmepumpenlauf automatisch in NT-Zeiten.",
        touStatusOffPeak = "🟢 NT-Niedertarif aktiv",
        touStatusPeak = "🟠 HT-Hochtarif aktiv",
        touWeekendAllDay = "Inbegriffen (24h)",
        touWeekendStandard = "Standardzeiten",
        flatRateNoteTitle = "💡 Festpreis-Optimierungshinweis",
        flatRateNoteDesc = "Bei einem festen Stromtarif optimiert der MPC-Planer den Wärmepumpenbetrieb rein nach Carnot-Wirkungsgrad (Außentemperatur), solarer Einstrahlung und Estrich-Pufferspeicher.",
        scanningSetbackMsg = "Durchsuche bis zu 25.000 Telemetriepunkte nach nächtlicher Absenkkühlung (≥2h nach Sonnenuntergang bis Sonnenaufgang)...",
        setbackCompleteMsg = "✓ Nächtliche Absenkanalyse abgeschlossen (25k-Datenfenster)",
        noSetbackCooldownMsg = "Keine gültige nächtliche Absenkphase gefunden (erfordert entladenden warmen Estrich ≥2h nach Sonnenuntergang bei Verdichter AUS). Lassen Sie die Wärmepumpe heute Nacht in Absenkung laufen.",
        scanningModulationMsg = "Durchsuche bis zu 25.000 Telemetriepunkte nach stationärer Verdichtermodulation...",
        modCalibratedMsg = "✓ Inverter-Modulations-PID-Modell kalibriert (25k-Datenfenster)",
        noHeatingRunsMsg = "Keine aktiven Heizläufe gefunden, die der Solltemperatur %s°C entsprechen (mindestens 5 erforderlich). Heizen Sie, um Telemetrie zu sammeln.",
        telemetryDefaultDesc = "Vergleicht reale Betriebstelemetrie direkt mit den Bosch HPC 410 Werks-Heizkurven (Bild 2 & 4). Tippen Sie auf das Diagramm oder Vollbild, um Kurven anzupassen.",
        telemetryShiftKFormat = " (Parallelverschiebung +%.1f K)",
        telemetryShiftBase = " (+0 K Basis)",
        telemetryLoggedCountFormat = " (%d erfasst)",
        telemetryEquilibriumFormat = "Telemetrie: %d Gleichgewichtspunkte (%d Anfahr-Rampenpunkte ausgeschlossen)",
        telemetrySteadyStateFormat = "Telemetrie: %d stationäre Messpunkte",
        telemetryAvgDeltaTFormat = "Mittlere Heizspreizung ΔT: %.1f K",
        telemetryThermalDecayFormat = "  •  Thermisches Abklingen: -%.2f °C/h",
        telemetryWarmupRampFormat = "  •  Aufheizrampe: ~%.0f min",
        modeDhw = "WW",
        modeHeating = "Heizen",
        modeIdle = "Standby",
        cascadeActivePlanFormat = "Aktiver Tarif: %s\n(Synthetische Zeitslots direkt aus Fahrplan generiert; externer Börsen-Fallback umgangen)",
        cascadePriorityTitle = "Prioritäts-Kaskade:\n",
        cascadePrimaryRole = "Primär",
        cascadeFallbackRoleFormat = "Fallback %d",
        cascadeDelayedLine = "\n  ↳ Bei Verzögerung/Offline: ",
        cascadeStatusReady = "Bereit",
        cascadeStatusChecking = "Prüfe...",
        cascadeStatusOnlineFormat = "✓ Online (%d Slots, %dms)",
        anchorActiveBadge = "⚓ 25k-Anker aktiv",
        blockSingle = "%d Block",
        blockPlural = "%d Blöcke",
        chipHeatLossRate = "Wärmeverlustrate",
        chipWeightedConfidence = "Gewichtete Konfidenz",
        chipCoolingRate = "Abkühlrate",
        chipTempDropFormat = "Abfall: %.1f K",
        chipOutdoorTemp = "Außentemperatur",
        chipThermalMass = "Thermische Masse",
        chipScreedCore = "Estrichkern C_eff",
        nuanceBoxTitle = "Methodische Details & Physikalische Anpassungen",
        nuanceSetbackPriorFormat = "• 25k-Historischer Anker: Kumulative historische Basislinie (%d W/K, Gewicht=%d) mit %d aktiven Beobachtungsblöcken verschmolzen.",
        nuanceSetbackWindow = "• 25k-Datenfenster: Bis zu 25.000 Punkte durchsucht; initiale mehrtägige Gebäudehüll-Basislinie etabliert.",
        nuanceSolarGate = "• Sonneneinstrahlungs-Filter: Tageslicht & Dämmerung ausgeschlossen; strikt ≥2h nach Sonnenuntergang bis Sonnenaufgang zur Vermeidung solarer Nachlaufeffekte.",
        nuanceQuiescence = "• Systemruhe: 0% Verdichtermodulation, Heizung & Warmwasser inaktiv, Spreizung |Tvor - Trück| ≤ 1,5 K verifiziert.",
        nuanceMassCouplingFormat = "• 2R2C-Zustandsraum-Massenkopplung: Kernentladungsfaktor α ausgewertet (%.1f kWh/K dynamisch wirksame Wärmekapazität).",
        nuanceMultiNight = "• Mehrtägige Überbrückung: Nahtlos über Mitternachtsgrenzen hinweg berechnet; Konfidenz-Gewichtung nach R² × Dauer.",
        badgeTargetFormat = "Ziel: %.1f°C",
        badgeRunsCountFormat = "%d Heizläufe (%d Pkt.)",
        chipBaselineMod = "Basis-Modulation",
        chipInverterFreq = "Inverter-Frequenz",
        chipSensitivity = "Kälte-Sensitivität",
        chipColderSens = "Zuschlag pro Grad",
        chipSteadyPower = "Stationäre Leistung",
        chipShaftPump = "Welle + 50W Pumpe",
        chipLoopSpread = "Hydraulik-Spreizung",
        chipHydraulicLift = "Wärmehub",
        nuanceModPriorFormat = "• 25k-Historischer Anker: Kumulative 25.000-Punkte-Basislinie (%.1f%% bei 10°C, Gewicht=%d) in neue Regression eingeflossen, um langfristiges Lernen über Saisons zu sichern.",
        nuanceModWindow = "• 25k-Datenfenster: Bis zu 25.000 Punkte gescannt; initialisiere kumulativen 25k-Anker.",
        nuancePidStartupFormat = "• PID-Anfahrtransiente isoliert: %d Aufheiz-Messwerte (Ø %s in 20-Min.-Rampe) vom stationären Zustand getrennt (+%d%% PID-Überschwingen).",
        nuanceObservedCorridorFormat = "• Beobachteter Außenkorridor: Telemetrie zwischen %.1f°C und %.1f°C erfasst; Roh-Modulation %.0f%% bis %.0f%%.",
        nuanceInverterClamping = "• Frequenzgrenze & Grundlast: 28,0% Hardware-Mindestfrequenz und 50 W Dauerlast der Wilo-Umwälzpumpe berücksichtigt.",
        projectionTableTitle = "Dynamischer Temperatur-Betriebskorridor",
        projectionFloorBadge = "(Min.)",
        weatherLocationFormat = "Open-Meteo DWD ICON: Breitengrad %.2f°N, Längengrad %.2f°O",
        weatherFetchingMsg = "Rufe 48h-Vorhersage des DWD ICON ab...",
        weatherSuccessFormat = "✓ Wetterdaten OK: %d-Std.-Vorhersage (Min: %.1f°C, Max: %.1f°C, Spitzen-Solar: %d W/m²)",
        weatherOfflineMsg = "✗ Wetterdienst offline: verwende synthetische Tagesgangkurve"
    )

    private val ZH = AppStrings(
        appTitle = "BREAST 热泵控制",
        appSubtitle = "博世热泵日前现货电价自主优化",
        settingsTitle = "系统设置",
        done = "← 完成",
        save = "保存",
        cancel = "取消",
        editNameTitle = "命名您的热泵",
        editNamePrompt = "请输入此热泵设备的自定义名称：",
        defaultHeatPumpName = "智能热泵",
        subtitleHeader = "EPEX Spot DE-LU  •  Bosch K30",
        connected = "● 已连接",
        loginNeeded = "● 需要登录",
        checking = "● 正在检查...",
        bgActive = "● 后台运行中",
        bgInactive = "○ 后台已关闭",
        lastSyncNever = "最近同步: 从未",
        lastSyncPrefix = "最近日程同步: %s",
        connectSingleKey = "连接博世 SingleKey ID",
        reconnectSingleKey = "重新连接博世 SingleKey ID",
        scheduledWindowsTitle = "最优电价开机时段",
        scheduledWindowsSubtitle = "空间采暖 (HC1) 与生活热水 (DHW1) 统一协同运行窗口",
        manualAdjustmentDetected = "⚠ 检测到手动调整，自动同步已暂停",
        manualAdjustmentDetail = "温控器上的热泵运行日程已被修改。自动同步已暂停以保留您的手动设置。点击“立即同步”以覆盖并恢复自动同步。",
        optModePrefix = "优化模式: %s",
        modeComfort = "舒适优先",
        modeBalanced = "均衡模式",
        modeEfficiency = "能效优先",
        modeBudget = "预算优先",
        todayAppliedTitle = "今日 (%s)  •  当前已执行日程 • 目标: %s",
        todayDefaultTitle = "今日日程",
        todayNoSchedule = "今日尚未同步日程。点击“立即同步最低电价时段”。",
        tomorrowScheduledTitle = "明日 (%s)  •  已规划日程",
        tomorrowDayAheadTitle = "明日 (日前预测)",
        tomorrowNotice = "日前现货电价每日 12:00–15:00 CET 发布，后台守护进程将自动同步。",
        viewAuditBtn = "🔬 查看热力学物理审计报告",
        syncScheduleBtn = "立即同步最低电价时段",
        inspectScheduleBtn = "查看热泵 7 天完整日程",
        hideScheduleBtn = "隐藏热泵 7 天完整日程",
        weeklyScheduleHeader = "当前生效的 7 天热泵运行程序",
        readingScheduleText = "正在从博世网关直接读取 7 天日程...",
        tapToInspect = "点击查看详情。",
        telemetryTitle = "实时遥测与真实供热曲线",
        telemetrySubtitle = "经验供水温度曲线拟合与全天热力学画像",
        badgeOutdoor = "室外: %s",
        badgeSupply = "供水: %s",
        badgeReturn = "回水: %s",
        badgeDelta = "温差: %s",
        badgeMod = "调制: %s",
        badgeDemand = "模式: %s",
        curveCardTitle = "供热曲线与运行遥测 (BOSCH HPC 410)",
        fullscreenBtn = "⛶ 全屏图表",
        awaitingRunData = "等待采暖运行数据记录点...",
        curveEvaluationDesc = "在热泵采暖运行时采集稳态数据点，拟合建筑真实供热曲线并与博世官方技术规范比对。",
        snapshotsCountFormat = "本地数据库已记录 %d 条遥测采样",
        refreshTelemetryBtn = "刷新遥测数据",
        exportCsvBtn = "导出 CSV",
        comfortTitle = "目标温度与运行时长控制",
        comfortSubtitle = "调节室内舒适温度及每日目标运行时长",
        heatingTargetLabel = "采暖目标温度",
        heatingTargetDesc = "应用于空间采暖 (HC1 comfort2)。夜间回落 (17°C) 与生活热水 (42°C) 保持不变。",
        runtimeTargetLabel = "每日目标运行时长",
        budgetTitle = "每日预算与设备功率设置",
        budgetSubtitle = "用于成本核算的用电花费上限与功率参数",
        dailyBudgetLabel = "每日预算 (€/天)",
        noLimit = "不限",
        powerModelLabel = "功率模型 / 实际功率 (kW)",
        powerHint = "自动 (~0.6kW)",
        budgetExplanation = "• 硬性预算上限：设置后，优化器将严格限制运行时长，使每日电费支出不超过该额度。\n• 平均耗电功率：用于将动态现货电价 (€/kWh) 折算为每日实际欧元支出。",
        autoTitle = "后台自动化与现货电价同步",
        autoSubtitle = "每小时守护进程自动拉取现货电价 (12:00–15:00 CET) 并下发计划",
        autoSwitchLabel = "自动后台同步 (每小时)",
        cronStatusFormat = "每小时定时任务: %s  •  电池策略: %s",
        cronActive = "运行中",
        cronPaused = "已暂停",
        batteryUnrestricted = "不受限制 (最佳)",
        batteryOptimized = "受系统优化 (后台同步可能延迟)",
        reqBatteryBtn = "申请不受限电池白名单 (推荐)",
        languageSectionTitle = "🌐 界面语言 / Language / Sprache",
        languageSectionSubtitle = "选择应用程序界面控件、按钮及遥测卡片的显示语言。",
        tariffSectionTitle = "⚡ 电价模型合成器与资费管理",
        tariffSectionSubtitle = "自定义电价构成（增值税、电网费、售电公司溢价）或切换至峰谷平时段（ToU）或固定电价合同。",
        dynamicSpotModel = "动态现货",
        touModel = "峰谷电价 (ToU)",
        flatRateModel = "单一固定价",
        baseWorkingPriceLabel = "度电基础电价 (净价)",
        baseWorkingPriceSub = "购电合同中除现货价格外的固定组成部分（电网费、税费、附加费及售电溢价）。",
        monthlyBaseFeeLabel = "每月基础服务费 (Grundpreis)",
        monthlyBaseFeeSub = "购电合同确认函中的每月固定基本费。",
        vatLabel = "增值税率 (VAT / MwSt)",
        vatSub = "所在司法管辖区适用的电力销售增值税（德国 19%，奥地利 20%，光伏 0%）。",
        contractFormulaTitle = "📄 购电合同核算公式",
        peakPriceLabel = "高峰电价 (Hochtarif / HT)",
        peakPriceSub = "双费率购电合同中的日间高峰电价。",
        offPeakPriceLabel = "低谷电价 (Niedertarif / NT)",
        offPeakPriceSub = "购电合同中的夜间低谷优惠电价。",
        offPeakHoursLabel = "低谷用电时段 (夜间):",
        weekendOffPeakLabel = "周末全天执行低谷电价 (周六/周日)",
        touScheduleTitle = "🕒 峰谷时段排程概览",
        flatRateLabel = "单一固定电价 (Festpreis)",
        flatRateSub = "购电合同约定的每度电固定含税全包价格。",
        sourcesSectionTitle = "🌐 现货电力市场数据源",
        sourcesSectionSubtitle = "用于动态现货电价的交易所数据提供方。启用动态电价时按序查询。",
        testSourcesBtn = "立即测试价格源",
        primaryLabel = "★ 主数据源",
        setPrimaryLabel = "设为主数据源",
        telemetryRecSectionTitle = "📈 实时遥测与供热曲线",
        telemetryRecSectionSubtitle = "将热泵室外温度、供回水温度与变频调制录入本地 SQLite 数据库，用于拟合建筑真实热力学参数。",
        recordTelemetrySwitch = "后台自动记录遥测数据",
        samplingFreqLabel = "采样频率",
        freqEveryMinute = "每 1 分钟",
        freqEveryMinutes = "每 %d 分钟",
        freqEveryHour = "每 1 小时",
        clearDbBtn = "清空数据库",
        clearDbTitle = "清空遥测数据库？",
        clearDbMessage = "此操作将永久删除所有已记录的温度采样记录和已学习的稳态数据点。",
        clearAllBtn = "全部清空",
        devCardTitle = "🛠️ 开发者与诊断控制台",
        devCardSubtitle = "高级诊断日志、API 负载审查与测试工具。",
        debugModeSwitch = "开发者 / 调试模式",
        debugModeDesc = "启用后，在主屏幕底部以等宽字体控制台卡片展示 SingleKey 通信日志及遥测 JSON 输出。",
        inspectRawScheduleBtn = "查看热泵原始日程及 API 端点",
        holisticSectionTitle = "☀️ 全局多目标热力学与能效优化",
        holisticSectionSubtitle = "以交付热能成本 (度电成本/COP) 为导向寻优，捕捉正午日照卡诺能效峰值，动态解算日均需热量。",
        holisticSwitchLabel = "全局多目标热力学协同优化",
        holisticDesc = "以动态日均 kWh 需热量替代僵化的固定运行小时数。优先在 COP 最高的正午日照峰值及超低电价时段运行。",
        optPreferenceLabel = "运行优化策略偏好",
        prefBalancedSub = "在电价成本节约与室内地暖温度平稳之间达到最优平衡",
        prefComfortSub = "最高舒适度；即便电价稍高也维持足够的运行时间以保障室温",
        prefBudgetSub = "严格成本控制；仅在全天电价最低的时段集中运行",
        prefEfficiencySub = "重点锁定正午高 COP 日照时段与深谷电价",
        screedBufferLabel = "地暖砂浆蓄热温升上限",
        heatLossLabel = "建筑物散热损耗估算",
        setbackStatusDefault = "在夜间降温回落期间，根据地暖回水温度衰减自动推算建筑散热损耗。",
        analyzeSetbackBtn = "分析夜间降温回水遥测",
        applySetbackBtnFormat = "应用 %d W/K 至建筑物散热损耗设置",
        modStatusDefault = "变频压缩机调制模型: %s%% @ 10°C (每降低 1K 增加 +%s%%)",
        analyzeModulationBtn = "分析变频压缩机调制遥测",
        applyModulationBtnFormat = "应用此校准模型 (%s%% @ 10°C, +%s%%/K)",
        parasiticSectionTitle = "辅助设备待机功耗与外接电表",
        parasiticDesc = "计入地暖二次循环泵 (~25W) 及主控电路板 (~15W) 在加热及回落时段的全天连续功耗。",
        externalSubmeterLabel = "已安装外接物理电表 (如 Shelly 3EM)",
        submeterHint = "ℹ 提示：尚未实装与外接电表的直接联网通信（Wi-Fi/API）。开启后，优化器将采用下方滑块设定的实测值替代约 40W 的理论默认值。",
        submeterDefaultBadge = "~40 W (0.96 kWh/d) • 默认基准",
        submeterCalibratedBadgeFormat = "%d W (%.2f kWh/d) • 已启用",
        testWeatherBtn = "测试天气预报数据接口",
        todayPhysicsAuditTitle = "今日调度物理与热力学审计",
        tomorrowPhysicsAuditTitle = "明日调度物理与热力学审计",
        noPhysicsAuditMessage = "暂无物理分解数据。点击“立即同步最低电价时段”以生成。",
        close = "关闭",
        overwriteHintTwice = "检测到手动调整，请再次连击两次以覆盖设置",
        overwriteHintOnce = "检测到手动调整，请再点击一次以覆盖设置",
        overwriteBtnTwice = "再次点击两次以覆盖（剩余 2 次）",
        overwriteBtnOnce = "再次点击一次以覆盖（剩余 1 次）",
        overwritingToast = "正在使用 %s°C 覆盖热泵计划...",
        syncingScheduleToast = "正在与热泵同步运行计划...",
        contractFormulaDetail = "含税总价 = (现货净价 + %.2f 欧分度电附加费) × %.2f (%.1f%% 增值税)\n• 合同保底电价 (0分现货价时): %.2f 欧分/kWh (含税)\n• 交付电价示例 (5.0分现货价时): %.2f 欧分/kWh (含税)\n• 每月固定基础费: %.2f €/月",
        touSummaryDetail = "• 当前电价状态: %s (%.1f 欧分/kWh)\n• 夜间低谷时段: %02d:00 → %02d:00 (持续 %d 小时)\n• 周末全天低谷: %s\n• 高峰加价: +%.1f 欧分/kWh (比低谷高 %.0f%%)\n• 调度算法自动将热泵运行转移至低谷时段。",
        touStatusOffPeak = "🟢 低谷时段 (NT) 运行中",
        touStatusPeak = "🟠 高峰时段 (HT) 运行中",
        touWeekendAllDay = "全天低谷 (24小时)",
        touWeekendStandard = "标准时段",
        flatRateNoteTitle = "💡 固定费率优化提示",
        flatRateNoteDesc = "在固定电价下，MPC 调度器纯粹依据室外气温 Carnot 效率提升、日间太阳辐射增益及建筑地暖热蓄能进行运行优化。",
        scanningSetbackMsg = "正在扫描最多 25,000 个遥测数据点以分析夜间降温冷却曲线（日落后 ≥2h 至日出）...",
        setbackCompleteMsg = "✓ 夜间降温衰减分析完成（25k 数据窗口）",
        noSetbackCooldownMsg = "未找到有效的夜间降温回落周期（需要地暖在日落后 ≥2h 散热且压缩机关闭）。请今晚保持夜间节能降温模式以进行测量。",
        scanningModulationMsg = "正在扫描最多 25,000 个遥测数据点以标定稳态变频压缩机调制率...",
        modCalibratedMsg = "✓ 变频 PID 调制模型已完成标定（25k 数据窗口）",
        noHeatingRunsMsg = "未找到匹配目标设定值 %s°C 的有效采暖运行周期（至少需要 5 次）。请运行采暖以收集遥测数据。",
        telemetryDefaultDesc = "将实际运行遥测数据直接绘制于博世 HPC 410 出厂供热特性曲线之上（技术手册图 2 & 4）。点击图表或全屏以自定义图层叠放。",
        telemetryShiftKFormat = " (平行平移 +%.1f K)",
        telemetryShiftBase = " (+0 K 基准)",
        telemetryLoggedCountFormat = " (已记录 %d 点)",
        telemetryEquilibriumFormat = "遥测: %d 个稳态平衡点 (已排除 %d 个升温暂态点)",
        telemetrySteadyStateFormat = "遥测: %d 个稳态数据点",
        telemetryAvgDeltaTFormat = "平均供回水温差 ΔT: %.1f K",
        telemetryThermalDecayFormat = "  •  热衰减速率: -%.2f °C/h",
        telemetryWarmupRampFormat = "  •  升温爬坡时长: ~%.0f 分钟",
        modeDhw = "生活热水",
        modeHeating = "采暖",
        modeIdle = "待机",
        cascadeActivePlanFormat = "当前生效电价: %s\n(直接根据时刻表生成模拟时段；跳过外部交易所备选链路)",
        cascadePriorityTitle = "数据源优先级级联:\n",
        cascadePrimaryRole = "主数据源",
        cascadeFallbackRoleFormat = "备用数据源 %d",
        cascadeDelayedLine = "\n  ↳ 若数据延迟或离线时切换: ",
        cascadeStatusReady = "就绪",
        cascadeStatusChecking = "正在检测...",
        cascadeStatusOnlineFormat = "✓ 在线 (%d 个时段, %d毫秒)",
        anchorActiveBadge = "⚓ 25k 历史锚定生效",
        blockSingle = "%d 个观测周期",
        blockPlural = "%d 个观测周期",
        chipHeatLossRate = "散热损耗速率",
        chipWeightedConfidence = "加权置信度",
        chipCoolingRate = "冷却速率",
        chipTempDropFormat = "温降: %.1f K",
        chipOutdoorTemp = "室外气温",
        chipThermalMass = "有效热容",
        chipScreedCore = "地暖有效热容 C_eff",
        nuanceBoxTitle = "算法物理校准与处理细节",
        nuanceSetbackPriorFormat = "• 25k 历史经验锚定: 将累计历史基准 (%d W/K, 权重=%d) 与当前的 %d 个活跃观测周期进行加权融合。",
        nuanceSetbackWindow = "• 25k 数据窗口: 扫描最多 25,000 点；建立初始多日建筑物围护结构基准。",
        nuanceSolarGate = "• 太阳辐射门控: 排除日间与黄昏；严格限定日落后 ≥2h 至日出时段，彻底消除日光辐射滞后热效应。",
        nuanceQuiescence = "• 系统静默校验: 确保压缩机 0% 调制停止、采暖与热水待机、供回水循环温差 |T供 - T回| ≤ 1.5 K。",
        nuanceMassCouplingFormat = "• 2R2C 状态空间热容耦合: 评估核心蓄热放电因子 α (%.1f kWh/K 动态参与有效热容)。",
        nuanceMultiNight = "• 跨日夜间连续桥接: 无缝跨越午夜；基于拟合优度 R² × 观测时长进行置信度加权。",
        badgeTargetFormat = "目标: %.1f°C",
        badgeRunsCountFormat = "%d 次运行 (%d 个采样点)",
        chipBaselineMod = "基准调制率",
        chipInverterFreq = "变频工作频率",
        chipSensitivity = "温降灵敏度",
        chipColderSens = "每降温 1K 加成",
        chipSteadyPower = "稳态耗电功率",
        chipShaftPump = "压缩机轴功 + 50W 循环泵",
        chipLoopSpread = "循环供回水温差",
        chipHydraulicLift = "水力温升",
        nuanceModPriorFormat = "• 25k 历史经验锚定: 将累计 25,000 点的历史基准 (%.1f%% @ 10°C, 权重=%d) 融入新回归分析，以保留长周期跨季度自学习经验。",
        nuanceModWindow = "• 25k 数据窗口: 扫描最多 25,000 点；正在初始化累计 25k 历史锚定基准。",
        nuancePidStartupFormat = "• PID 启动暂态剥离: 将 %d 个爬坡样本点（20分钟启动期间均值 %s）与稳态分离（识别出 +%d%% PID 超调量）。",
        nuanceObservedCorridorFormat = "• 观测室外温度走廊: 遥测数据分布在 %.1f°C 至 %.1f°C 之间；原始变频调制率范围为 %.0f%% 至 %.0f%%。",
        nuanceInverterClamping = "• 变频下限约束与基准: 设定 28.0% 硬件最低运转频率下限及 50W 持续 Wilo 水泵循环负载。",
        projectionTableTitle = "动态气温运行走廊预测",
        projectionFloorBadge = "(硬件下限)",
        weatherLocationFormat = "Open-Meteo DWD ICON: 纬度 %.2f°N, 经度 %.2f°E",
        weatherFetchingMsg = "正在获取 48 小时德国气象局 DWD ICON 预报...",
        weatherSuccessFormat = "✓ 气象接口正常: 获取 %d 小时预报 (最低: %.1f°C, 最高: %.1f°C, 太阳辐射峰值: %d W/m²)",
        weatherOfflineMsg = "✗ 气象接口离线: 降级采用合成昼夜温差经验曲线"
    )

    fun get(lang: AppLanguage): AppStrings {
        return when (lang) {
            AppLanguage.DE -> DE
            AppLanguage.ZH -> ZH
            AppLanguage.EN -> EN
        }
    }

    fun localizeScenarioLabel(label: String, lang: AppLanguage): String {
        return when (lang) {
            AppLanguage.DE -> when {
                label.startsWith("Mild") -> label.replace("Mild", "Mild")
                label.startsWith("Standard") -> label.replace("Standard", "Standard")
                label.startsWith("Chilly") -> label.replace("Chilly", "Kühl")
                label.startsWith("Cold") -> label.replace("Cold", "Kalt")
                label.startsWith("Freezing") -> label.replace("Freezing", "Frostig")
                label.startsWith("Extreme") -> label.replace("Extreme", "Extremkälte")
                else -> label
            }
            AppLanguage.ZH -> when {
                label.startsWith("Mild") -> label.replace("Mild", "温暖")
                label.startsWith("Standard") -> label.replace("Standard", "基准")
                label.startsWith("Chilly") -> label.replace("Chilly", "寒凉")
                label.startsWith("Cold") -> label.replace("Cold", "寒冷")
                label.startsWith("Freezing") -> label.replace("Freezing", "严寒")
                label.startsWith("Extreme") -> label.replace("Extreme", "极寒")
                else -> label
            }
            AppLanguage.EN -> label
        }
    }

    fun localizeCurveSummary(summary: String, lang: AppLanguage): String {
        return when (lang) {
            AppLanguage.DE -> summary
                .replace("Optimal Bosch underfloor curve", "Optimale Bosch Fußboden-Heizkurve")
                .replace("Slope", "Steigung")
                .replace("Flow @0°C:", "Vorlauf bei 0°C:")
                .replace("Very good heat pump efficiency.", "Sehr gute Wärmepumpen-Effizienz.")
                .replace("Flatter curve", "Flachere Heizkurve")
                .replace("High thermal efficiency, well-insulated building.", "Hohe thermische Effizienz, gut gedämmtes Gebäude.")
                .replace("Steeper curve", "Steilere Heizkurve")
                .replace("Flow @-10°C:", "Vorlauf bei -10°C:")
                .replace("Consider lowering heating curve slope to boost seasonal COP.", "Prüfen Sie ein Absenken der Steigung zur Steigerung der Jahresarbeitszahl (JAZ).")
            AppLanguage.ZH -> summary
                .replace("Optimal Bosch underfloor curve", "博世地暖标准最优曲线")
                .replace("Slope", "斜率")
                .replace("Flow @0°C:", "0°C供水温度:")
                .replace("Very good heat pump efficiency.", "热泵运行能效极佳。")
                .replace("Flatter curve", "平缓供热曲线")
                .replace("High thermal efficiency, well-insulated building.", "建筑物保温性能良好，热工能效高。")
                .replace("Steeper curve", "较陡供热曲线")
                .replace("Flow @-10°C:", "-10°C供水温度:")
                .replace("Consider lowering heating curve slope to boost seasonal COP.", "建议适当调低曲线斜率以提升全年季节性能效比 (SCOP)。")
            AppLanguage.EN -> summary
        }
    }

    fun localizePlanSummary(summary: String, lang: AppLanguage): String {
        if (summary.isBlank()) return summary
        return when (lang) {
            AppLanguage.DE -> summary
                .replace("No window scheduled", "Kein Zeitfenster geplant")
                .replace("Translated Switch Points:", "Schaltpunkte Reglerprogramm:")
                .replace("(budget-cut -", "(Budgetkürzung -")
                .replace("Avg ", "Ø ")
                .replace(" ct/kWh gross", " ct/kWh brutto")
                .replace("Target ", "Ziel ")
                .replace("Est. ", "Geschätzt ")
                .replace(" win", " Fenster")
                .replace("(HEATING ON)", "(HEIZEN AN)")
                .replace("(SETBACK)", "(ABSENKUNG)")
                .replace("comfort2 @", "Komfort 2 @")
                .replace("setback @", "Absenkung @")
                .replace("DHW low @ 42°C", "Warmwasser 42°C")
                .replace("DHW off", "Warmwasser Aus")
                .replace("Standard Window", "Standard-Zeitfenster")
                .replace("Balanced", "Ausgewogen")
                .replace("Comfort", "Komfort")
                .replace("Efficiency", "Effizienz")
                .replace("Budget Priority", "Budget")
                .replace("Budget", "Budget")
                .replace(" mod)", " Modulation)")
            AppLanguage.ZH -> summary
                .replace("No window scheduled", "未排程开机窗口")
                .replace("Translated Switch Points:", "下发至热泵的定时开关点:")
                .replace("(budget-cut -", "(预算削减 -")
                .replace("Avg ", "均价 ")
                .replace(" ct/kWh gross", " 欧分/kWh (含税)")
                .replace("Target ", "目标室温 ")
                .replace("Est. ", "预估 ")
                .replace(" win", " 窗口")
                .replace("(HEATING ON)", "(采暖开启)")
                .replace("(SETBACK)", "(夜间回落)")
                .replace("comfort2 @", "舒适模式2 @")
                .replace("setback @", "夜间回落 @")
                .replace("DHW low @ 42°C", "生活热水 42°C")
                .replace("DHW off", "生活热水 关闭")
                .replace("Standard Window", "标准窗口")
                .replace("Balanced", "均衡模式")
                .replace("Comfort", "舒适优先")
                .replace("Efficiency", "能效优先")
                .replace("Budget Priority", "预算优先")
                .replace(" mod)", " 变频调制)")
                .replace(" 24h", " 24小时")
            AppLanguage.EN -> summary
        }
    }

    fun localizePhysicsAudit(breakdown: String, lang: AppLanguage): String {
        if (breakdown.isBlank()) return breakdown
        return when (lang) {
            AppLanguage.DE -> breakdown
                .replace("1. Weather Conditions (DWD ICON Forecast):", "1. Wetterbedingungen (DWD ICON Vorhersage):")
                .replace("24h Average Outdoor Temp:", "24h-Durchschnittsaußentemperatur:")
                .replace("Window Operating Ambient Temp:", "Mittlere Außentemperatur im Heizfenster:")
                .replace("Indoor - Outdoor Delta T:", "Innen-Außen-Temperaturdifferenz (ΔT):")
                .replace("2. Thermal Demand (2R2C Coupled State-Space):", "2. Wärmebedarf (2R2C gekoppeltes Zustandsraummodell):")
                .replace("Building Loss Coeff (Hbld):", "Gebäude-Wärmeverlustkoeffizient (Hbld):")
                .replace("(Adaptive online learned from", "(Adaptiv online gelernt aus")
                .replace("nocturnal cycles)", "nächtlichen Zyklen)")
                .replace("(Calibrated baseline)", "(Kalibrierte Basislinie)")
                .replace("Screed Cooldown Rate (κ):", "Estrich-Abkühlrate (κ):")
                .replace("Domestic & Solar Gains Offset:", "Interne & solare Wärmegewinne:")
                .replace("Live Measured Screed Return:", "Gemessener Estrich-Rücklauf:")
                .replace("(Target return:", "(Ziel-Rücklauf:")
                .replace("Active Daytime Net Heat Loss:", "Netto-Wärmeverlust im aktiven Fenster:")
                .replace("Nocturnal Reheat Mass Recovery:", "Wiederaufheizenergie für Estrichmasse:")
                .replace("Total Daily Thermal Demand:", "Gesamter täglicher Wärmebedarf:")
                .replace("3. Inverter Modulation & Thermodynamics:", "3. Inverter-Modulation & Thermodynamik:")
                .replace("Target Flow Temp:", "Ziel-Vorlauftemperatur:")
                .replace("(Carnot Lift:", "(Carnot-Hub:")
                .replace("Thermodynamic Reciprocal COP:", "Thermodynamischer Reziprok-COP:")
                .replace("Bosch Inverter Speed:", "Bosch Verdichterfrequenz:")
                .replace("modulation", "Modulation")
                .replace("Active Electrical Draw:", "Elektrische Leistungsaufnahme (aktiv):")
                .replace("Active Window Electrical Consumption:", "Stromverbrauch im aktiven Fenster:")
                .replace("Setback Parasitic & Circulation Draw:", "Parasitärer Standby- & Pumpenstrom:")
                .replace("24-Hour Total Electrical Consumption:", "24-Stunden-Gesamtstromverbrauch:")
                .replace("4. Tariff & Cost:", "4. Stromtarif & Kosten:")
                .replace("EPEX Spot Average (Window):", "EPEX-Spot Durchschnitt (Fenster):")
                .replace("gross", "brutto")
                .replace("net", "netto")
                .replace("Active Window Operating Cost:", "Stromkosten im aktiven Fenster:")
                .replace("Setback Standby & Circulation Cost:", "Standby- & Umwälzkosten:")
                .replace("24-Hour Total Electricity Cost:", "24-Stunden-Gesamtkosten:")
                .replace("5. Dynamic Runtime Corridor (Harmonized Quantizer):", "5. Dynamischer Laufzeitkorridor (Harmonisierter Quantisierer):")
                .replace("Target Daily Runtime (Slider):", "Tägliche Ziel-Laufzeit (Schieberegler):")
                .replace("Preference Mode:", "Optimierungsmodus:")
                .replace("expansion allowance", "Ausdehnungszuschlag")
                .replace("Scheduler Quantizer Step:", "Planer-Raster-Schrittweite:")
                .replace("Harmonized Extra Corridor Allowance:", "Harmonisierte Korridor-Erweiterung:")
                .replace("(Corridor:", "(Bereich:")
                .replace("Search Slots Evaluated:", "Bewertete Rasterschlitze:")
                .replace("raster slots", "Rasterschlitze")
                .replace("min raster", "Min-Raster")
                .replace("Applied Schedule Duration:", "Angewandte Planungsdauer:")
                .replace("vs target slider", "ggü. Schieberegler")
                .replace("Comfort", "Komfort")
                .replace("Balanced", "Ausgewogen")
                .replace("Efficiency", "Effizienz")
                .replace("Budget Priority", "Budget")
                .replace("Budget", "Budget")
            AppLanguage.ZH -> breakdown
                .replace("1. Weather Conditions (DWD ICON Forecast):", "1. 气象条件 (德国气象局 DWD ICON 预报):")
                .replace("24h Average Outdoor Temp:", "24小时平均室外气温:")
                .replace("Min ", "最低 ")
                .replace("Max ", "最高 ")
                .replace("Window Operating Ambient Temp:", "运行窗口内平均室外气温:")
                .replace("Indoor - Outdoor Delta T:", "室内外温差 (ΔT):")
                .replace("2. Thermal Demand (2R2C Coupled State-Space):", "2. 需热量解算 (2R2C 状态空间耦合模型):")
                .replace("Building Loss Coeff (Hbld):", "建筑物散热损耗系数 (Hbld):")
                .replace("(Adaptive online learned from", "(自适应在线学习，基于")
                .replace("nocturnal cycles)", "个夜间周期)")
                .replace("(Calibrated baseline)", "(已标定基准)")
                .replace("Screed Cooldown Rate (κ):", "地暖砂浆冷却速率 (κ):")
                .replace("Domestic & Solar Gains Offset:", "室内生活与日照增益抵扣:")
                .replace("Live Measured Screed Return:", "实测地暖回水温度:")
                .replace("(Target return:", "(目标回水:")
                .replace("Active Daytime Net Heat Loss:", "运行窗口内净热损耗:")
                .replace("Nocturnal Reheat Mass Recovery:", "夜间回落后蓄热体复温补偿:")
                .replace("Total Daily Thermal Demand:", "全天总需热量:")
                .replace("3. Inverter Modulation & Thermodynamics:", "3. 变频调制与热力学状态:")
                .replace("Target Flow Temp:", "目标供水温度:")
                .replace("(Carnot Lift:", "(卡诺温升:")
                .replace("Thermodynamic Reciprocal COP:", "热力学倒数 COP (单位供热电耗):")
                .replace("Bosch Inverter Speed:", "博世压缩机变频转速:")
                .replace("modulation", "调制率")
                .replace("Active Electrical Draw:", "运行期间实际耗电功率:")
                .replace("Active Window Electrical Consumption:", "运行窗口内用电量:")
                .replace("Setback Parasitic & Circulation Draw:", "待机维持与循环泵寄生电耗:")
                .replace("24-Hour Total Electrical Consumption:", "24小时全天总耗电量:")
                .replace("4. Tariff & Cost:", "4. 电价资费与成本明细:")
                .replace("EPEX Spot Average (Window):", "运行窗口内 EPEX 现货均价:")
                .replace("gross", "含税")
                .replace("net", "净价")
                .replace("Active Window Operating Cost:", "运行窗口内电费支出:")
                .replace("Setback Standby & Circulation Cost:", "待机维持与循环泵电费:")
                .replace("24-Hour Total Electricity Cost:", "24小时全天总用电成本:")
                .replace("5. Dynamic Runtime Corridor (Harmonized Quantizer):", "5. 动态运行时长走廊 (阶梯量化器):")
                .replace("Target Daily Runtime (Slider):", "每日目标运行时长 (滑块设定):")
                .replace("Preference Mode:", "优化策略偏好:")
                .replace("expansion allowance", "扩展裕量")
                .replace("Scheduler Quantizer Step:", "调度器离散步长:")
                .replace("Harmonized Extra Corridor Allowance:", "走廊弹性增量:")
                .replace("(Corridor:", "(走廊范围:")
                .replace("Search Slots Evaluated:", "搜索评估时间槽:")
                .replace("raster slots", "个时间槽")
                .replace("min raster", "分钟网格")
                .replace("Applied Schedule Duration:", "最终执行运行时长:")
                .replace("vs target slider", "相比滑块设定")
                .replace("Comfort", "舒适优先")
                .replace("Balanced", "均衡模式")
                .replace("Efficiency", "能效优先")
                .replace("Budget Priority", "预算优先")
            AppLanguage.EN -> breakdown
        }
    }

    private val NOTIF_EN = NotificationStrings(
        channelAuthName = "SingleKey ID Login Alerts",
        channelAuthDesc = "Alerts when Bosch SingleKey ID token expires or requires re-login",
        channelSyncName = "Daily Heat Pump Schedule Sync",
        channelSyncDesc = "Notifications when new EPEX Spot schedules are pushed to the heat pump",
        channelBgName = "Background Telemetry & Automation Badge",
        channelBgDesc = "Ongoing notification badge indicating BREAST telemetry and heat pump automation are actively monitoring in the background",
        authAlertTitle = "Bosch SingleKey ID Login Needed",
        authAlertTapPrompt = "Tap to open BREAST and sign in so daily heat pump scheduling continues.",
        syncStatusTitle = "Heat Pump Schedule Updated",
        syncStatusSummaryFormat = "Pushed %.1fh lowest-price window (%.1f°C) for %s",
        bgTitleTelemetryAndAuto = "BREAST • Telemetry & Automation Active",
        bgContentTelemetryAndAuto = "Telemetry every %s  •  EPEX Spot active",
        bgBigTextTelemetryAndAuto = "BREAST background operations are active.\n" +
            "• Recording heat pump telemetry every %s (Dev Mode)\n" +
            "• EPEX Spot dynamic schedule sync active (daily 12:00–15:00)\n" +
            "Tap to view heating curve & schedule dashboard.",
        bgTitleTelemetryOnly = "BREAST • Telemetry & Heating Curve Active",
        bgContentTelemetryOnly = "Recording heat pump telemetry every %s",
        bgBigTextTelemetryOnly = "Telemetry collection active in background.\n" +
            "• Recording temperatures every %s to reconstruct heating curve\n" +
            "Tap to view live points and curve analysis.",
        bgTitleSyncOnly = "BREAST • Daily Sync Active",
        bgContentSyncOnly = "EPEX Spot automation active  •  Monitoring spot prices",
        bgBigTextSyncOnly = "BREAST EPEX Spot automation is active in the background.\n" +
            "• Daily sync at 12:00 CET & hourly checks 12:00–15:00\n" +
            "Tap to open dashboard.",
        interval1Min = "1 min",
        interval1Hour = "1 hour",
        intervalNMinFormat = "%d min"
    )

    private val NOTIF_DE = NotificationStrings(
        channelAuthName = "SingleKey ID Anmeldehinweise",
        channelAuthDesc = "Benachrichtigungen, wenn das Bosch SingleKey ID Token abläuft oder ein Re-Login nötig ist",
        channelSyncName = "Täglicher Wärmepumpen-Sync",
        channelSyncDesc = "Benachrichtigungen bei Übertragung neuer EPEX-Spot-Zeitpläne an die Wärmepumpe",
        channelBgName = "Hintergrund-Telemetrie & Automatisierungs-Status",
        channelBgDesc = "Laufende Statusbenachrichtigung, dass BREAST-Telemetrie und Automatisierung aktiv im Hintergrund überwachen",
        authAlertTitle = "Bosch SingleKey ID Anmeldung erforderlich",
        authAlertTapPrompt = "Tippen, um BREAST zu öffnen und anzumelden, damit die tägliche Wärmepumpen-Planung fortgesetzt werden kann.",
        syncStatusTitle = "Wärmepumpen-Zeitplan aktualisiert",
        syncStatusSummaryFormat = "Günstigstes Zeitfenster von %.1fh (%.1f°C) an %s übertragen",
        bgTitleTelemetryAndAuto = "BREAST • Telemetrie & Automatisierung aktiv",
        bgContentTelemetryAndAuto = "Telemetrie alle %s  •  EPEX Spot aktiv",
        bgBigTextTelemetryAndAuto = "BREAST-Hintergrundbetrieb ist aktiv.\n" +
            "• Erfassung der Wärmepumpen-Telemetrie alle %s (Entwicklermodus)\n" +
            "• Dynamischer EPEX-Spot-Zeitplan-Sync aktiv (täglich 12:00–15:00 Uhr)\n" +
            "Tippen, um Heizkurve & Zeitplan-Übersicht anzuzeigen.",
        bgTitleTelemetryOnly = "BREAST • Telemetrie & Heizkurve aktiv",
        bgContentTelemetryOnly = "Erfassung der Wärmepumpen-Telemetrie alle %s",
        bgBigTextTelemetryOnly = "Telemetrie-Erfassung im Hintergrund aktiv.\n" +
            "• Temperaturmessung alle %s zur Rekonstruktion der Heizkurve\n" +
            "Tippen, um Live-Punkte und Kurvenanalyse anzuzeigen.",
        bgTitleSyncOnly = "BREAST • Täglicher Sync aktiv",
        bgContentSyncOnly = "EPEX-Spot-Automatisierung aktiv  •  Überwacht Spotpreise",
        bgBigTextSyncOnly = "BREAST EPEX-Spot-Automatisierung ist im Hintergrund aktiv.\n" +
            "• Täglicher Sync um 12:00 Uhr MEZ & stündliche Prüfung von 12:00 bis 15:00 Uhr\n" +
            "Tippen, um das Dashboard zu öffnen.",
        interval1Min = "1 Min.",
        interval1Hour = "1 Std.",
        intervalNMinFormat = "%d Min."
    )

    private val NOTIF_ZH = NotificationStrings(
        channelAuthName = "SingleKey ID 登录提醒",
        channelAuthDesc = "博世 SingleKey ID 凭据过期或需要重新登录时的重要提醒",
        channelSyncName = "每日热泵调度同步",
        channelSyncDesc = "向热泵成功下发最新 EPEX Spot 电价运行计划时的通知",
        channelBgName = "后台遥测与自动化状态常驻通知",
        channelBgDesc = "指示 BREAST 正在后台持续进行热泵遥测采集与自动调度的常驻状态徽标",
        authAlertTitle = "需要登录博世 SingleKey ID",
        authAlertTapPrompt = "点击打开 BREAST 并登录，以便继续执行每日热泵调度。",
        syncStatusTitle = "热泵运行计划已更新",
        syncStatusSummaryFormat = "已将最惠时段 %.1f 小时 (%.1f°C) 下发至热泵 (%s)",
        bgTitleTelemetryAndAuto = "BREAST • 遥测记录与自动调度运行中",
        bgContentTelemetryAndAuto = "遥测采样每 %s  •  EPEX Spot 自动调度激活",
        bgBigTextTelemetryAndAuto = "BREAST 后台监控与自动化运行中。\n" +
            "• 每 %s 记录一次热泵实时遥测数据 (开发者模式)\n" +
            "• EPEX Spot 动态电价计划自动同步已启用 (每日 12:00–15:00)\n" +
            "点击查看热泵采暖曲线及调度看板。",
        bgTitleTelemetryOnly = "BREAST • 遥测与采暖曲线分析运行中",
        bgContentTelemetryOnly = "正在后台采集热泵运行遥测数据 (每 %s)",
        bgBigTextTelemetryOnly = "后台热泵遥测数据采集运行中。\n" +
            "• 每 %s 记录一次温度以重构出水采暖曲线\n" +
            "点击查看实时采样点与拟合曲线。",
        bgTitleSyncOnly = "BREAST • 每日动态调度运行中",
        bgContentSyncOnly = "EPEX Spot 自动调度激活  •  实时监控现货电价",
        bgBigTextSyncOnly = "BREAST EPEX Spot 自动化服务在后台持续运行。\n" +
            "• 每天 12:00 CET 执行基准同步，并在 12:00–15:00 每小时轮询\n" +
            "点击打开主面板查看详情。",
        interval1Min = "1 分钟",
        interval1Hour = "1 小时",
        intervalNMinFormat = "%d 分钟"
    )

    fun notificationsFor(lang: AppLanguage): NotificationStrings {
        return when (lang) {
            AppLanguage.DE -> NOTIF_DE
            AppLanguage.ZH -> NOTIF_ZH
            AppLanguage.EN -> NOTIF_EN
        }
    }

    fun localizeAuthErrorMessage(msg: String?, lang: AppLanguage): String {
        if (msg == null) return ""
        return when (lang) {
            AppLanguage.DE -> when {
                msg.contains("No SingleKey ID refresh_token available", ignoreCase = true) ||
                msg.contains("Not logged in to Bosch SingleKey ID", ignoreCase = true) ->
                    "Nicht bei Bosch SingleKey ID angemeldet. Bitte anmelden."
                msg.contains("Token expired or revoked", ignoreCase = true) ->
                    "Bosch SingleKey ID Token ist abgelaufen oder wurde widerrufen. Bitte erneut anmelden."
                else -> msg
            }
            AppLanguage.ZH -> when {
                msg.contains("No SingleKey ID refresh_token available", ignoreCase = true) ||
                msg.contains("Not logged in to Bosch SingleKey ID", ignoreCase = true) ->
                    "未登录博世 SingleKey ID，请点击登录。"
                msg.contains("Token expired or revoked", ignoreCase = true) ->
                    "博世 SingleKey ID 令牌已过期或已撤销，请重新登录。"
                else -> msg
            }
            AppLanguage.EN -> msg
        }
    }
}
