# BREAST — Bosch Reverse Engineered API Smart Tool for Heatpump (BREAST Heatpump)

*Languages / Sprachen / 语言: **English** | [Deutsch](README_DE.md) | [中文](README_ZH.md)*

[![Build Status](https://img.shields.io/badge/Build-Passing%20(76%2F76%20tests)-brightgreen.svg)]()
[![Platform](https://img.shields.io/badge/Platform-Android%2014%2B%20%28API%2026%E2%80%9335%29-blue.svg)]()
[![Dev Environment](https://img.shields.io/badge/Dev%20Environment-Bosch%20Compress%207400i%20AW-orange.svg)]()
[![Dev Gateway](https://img.shields.io/badge/Dev%20Gateway-Bosch%20Connect--Key%20K30%20RF-purple.svg)]()
[![State](https://img.shields.io/badge/Status-Daily%20Driver%20Usable-success.svg)]()

**BREAST Heatpump** (**B**osch **R**everse **E**ngineered **A**PI **S**mart **T**ool for **Heatpump**) is a native Android Kotlin application designed to autonomously optimize the heating schedule and power consumption of **Bosch & Buderus Air-to-Water Heat Pumps** against dynamic day-ahead electricity spot market prices (**EPEX Spot DE-LU** via Vattenfall ÖkoStrom Dynamik).

The app interfaces directly with Bosch's **PointT Cloud API** (`pointt-api.bosch-thermotechnology.com`) using **SingleKey ID OAuth2 + PKCE**, logs high-resolution system telemetry into an on-device SQLite database, accurately renders the physical **non-linear Buderus/Bosch HPC410 heating curve**, solves an advanced **Model Predictive Control (MPC) building energy balance model**, simulates the closed-loop control unit digital twin, and operates autonomously using Android background daemons.

---

## Key Features

### 1. Dual Schedule Optimization Modes

#### A. Holistic Optimization Engine (MPC & Thermodynamics)
* **Building Energy Balance Model (`BuildingEnergyBalanceModel`):** Continuously models indoor thermal mass and heat loss rate:
  $$Q_{\text{loss}}(t) = H \cdot (T_{\text{in}}(t) - T_{\text{out}}(t))$$
  Quantifying floor screed and 120L buffer heat retention during setback hours.
* **Thermodynamic Heat Pump COP Model (`CopThermodynamicModel`):** Models Carnot lift scaling with outdoor ambient temperature:
  $$\text{COP}(t) = \eta_C \cdot \frac{T_{\text{supply}}(t) + 273.15}{T_{\text{supply}}(t) - T_{\text{out}}(t)}$$
  Favoring warmer afternoon outdoor temperatures when thermodynamic efficiency peaks.
* **Closed-Loop Control Digital Twin (`ControlUnitSimulator`):** Reproduces the exact state machine of the Bosch HPC 410 / Rego control unit, including return temperature cut-in hysteresis ($-1.2\text{ K}$) and cut-out ($+0.5\text{ K}$), concrete screed thermal differential ODE, and standby consumption.
* **Non-Negative Runtime Corridors ($+0.0\text{ h}$ Firm Floor):** The user's Target Daily Runtime slider acts as a firm, non-contracting floor across all modes, preventing mild-weather optimizer collapse while permitting one-sided daytime solar expansion.
* **Proportional Runtime Expansion & Scheduler Quantizer Harmonization:** Eliminates arbitrary static hour caps by scaling allowable extra runtime dynamically as a function of dialed target hours ($T_{\text{set}}$):
  $$\Delta H_{\text{quantized}} = \text{round}\left(\frac{T_{\text{set}} \times r_{\text{expansion}}}{Q}\right) \times Q$$
  where $Q = 0.5\text{ h}$ ($30\text{ min}$) matches the duration slider and partitions into exact integer multiples of $15\text{ min}$ raster slots.
* **4 Tailored Optimization Preferences:**
  1. **Comfort Priority:** Minimizes indoor temperature variance ($w_c = 0.60$), allowing up to $+20\%$ extra runtime to prevent floor slab cooldown during active occupancy.
  2. **Balanced (Recommended):** Optimal three-way balance between electricity price, thermodynamic COP lift, and comfort, allowing up to $+25\%$ extra runtime for midday solar and low-price capture.
  3. **Efficiency (COP / Bang for Buck) Priority:** Maximizes delivered thermal heat per euro ($\frac{\text{COP}}{c_{\text{gross}}}$), allowing up to $+35\%$ extra daytime expansion to cruise at low inverter modulation ($25\%\text{–}35\%$) across solar noon hours ($10:00\text{–}16:30$) within budget.
  4. **Budget Priority:** **Strict Runtime Lock (+0% Extra)** ($r = 0.00 \implies \Delta H = 0.0\text{ h}$). Evaluates the single cheapest continuous spot price window matching the exact slider duration without voluntary stretch or shrinkage.
* **Anti-Short-Cycling Compressor Protection:** Enforces minimum continuous operating blocks of 90 minutes.

#### B. Standard / Static Runtime Mode
* When Holistic Optimization is turned off, the scheduler strictly adheres to your configured **Target Daily Runtime Slider** (e.g., 6.0h), locating the cheapest spot price trough of the day.

---

### 2. Budget Cap, Two-Stage Evaluation & Marginal Cost Edge Shaving
* **Two-Stage Budget Evaluation:** Evaluates candidate windows strictly within the target duration corridor first. If the corridor window fits within `dailyBudgetEuro`, the full window is executed with `budgetCutMinutes = 0` (zero false budget-cut reporting).
* **Marginal Cost Edge Shaving:** If and only if every window in the target corridor breaches the hard budget ceiling, the optimizer compares the delivered heat cost $c_{\text{heat}} = \frac{c_{\text{gross}}}{\text{COP}}$ at the start edge ($S$) vs. the end edge ($E - 1$). It iteratively trims 15-minute slots from whichever edge is less cost-effective until within budget, preserving solar noon peak COP.
* **Dynamic Inverter Power Modulation & Empirical Interpolation:** Rather than assuming a static flat power draw (e.g. 2.0 kW), the optimizer dynamically calculates compressor electrical draw based on building heat demand ($H_{\text{bld}} = 132\text{ W/K}$), empirical reciprocal-lift COP interpolation ($\text{COP} \approx 8.7$ in mild weather down to $4.09$ in deep sub-zero winter), and the physical inverter clamp ($28\%\text{–}100\%$, $\approx 0.35\text{–}0.70\text{ kW}$ on mild days). Continuous parasitic loads (secondary pump + standby electronics, generous 50W rule of thumb) are factored in with dedicated external submeter calibration support. Card 4 defaults to `Auto (~0.6kW)` or accepts manual fixed power overrides.
* **Dual-Mode Protection:** Works identically in both Holistic Mode and Standard Mode.
* **Zero-Price Opportunity Extension:** Absorbs negative/zero spot price energy into the floor screed, strictly guarded by the daily budget limit.
* **Instant UI Input Persistence:** Changes to daily budget or average heat pump power draw (kW) persist immediately on typing without requiring field defocus.

---

### 3. Controller Manual Adjustment Protection (3-Tap Shield)
* **Hardware Override Shield:** If anyone adjusts the room thermostat on the wall or alters switch points in the official Bosch HomeCom app, BREAST detects the schedule signature divergence.
* **Auto-Sync Protection:** The background daemon halts automatic overwrites to respect manual user intervention.
* **3-Tap Confirmation Barrier:** Overwriting manual heat pump modifications from the app requires tapping "Sync" three times with an explicit warning banner, preventing accidental schedule resets.

---

### 4. Interactive HPC410 Heating Curve & Telemetry Visualizer
* **Physical Non-Linear HPC410 Curvature:** Accurately plots the manufacturer's non-linear supply water curve:
  $$T_{\text{supply}} = T_{\text{base}} + \frac{T_{\text{design}} - T_{\text{base}}}{(-10 - 20)^\alpha} \cdot (T_{\text{outdoor}} - 20)^\alpha + \Delta T_{\text{parallel}}$$
  with customizable base point, design point (e.g., $35^\circ\text{C}$ @ $-10^\circ\text{C}$ for underfloor heating), and parallel shift ($K$).
* **Fullscreen Landscape Visualizer:** Tap anywhere to expand into an interactive landscape graph featuring:
  * HPC410 Reference Curves [1–5].
  * Steady-state equilibrium points vs transient warm-up points.
  * Live crosshair operating point overlay ($T_{\text{outdoor}}, T_{\text{supply}}$).
  * Persistent chip selection shared seamlessly with the main dashboard.
* **Real-Time Sensor Badges:** Displays outdoor temperature, supply flow, return flow, underfloor temperature drop ($\Delta T$), compressor modulation level (%), and active demand state (`ch`, `dhw`, `idle`).
* **Empirical Building Heat Loss Derivation & Online Learning (`SetbackHeatLossEstimator` & `AdaptiveThermalObserver`):**
  * **Continuous Online Return Temperature Learning (`AdaptiveThermalObserver`):** Continually monitors return temperature cooldown during night setback, performing online EMA updates of building loss ($H_{\text{bld}}$) and cooling rate ($\kappa$) filtered by strict $R^2 \ge 0.65$ thresholds. Dynamically informs `BuildingEnergyBalanceModel` of live screed reheat deficits.
  * **Strict NOAA Astronomical Nocturnal Filter:** Restricts cooldown samples strictly to $\ge 2\text{ hours}$ after sunset until sunrise, eliminating daytime solar insolation and exterior envelope thermal storage transients. Seamlessly bridges nocturnal blocks across calendar midnight (e.g. 19:00 to 04:15 next morning).
  * **Setback Mode Verification:** Confirms the heat pump is strictly operating in setback/eco mode ($T_{\text{setpoint}} \le 18.5^\circ\text{C}$ or $T_{\text{setpoint}} \le T_{\text{comfort}} - 2.0\text{ K}$, compressor OFF, modulation $0\%$, heating and DHW idle, hydraulic loop equalized $\le 1.5\text{ K}$). Immediately cuts off when comfort heating resumes (e.g. at 04:15 CET).
  * **2R2C Dynamic Participating Thermal Capacity:** Distinguishes active floor screed buffer discharge ($T_{\text{return}} \ge T_{\text{comfort}} + 1.0\text{ K} \implies C_{\text{eff}} = C_{\text{screed}} \approx 4.8\text{ kWh/K}$) from coupled whole-building equilibrium decay ($T_{\text{return}} \le T_{\text{comfort}} \implies C_{\text{eff}} = C_{\text{screed}} + C_{\text{indoor}} \approx 13.3\text{ kWh/K}$) with smooth continuous interpolation $\alpha \in [0.0, 1.0]$.
  * **Continuous Least-Squares Linear Regression:** Computes slope and $R^2$ across all $N$ block samples, ensuring robust signal tracking without point-to-point end-point noise ($R^2 \ge 0.70$, cooling rate $\ge 0.04\text{ K/h}$).
  * **Multi-Night $R^2 \times \text{Duration}$ Weighted Averaging:** Aggregates valid nocturnal periods across multiple days into an accurate, resilient building heat loss rating ($W/K$), rendered with local CET timestamps.
  * **Target Setpoint Consistency Filter:** Filters out anomalous setpoint changes ($25.5^\circ\text{C}$ tests) from modulation and heating curve fitting.
  * **Window-Specific vs 24-Hour Total Energy Accounting:** Separates active window operating consumption from 24-hour total energy including the continuous unmetered parasitic draw of the secondary underfloor circulation pump (~25W) and system control electronics (~15W) with a generous 50W default rule of thumb (~$1.2\text{ kWh/day}$), fully prepared with a configurable variable for external physical submeter calibration (e.g. Shelly 3EM / Eastron SDM).

---

### 5. Electricity Tariff Compositor & Multi-Model Pricing
* **Dynamic EPEX Spot Compositor:** Allows manual calibration of electricity contract components:
  $$P_{\text{gross}} = (P_{\text{spot}} + P_{\text{base}}) \times (1 + r_{\text{VAT}}) + F_{\text{monthly}}$$
* **Direct Precision Text Input Boxes:** Replaces coarse sliders with clean decimal input boxes (`EditText`) across all pricing parameters (e.g., `21.3446 ct/kWh net` base working price, `13.80 €/month` base fee, `19.0%` VAT). Supports European comma `,` and standard dot `.` keypad input without touch slider friction.
* **Configurable VAT Adjuster:** Features direct decimal input and one-tap regional presets (`0% Exempt`, `7% Reduced`, `19% Germany`, `20% Austria`) with real-time formula recalculation and gross rate preview.
* **Simplified Peak / Off-Peak (ToU HT/NT):** Supports two-tier electricity plans with direct text input for Peak (HT) and Off-Peak (NT) gross rates, configurable nocturnal off-peak timetable (e.g., 22:00 to 06:00), and all-day weekend off-peak toggle. Automatically synthesizes 15-minute price slots so downstream MPC optimizers steer heat pump runs into off-peak windows without external API dependencies.
* **Flat Rate (Festpreis):** Constant electricity price mode with direct rate text input where the MPC solver optimizes schedule timing purely around Carnot COP, ambient weather lift, and solar radiation.

---

### 6. Background Automation & Battery Resilience
* **Automated Hourly EPEX Spot Sync (`SpotScheduleJobService`):** Queries EPEX Spot Day-Ahead prices hourly (12:00–15:00 CET release window) and safely stages tomorrow's schedule without disturbing today's active cycle.
* **High-Frequency Telemetry Ticker (`AlarmManager`):** Gathers telemetry in the background even during deep Android Doze.
* **Android 15 Edge-to-Edge & System Theme Integration:** Fully compliant with Android 15 (targetSdk 35) with custom `@style/Theme.BreastApp` (`windowNoTitle = true`, `windowActionBar = false`). Handles system bar and camera punch-hole display cutouts (`WindowInsetsCompat.Type.displayCutout()`) alongside dynamic `WindowInsetsCompat.Type.ime()` soft-keyboard adjustment. Top navigation features a single canonical `⚙ Settings` button, and all empirical model badges feature horizontal scrolling and single-line vertical centering to prevent text squishing.

---

## Development & Testbed System Specifications

* **Heat Pump:** Bosch Compress 7400i AW (`CS7400iAW 9 OR-S`)
  * **Outdoor Unit:** Modulating air-to-water inverter compressor (single-phase 230V, rated $P_{el} \approx 0.5 - 2.2\text{ kW}$).
  * **Indoor Hydraulic Tower:** AWM 9 / AWMB 9 with integrated **9.0 kW staged auxiliary electric heater** (`eheater`).
  * **Hydraulic Decoupling:** **120L heating buffer tank** (*Pufferspeicher*) allowing unified CH and DHW scheduling without starving underfloor flow.
  * **Post-DHW Cycle Hydraulic Dynamics & Sensor Flush:** When domestic hot water heating completes, the motorized 3-way diverter valve returns from the $55^\circ\text{C}$ DHW cylinder to the underfloor heating circuit ($22^\circ\text{C}-25^\circ\text{C}$). The primary circulation pump immediately flushes cool return water across the indoor temperature sensors (`T_supply`, `T_return`), causing a rapid sensor temperature drop (apparent circuit cooldown, not tank heat loss). Following a ~3-minute compressor pressure equalization dwell, the HPC 410 detects the return water falling $\ge 1.2\text{ K}$ below the heating curve target and safely resumes space heating.
* **Master Controller:** **HPC 410** (`Id: 158`, FW `73.03`, Part `8737712760`) & **CUHP** (`Id: 172`, FW `01.20`) via EMS 2.0 bus.
* **Gateway:** **Connect-Key K30 RF** (`K30RF_v1`, FW `15.00.01`).

---

## Project Structure

```
beautiful-heisenberg/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml          # Permissions, JobService, BootReceiver, OAuth URI
│   │   │   ├── res/values/styles.xml        # Theme.BreastApp (NoActionBar, Android 15 Edge-to-Edge)
│   │   │   └── java/de/breast/
│   │   │       ├── api/
│   │   │       │   └── PointTApiClient.kt   # Bosch PointT Cloud REST client & telemetry fetcher
│   │   │       ├── auth/
│   │   │       │   ├── AndroidPrefsStores.kt# SharedPreferences stores (Tokens, Settings, State)
│   │   │       │   └── SingleKeyAuthManager.kt # SingleKey ID OAuth2 + PKCE manager
│   │   │       ├── daemon/
│   │   │       │   └── AutoScheduleDaemon.kt# JobScheduler, AlarmManager, hourly cron cycle
│   │   │       ├── optimizer/
│   │   │       │   ├── BuildingEnergyBalanceModel.kt # 3-state building heat loss & thermal mass
│   │   │       │   ├── ControlUnitSimulator.kt       # Closed-loop HPC 410 / Rego digital twin
│   │   │       │   ├── CopThermodynamicModel.kt      # Heat pump Carnot COP thermodynamic curve
│   │   │       │   ├── HolisticScheduleOptimizer.kt  # Multi-objective MPC optimization engine
│   │   │       │   └── SwitchProgramScheduler.kt     # Spot raster scheduler & budget cutter
│   │   │       ├── spot/
│   │   │       │   ├── SpotPriceClient.kt   # Energy-Charts & aWATTar DE EPEX price client
│   │   │       │   └── SpotPriceSource.kt   # Dynamic spot source definitions
│   │   │       ├── telemetry/
│   │   │       │   ├── HeatPumpTelemetrySnapshot.kt # Immutable telemetry snapshot model
│   │   │       │   ├── HeatingCurveAnalyzer.kt     # Heating curve evaluation & thermal decay
│   │   │       │   ├── SetbackHeatLossEstimator.kt # NOAA astronomical nocturnal heat loss regression
│   │   │       │   └── SqliteTelemetryStore.kt     # SQLite database for time-series logging
│   │   │       ├── ui/
│   │   │       │   ├── HeatingCurveChartView.kt    # Non-linear HPC410 Canvas chart view
│   │   │       │   ├── HeatingCurveFullscreenActivity.kt # Fullscreen interactive visualizer
│   │   │       │   ├── MainActivity.kt             # Main mobile dashboard & card layout
│   │   │       │   └── SettingsActivity.kt         # Advanced settings & calibration
│   │   │       └── weather/
│   │   │           └── WeatherForecastClient.kt    # Open-Meteo hourly weather client
│   │   └── test/java/de/breast/
│   │       ├── BreastCoreTest.kt            # Schedule, PKCE, Budget & Holistic MPC unit tests
│   │       └── telemetry/
│   │           └── HeatingCurveAnalyzerTest.kt # Curve fitting & 24h profile unit tests
│   └── build.gradle.kts                     # Android app build configuration
├── build.gradle.kts                         # Root Gradle build configuration
├── docs/
│   ├── ARCHITECTURE.md                      # Comprehensive technical architecture ([DE](ARCHITECTURE_DE.md), [ZH](ARCHITECTURE_ZH.md))
│   ├── HEAT_PUMP_OPTIMIZATION_PLAN.md       # Holistic optimization system v2.0 specification & physical rationale
│   ├── POSTMORTEM.md                        # Reverse engineering discoveries & root causes
│   └── PROJECT_STATUS.md                    # Current feature matrix and roadmap status
├── scripts/
│   ├── analysis/                            # Offline inspection of telemetry DBs & PointT recordings
│   ├── simulation/                          # Optimizer simulations & ad-hoc regression scripts
│   ├── adb-singlekey-inspect.ps1            # ADB helper for the SingleKey ID OAuth redirect
│   ├── check_sync_log.py                    # Read the app's last sync log from the phone via ADB
│   ├── fetch_emon.py                        # Download PointT energy-monitor (emon) recordings
│   ├── probe_emon_details.ps1               # Probe PointT energy-monitor (emon) endpoints
│   └── probe_power_endpoints.ps1            # Probe PointT heat-source power & energy endpoints
└── data/
    ├── pointt/                              # PointT API recordings & probe results (JSON)
    └── telemetry/                           # SQLite telemetry databases
```

---

## Toolchain & Verification

* **Gradle:** `9.5.0`
* **Java / JBR:** OpenJDK `25.0.2` (Android Studio JBR / JDK 17 compatible)
* **Android Gradle Plugin:** `9.3.3`
* **Kotlin:** `2.2.10`
* **Target SDK:** Android 35 (`compileSdk = 35`, `minSdk = 26`)
* **Unit Tests:** **76 / 76 Passing**

### Running Unit Tests Locally
Run unit tests from the project root:
```bash
./gradlew testDebugUnitTest
```

### Running on Device
1. Clone the repository and open it in Android Studio:
   ```bash
   git clone https://github.com/siml1169/BREAST.git
   ```
2. Connect your Android phone via USB (or wireless ADB).
3. Click **Run (`app`)** or install the pre-compiled `app-release.apk`.
4. In the app, tap **Connect Bosch SingleKey ID**, authenticate once, and tap **Sync Lowest-Price Schedule Now**.

### Building the Distribution Release APK
To assemble the signed production APK:
```bash
./gradlew assembleRelease
```
The compiled APK is output to `app/build/outputs/apk/release/app-release.apk`.

### Hardware Scope & Supported Circuits
* **Primary Heating Circuit:** BREAST Heatpump schedules switch points for primary heating circuit **`hc1`** (`/heatingCircuits/hc1/switchPrograms/heating/switchPoints`). Systems with dual circuits (`hc1` underfloor + `hc2` radiators) should maintain `hc2` on standard controller schedules.
* **Domestic Hot Water:** Observes `/dhwCircuits/dhw1` telemetry and coordinates setback states to prevent interference during hot water storage recharge.

---

## Safety Notice & License

This project is open-source under the [MIT License](LICENSE).

> [!CAUTION]
> **HVAC Plant Safety Notice:** BREAST Heatpump writes heating switch points directly to your Bosch/Buderus cloud gateway. While the system respects physical minimum compressor cycle times and building thermal limits, users must observe appliance behavior during initial commissioning. The authors assume no liability for hardware wear, unintended electrical bills, or comfort variance.

---

## Acknowledgments & Prior Art

This project stands on the shoulders of the open-source smart-home and reverse-engineering community who investigated Bosch Thermotechnology and SingleKey ID protocols before us:

* **[gladys-bosch-homecom](https://github.com/guim31/gladys-bosch-homecom)** by [Guillaume Morin](https://github.com/guim31) — Provided foundational insights into Bosch's PointT Cloud API endpoints (`pointt-api.bosch-thermotechnology.com`) and gateway discovery structures.
* **[bosch-homecom-hass](https://github.com/serbanb11/bosch-homecom-hass)** by [@serbanb11](https://github.com/serbanb11) — The Home Assistant integration wiki that thoroughly documented K30 RF (cloud-only) vs. K40 RF (local API) capabilities, telemetry endpoints, and gateway behaviors.
* **[homecom_alt](https://github.com/karl2510/homecom_alt)** by [@karl2510](https://github.com/karl2510) — Invaluable reference for the SingleKey ID OAuth2 PKCE authorization flow, client ID scopes, and mobile redirect URI handling.
* **[bosch-thermostat-client-python](https://github.com/pschmitt/bosch-thermostat-client-python)** by [Pawel Schmitt](https://github.com/pschmitt) and contributors — Pioneering work deciphering the nested Bosch resource trees, read/write permissions, and thermostat curves.
* **[Open-Meteo](https://open-meteo.com/) & Deutscher Wetterdienst (DWD)** — Open-source access to high-precision local solar radiation and temperature forecasts (DWD ICON).
* **[Fraunhofer ISE Energy-Charts](https://api.energy-charts.info/) & [aWATTar](https://api.awattar.de/)** — Open APIs providing reliable European EPEX Spot day-ahead electricity prices.
