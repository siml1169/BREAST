# BREAST Project Status & Roadmap

**Current Version:** `1.0.0-rc2` (Daily Driver Usable)  
**Last Updated:** October 29, 2026  
**Build Status:** [![Build Status](https://img.shields.io/badge/Build-Passing%20(62%2F62%20tests)-brightgreen.svg)]()  

---

## 1. Feature Completion Matrix

| Component | Feature | Status | Notes |
| :--- | :--- | :---: | :--- |
| **Authentication** | Bosch SingleKey ID OAuth2 | ✅ Complete | In-app WebView interceptor, PKCE `S256` exchange |
| | Automatic Token Refresh | ✅ Complete | Refreshes access tokens via `refresh_token` automatically |
| | Credential Alert Notifications | ✅ Complete | Notifies user if SingleKey ID requires re-authentication |
| **Schedule Optimizer** | EPEX Spot DE-LU Day-Ahead Client | ✅ Complete | Multi-source fallback: Fraunhofer Energy-Charts & aWATTar DE |
| | Vattenfall Dynamic Tariff Calculation | ✅ Complete | Includes 19% VAT, grid fees ($21.34\text{ ct}$ net base floor), and regional surcharges |
| | Static Lowest-Price Window Finder | ✅ Complete | Discretized 15-min raster with exact runtime matching |
| | **Holistic MPC Optimization Engine** | ✅ Complete | 3-state building thermal inertia + Carnot COP model |
| | **4 Optimization Preferences** | ✅ Complete | Comfort, Balanced, Efficiency (COP / Bang for Buck), Budget Priority |
| | **Non-Negative Runtime Corridors** | ✅ Complete | $+0.0\text{ h}$ firm baseline floor across all modes; eliminates mild-weather optimizer collapse |
| | **Proportional Expansion Corridors** | ✅ Complete | $f(T_{\text{set}})$: Budget ($0\%$), Comfort ($+20\%$), Balanced ($+25\%$), Efficiency ($+35\%$) |
| | **Scheduler Quantizer Harmonization** | ✅ Complete | Formally snapped to discrete $0.5\text{ h}$ slider steps and $15\text{ min}$ raster slots |
| | **Budget Priority Strict Runtime Lock** | ✅ Complete | $r = 0.00 \implies \Delta H = 0.0\text{ h}$ zero voluntary stretch; locked to user slider |
| | **Two-Stage Budget Evaluation** | ✅ Complete | Normal corridor evaluated first; reports zero budget cut when budget is unthreatened |
| | **Marginal Cost Edge Shaving** | ✅ Complete | Trims least cost-effective edges ($\max c_{\text{heat}} = c_{\text{gross}}/\text{COP}$) when budget breached |
| | **Online Return Temp Learning** | ✅ Complete | `AdaptiveThermalObserver.kt`: Continuous nocturnal EMA adaptation of $H_{\text{bld}}$ and $\kappa$ |
| | **Window vs 24h Total Energy Metrics**| ✅ Complete | Double-tracking active heating electricity vs 24-hour total consumption ($50\text{ W}$ parasitic) |
| | Anti-Short-Cycling Safety | ✅ Complete | 90-minute minimum continuous run block protection |
| | Heating Temperature Slider | ✅ Complete | `15.0°C .. 30.0°C` (step `0.5°C`), adjusts `comfort2` |
| | Operation Duration Slider | ✅ Complete | `0.5 h .. 16.0 h` (step `0.5 h`) runtime selection |
| | Midnight Boundary Handling | ✅ Complete | Prevents state leakage when operating up to 24:00 |
| **Hardware Protection** | Thermostat Override Detection | ✅ Complete | Detects external setpoint / switch point modifications |
| | 3-Tap Overwrite Barrier | ✅ Complete | Prevents inadvertent auto-overwrites of manual heat pump changes |
| **Telemetry & Physics** | Telemetry Data Snapshot Model | ✅ Complete | Outdoor, Supply, Return, Condenser, Mod %, Demand |
| | SQLite Time-Series Persistence | ✅ Complete | Indexed `breast_telemetry.db` via `SQLiteOpenHelper` |
| | **25k Telemetry Window & Historical Anchor** | ✅ Complete | Fixed 25,000-point query horizon; retains running cumulative average of the last 25k points to incorporate multi-seasonal learning into new points without heap expansion |
| | **HPC410 Non-Linear Heating Curve** | ✅ Complete | Accurately renders manufacturer exponential supply curve |
| | **Closed-Loop Control Digital Twin** | ✅ Complete | `ControlUnitSimulator.kt`: screed ODE, return hysteresis (-1.2K/+0.5K), and standby |
| | **Inverter Modulation Model** | ✅ Complete | Empirical ground-truth calibration ($\text{Mod} \propto T_{\text{out}}$) with rich UI breakdown (2x2 KPI chips, PID startup transient isolation audit trail, $R^2$ quality badge, and dynamic operating corridor projection table) |
| | **Astronomical Nocturnal Setback Window** | ✅ Complete | NOAA solar equations: strictly $\ge 2\text{ hours}$ after sunset to sunrise; rich UI breakdown (2x2 KPI chips, 2R2C screed core mass coupling audit trail, $R^2$ quality badge) |
| | **Setpoint Consistency Filter** | ✅ Complete | Excludes anomalous high/low setpoint telemetry from regression |
| | **Building Heat Loss Tracking (Model 3)** | ✅ Complete | 2R2C State-Space Observer: continuous multi-point linear regression ($R^2 \ge 0.70$), dynamic participating thermal mass ($C_{\text{eff}} = C_{\text{screed}} + \alpha \cdot C_{\text{indoor}}$, $4.8 .. 13.3\text{ kWh/K}$), seamless midnight bridging, and multi-night weighting |
| | **Parasitic Load & Submeter Calibration** | ✅ Complete | Generous 50W rule of thumb continuous baseline (~25W secondary pump + ~15W electronics + primary pump); configurable variable prepared for external submeter calibration |
| | One-Tap Data Export | ✅ Complete | Exports complete database as CSV/JSON via Android Share |
| **Background Automation**| Hourly Price Check (`JobScheduler`) | ✅ Complete | Runs daily between 12:00 and 15:00 CET |
| | High-Frequency Telemetry Ticker | ✅ Complete | `AlarmManager.setAndAllowWhileIdle()` (`1 .. 60 min`) |
| | Ongoing Background Status Badge | ✅ Complete | Visible status bar icon + notification badge |
| | Boot & Package Replaced Receiver | ✅ Complete | Automatically restores cron and alarm ticks on reboot |
| | Unrestricted Battery Shortcut | ✅ Complete | Direct prompt to exempt app from aggressive OEM Doze |
| **User Interface** | System-Following Adaptive Theme | ✅ Complete | Follows Android system Light & Dark modes |
| | Pull-to-Refresh Gesture | ✅ Complete | Drag top to refresh live telemetry & sync schedule |
| | **Fullscreen Interactive Visualizer** | ✅ Complete | Landscape canvas with HPC 410 reference curves & live crosshair |
| | Persistent Overlay State | ✅ Complete | Selected graph overlays persist between fullscreen and main screen |
| | Android 15 Edge-to-Edge Keyboard Insets | ✅ Complete | Automatic scroll adjustment so IME keyboard never obscures inputs |
| | Real-Time Input Persistence | ✅ Complete | Budget and power draw inputs commit instantly on edit |
| | Card Separation | ✅ Complete | Distinct cards for Budget/Power and Background Automation |
| | Collapsible Developer / Debug Mode | ✅ Complete | Terminal logs and raw endpoint inspector hidden by default |

---

## 2. Hardware Environment Profile

* **Appliance:** Bosch Compress 7400i AW (`CS7400iAW 9 OR-S`)
* **Internal Controller:** Bosch **HPC 410** (`Id: 158`, FW `73.03`) + **CUHP** (`Id: 172`, FW `01.20`)
* **Gateway:** Bosch **Connect-Key K30 RF** (`ProductUuid 101845229`, FW `15.00.01`)
* **Hydraulic Circuit:**
  * Space Heating: Underfloor heating (`hc1`, `heatingType: floor`, max flow temp `50.0°C`)
  * Decoupling: **120L heating buffer tank** (*Trennspeicher*)
  * Auxiliary Heater: **9.0 kW staged immersion heater** (`eheater`)
  * Domestic Hot Water: Cylinder on motorized 3-way diverter valve (`dhw1`)

---

## 3. Milestone Completion Summary

### Milestone 1: Visual In-App Graph Rendering & Hardware Protection (✅ COMPLETE)
* Replaced linear empirical regression with the physical non-linear Buderus / Bosch Logamatic HPC410 supply curve:
  $$T_{\text{supply}} = T_{\text{base}} + \frac{T_{\text{design}} - T_{\text{base}}}{(-10 - 20)^\alpha} \cdot (T_{\text{outdoor}} - 20)^\alpha + \Delta T_{\text{parallel}}$$
* Fullscreen landscape interactive visualizer with persistent chip selection shared with dashboard.
* 3-Tap overwrite barrier and signature conflict detection preventing disruption of physical thermostat tweaks.

### Milestone 2: Hard Budget Caps & Economic Controls (✅ COMPLETE)
* Independent Card 4 (Operating Budget & Heat Pump Power) and Card 5 (Background Automation & EPEX Spot Sync).
* Strict hard budget cap (€/day) dynamically truncating schedule duration in both Holistic and Static modes.
* Calibrated average electrical power draw ($kW$) multiplying spot price rates to accurately enforce euro expenditure.
* Instant text watchers and keyboard IME insets preventing visual skewing.

### Milestone 3: Model Predictive Control (MPC) Holistic Integration (✅ COMPLETE)
* Developed [`HolisticScheduleOptimizer.kt`](../app/src/main/java/de/breast/optimizer/HolisticScheduleOptimizer.kt) integrating:
  1. [`BuildingEnergyBalanceModel.kt`](../app/src/main/java/de/breast/optimizer/BuildingEnergyBalanceModel.kt): 3-state building thermal inertia model calculating building heat loss rate and buffer storage.
  2. [`CopThermodynamicModel.kt`](../app/src/main/java/de/breast/optimizer/CopThermodynamicModel.kt): Thermodynamic Carnot heat pump COP curve scaling with temperature lift.
  3. [`WeatherForecastClient.kt`](../app/src/main/java/de/breast/weather/WeatherForecastClient.kt): Open-Meteo hourly weather forecasting.
  4. Multi-Objective Solver: Balanced, Comfort, Efficiency (COP), and Budget Priority with price arbitrage and runtime deficit penalties.
* Anti-short-cycling safeguards (90-minute minimum continuous run block).

### Milestone 4: Closed-Loop Digital Twin, Inverter Calibration & Two-Stage Budget Engine (✅ COMPLETE)
* **Closed-Loop Control Digital Twin (`ControlUnitSimulator.kt`):** Completely eliminated duty cycle heuristics; reproduces the exact physical closed-loop state machine of the Bosch HPC 410 / Rego control unit (return temperature cut-in hysteresis of $-1.2\text{ K}$, cut-out at $+0.5\text{ K}$, screed thermal ODE, and standby consumption).
* **Empirical Inverter Modulation & Telemetry Filtering (Model 3 2R2C State-Space Observer):** Calibrated compressor modulation directly from real-world historical telemetry ($\approx 42\%$ at $10^\circ\text{C}$, slope $5.0\%/\text{K}$), filtered out anomalous setpoints, and enforced strict astronomical nocturnal setback qualification (NOAA solar equations: $\ge 2\text{ hours}$ post-sunset to sunrise, heat pump strictly in setback mode $T_{\text{setpoint}} \le 18.5^\circ\text{C}$). Upgraded heat loss estimation to Model 3 (2R2C State-Space Dynamic Thermal Mass Observer): continuous multi-point linear regression across all $N$ block samples, dynamic participating capacitance ($C_{\text{eff}} = C_{\text{screed}} + \alpha \cdot C_{\text{indoor}}$, seamlessly decoupling fast screed discharge $4.8\text{ kWh/K}$ from slow whole-building equilibrium decay $13.3\text{ kWh/K}$), seamless midnight bridging (e.g. 19:00 to 04:15 next morning), and $R^2 \times \text{duration}$ multi-night weighted building heat loss calculation displayed in local CET time.
* **Two-Stage Budget Evaluation & Edge Shaving:** Evaluates candidate windows within the target corridor first; only curtails duration if the hard euro ceiling is genuinely breached. In Budget Priority, duration is strictly locked to target ($\Delta t = 0.0\text{h}$). When budget is breached, **Marginal Cost Edge Shaving** trims slots with highest delivered heat cost ($c_{\text{heat}} = c_{\text{gross}} / \text{COP}$) while protecting peak midday solar hours. Efficiency Priority expands runtime during daytime to cruise at lower modulation for maximum bang for buck.
* **Empirical Ground-Truth Energy Interpolation & Parasitic Baseline:** Calibrated against October 2026 month-to-date recordings ($448\text{ kWh}_{\text{th}}$ heat vs $64\text{ kWh}_{\text{el}}$ compressor electricity, average monthly COP $7.00$). Implemented reciprocal-lift thermodynamic COP interpolation unifying mild autumn ($\text{COP} \approx 7.0 - 7.5$ at $16\text{ K}$ lift) and deep winter ($\text{COP} = 4.0$ at $35\text{ K}$ lift) with $1/\text{COP} = 0.00542 \cdot \Delta T_{\text{lift}} + 0.060$ harmonized across `ControlUnitSimulator.kt`, `EmpiricalModulationAnalyzer.kt`, and `HolisticScheduleOptimizer.kt`. Added generous $50\text{ W}$ continuous parasitic baseline (~$1.20\text{ kWh/day}$) for secondary underfloor circulation pump (`hc1`) and electronics, with configurable variable in `UserSettingsStore` for external physical submeter calibration.
* **Closed-Loop Duty Cycle & Heat Demand Equilibrium:** Resolved the continuous 100% duty cycle artifact during long scheduled comfort windows. Bounded active compressor run time by the daily building thermal energy demand ($Q_{\text{th}} = \int H \cdot (T_{\text{in}} - T_{\text{out}}) dt + Q_{\text{recharge}}$) and aligned compressor rating to full $9.0\text{ kW}_{\text{th}}$ inverter rating. On mild days ($11.5^\circ\text{C}$), a 9.25-hour scheduled comfort window operates at $\approx 60-65\%$ duty cycle ($5.75\text{ h}$ active compressor time at $\approx 0.53\text{ kW}$ active electric power, plus $3.5\text{ h}$ idle circulation at $50\text{ W}$), yielding realistic electricity consumption of $3.23\text{ kWh}$ ($1.72\text{ €}$) instead of an unphysical $7.5\text{ kWh}$ ($3.99\text{ €}$).
* **25,000-Point Telemetry Horizon & Multi-Seasonal Anchor:** Expanded the database telemetry window from 500 to 25,000 datapoints with native SQLite aggregation and persistent Bayesian weighted prior anchor, ensuring long-term adaptation without heap or latency penalties.
* All 54 unit tests passing and deployed live on physical hardware (`Pixel 10 Pro - 17`).

### Milestone 5: Non-Negative Corridors, Proportional Runtime Expansion & Online Adaptive Learning (✅ COMPLETE)
* **Mathematical Root Cause Resolution for Runtime Contraction:** Resolved the issue where Budget mode strictly locked to target while Balanced and Efficiency unexpectedly contracted below user intent in mild weather. Identified the interaction between negative delta floors ($\text{minDeltaHours} = -0.5\text{ h}$), nominal hours blending against full inverter rating ($4.5\text{ kW}$ yielding $\approx 2.7\text{ h}$ demand), and the per-slot average optimization bias ($\frac{1}{N}\sum \text{COP}$ naturally favoring narrow midday peaks).
* **Non-Negative Runtime Corridors ($+0.0\text{ h}$ Firm Floor):** Anchored baseline minimum runtime strictly to `userTargetHours` across all modes, establishing the slider setting as a firm non-contracting floor.
* **Proportional Runtime Expansion Corridors ($f(T_{\text{set}})$):** Replaced static $1.5\text{–}2.0\text{ h}$ constants with dynamic proportional functions:
  * Budget Priority: $+0\%$ extra ($r = 0.00 \implies \Delta H = 0.0\text{ h}$ strictly locked).
  * Comfort Priority: $+20\%$ extra ($r = 0.20$).
  * Balanced: $+25\%$ extra ($r = 0.25$).
  * Efficiency Priority: $+35\%$ extra ($r = 0.35$).
* **Scheduler Quantizer Harmonization:** Mathematically snapped expansion hours to discrete scheduling quanta:
  $$\Delta H_{\text{quantized}} = \operatorname{round}\left(\frac{T_{\text{set}} \cdot r_{\text{expansion}}}{Q}\right) \cdot Q$$
  where $Q = 0.5\text{ h}$ ($30\text{ min}$) aligns with the user duration slider and partitions cleanly into $15\text{ min}$ raster slots.
* **Continuous Online Return Temperature Learning (`AdaptiveThermalObserver.kt`):** Implemented continuous nocturnal telemetry observation with EMA online parameter calibration ($H_{\text{bld}}$, cooling rate $\kappa$) filtered by strict $R^2 \ge 0.65$ quality thresholds, dynamically informing `BuildingEnergyBalanceModel`.
* **Window-Specific vs. 24h Total Energy & Cost Double-Tracking:** Separated active window operating consumption from continuous 24-hour parasitic draw ($50\text{ W}$ secondary circulation pump and electronics) across UI badges, sync states, and in-app audit reporting.
* **Thermodynamic Audit Breakdown (Section 5):** Added Section 5 ("Dynamic Runtime Corridor (Harmonized Quantizer)") to the physics modal, providing transparent visibility into dialed target, mode expansion percentage, quantizer step, search slot range, and applied schedule delta.
* All 62 unit tests passing and deployed live on physical hardware (`Pixel 10 Pro - 17`).

---

## 4. Operational Monitoring & Next Polish Items

With the core system achieving full daily-driver stability, ongoing observation during cold weather will focus on:
1. **Building Loss Coefficient Calibration ($H$):** Continuous validation of Model 3 2R2C State-Space Observer ($132\text{ W/K}$) during sub-zero cold snaps.
2. **External Submeter Calibration:** When an external electrical submeter (e.g. Shelly 3EM / Eastron SDM) is wired to the heat pump breaker, fine-tuning the parasitic baseline ($50\text{ W}$) to the measured plant room idle draw.
3. **Historical Savings Card (Optional):** Correlating `/recordings/heatSources/emon/*` historical energy with past spot prices to display exact historical euros saved compared to a flat tariff.
