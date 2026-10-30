# BREAST Heatpump Engineering Postmortem & Technical Discoveries

This document captures the reverse-engineering breakthroughs, root cause investigations, technical dead-ends, and architectural decisions made during the development of **BREAST Heatpump** (*Bosch Reverse Engineered API Smart Tool for Heatpump*).

---

## 1. Bosch SingleKey ID OAuth2 + PKCE Integration

### The Challenge
Bosch's SingleKey ID authorization server (`https://singlekey-id.com/auth/connect/authorize`) does not support standard `localhost` or custom HTTP redirect URIs for third-party client IDs. The official PointT client ID (`762162C0-FA2D-4540-AE66-6489F189FADC`) enforces an internal Android custom scheme redirect:
`com.bosch.tt.dashtt.pointt://app/login?code=...`

### The Solution
Instead of requiring an external web browser or custom server relay, we implemented an in-app `WebViewClient` interceptor in [`MainActivity.kt`](../app/src/main/java/de/breast/ui/MainActivity.kt):
1. Loads the SingleKey ID login form inside a native floating dialog.
2. Intercepts `shouldOverrideUrlLoading` when the URL prefix matches `com.bosch.tt.dashtt.pointt://app/login`.
3. Extracts the authorization `code` and dismisses the web view dialog.
4. Performs the RFC 7636 **PKCE (`S256`)** code verifier exchange via [`SingleKeyAuthManager.kt`](../app/src/main/java/de/breast/auth/SingleKeyAuthManager.kt) directly to `https://singlekey-id.com/auth/connect/token`.
5. Automatically refreshes access tokens using the offline `refresh_token` before every API session.

---

## 2. PointT API SwitchProgram Serialization Bug

### The Challenge
Initial attempts to write switch programs to `/heatingCircuits/hc1/switchPrograms/B` resulted in `HTTP 400 Bad Request` or `HTTP 422 Unprocessable Entity` errors, despite valid authentication tokens.

### Root Cause Analysis
Different generations of Bosch gateways (KM200 vs K30 Connect-Key) use conflicting JSON payload structures:
* **Legacy KM200 Format:** Wrapped object with a `switchPoints` key:
  ```json
  { "switchPoints": [ {"dayOfWeek": "Mo", "setpoint": "comfort2", "time": 180} ] }
  ```
* **Modern PointT / K30 Format:** By decompiling the official `com.bosch.tt.dashtt` Android APK and inspecting the Kotlin `@JvmInline value class PutSwitchPoints`, we discovered that the K30 expects a **naked, top-level JSON array**:
  ```json
  [ {"dayOfWeek": "Mo", "setpoint": "comfort2", "time": 180} ]
  ```
  Successful submission returns `HTTP 204 No Content`.

### The Solution
Implemented automatic fallback in [`PointTApiClient.putSwitchProgramDetailed()`](../app/src/main/java/de/breast/api/PointTApiClient.kt): the client transmits the top-level array format first, falling back to the wrapped object format if the gateway rejects it.

---

## 3. The "Local API vs Cloud API" Reality on K30 RF

### The Challenge
Community Home Assistant integrations (`serbanb11/bosch-homecom-hass`) describe a "Local Network API" capable of polling instantaneous electrical power draw in Watts ($W$) directly over the local LAN.

### The Discovery
Probing the local network IP (`192.168.178.110`) and investigating hardware documentation revealed a fundamental hardware split:
* **Bosch K 40 RF Gateway:** Features dual buttons (**WLAN** + **Wireless**), a blue status LED, and runs a local read-only HTTP server (firmware $\ge 15.00.01$) enabled by pressing both buttons simultaneously for 1 second.
* **Bosch K 30 RF Gateway (Our Device):** Has a **single button**, lacks the local web server entirely, and was excluded from Bosch's local API firmware. Any local token request fails with `"gateway not reachable"`.

### The Cloud API Solution
Because the K30 communicates exclusively with the PointT Cloud, we probed all cloud endpoints and identified real-time equivalents:
1. **`/heatSources/actualModulation`**: Returns live compressor modulation level (`38.0%`).
2. **`/heatSources/actualHeatDemand`**: Returns live demand state (`["dhw"]`, `["ch"]`, or `[]`).
3. Multiplying modulation percentage by the rated compressor power ($2.0\text{ kW} \times 0.38 = 0.76\text{ kW}$) accurately models instantaneous electrical power draw.

---

## 4. Unlocking Energy Recordings (`/recordings/`)

### The Challenge
Direct `GET` requests to `/recordings/heatSources/emon/total/compressor` returned `HTTP 403 Forbidden`.

### The Discovery
Bosch's PointT cloud recording resources are of type `yRecording`. The server rejects requests without an explicit time interval query parameter. By querying with:
`?interval=YYYY-MM-DD`
the cloud server returned complete, unredacted 24-hour hourly history in **`kWh`**:
* `/recordings/heatSources/emon/total/compressor?interval=YYYY-MM-DD`: Hourly electrical energy consumed by the compressor.
* `/recordings/heatSources/emon/total/eheater?interval=YYYY-MM-DD`: Hourly electrical energy consumed by the auxiliary electric heater.
* `/recordings/heatSources/emon/total/outputProduced?interval=YYYY-MM-DD`: Hourly thermal heat delivered to the home.

Querying with `?interval=YYYY-MM` unlocked monthly day-by-day energy records, proving our heat pump achieved a seasonal COP of **$4.09$** in peak winter (January 2026: 2,044 kWh heat produced from 458 kWh compressor + 42 kWh heater).

---

## 5. System Identification: HPC 410, CUHP, and CS7400iAW 9 OR-S

### The Discovery
Querying `/system/info` returned:
* `HPC410` (Bedieneinheit, Article `8737712760`, Firmware `73.03`).
* `CUHP` (Control Unit Heat Pump, Firmware `01.20`).
* `K30RF` (Connect-Key Gateway, Firmware `15.00.01`).

Cross-referencing technical service documentation confirmed the installation is a **Bosch Compress 7400i AW (`CS7400iAW 9 OR-S`)**:
* Single-phase 230V modulating inverter compressor ($P_{el} \approx 0.5 - 2.2\text{ kW}$).
* AWM 9 indoor hydraulic module with an integrated **9.0 kW staged auxiliary electric immersion heater** (`eheater`).

---

## 6. Unified vs Decoupled CH / DHW Scheduling

### The Debate
Because Domestic Hot Water (DHW) requires $50^\circ\text{C}-55^\circ\text{C}$ water and space heating requires only $28^\circ\text{C}-32^\circ\text{C}$, heat pumps enforce **Warmwasservorrang** (DHW priority via a motorized 3-way valve). 

We considered whether BREAST should decouple DHW into a dedicated midday slot to avoid interrupting floor heating during cold night runs.

### The Decisive Factor: The 120L Buffer Tank
The installation includes a **120L heating buffer tank** (*Pufferspeicher / Trennspeicher*). 
* When the heat pump diverts output to charge the hot water cylinder for 45 minutes, the secondary circulation pump draws heat from the 120L buffer into the underfloor heating without interruption.
* Combined with the thermal mass of the floor screed, the building never experiences a temperature drop.
* **Decision:** Keeping both circuits on a **unified schedule** (running during the cheapest spot hours) maximizes electricity cost savings, prevents fragmented switch points, and provides a vastly simpler user experience.

---

## 7. Single SwitchPoint & Midnight Boundary Handling

### The Problem
When the cheapest spot price block terminates at the end of the day (`24:00`), standard schedulers often force an extra switch point at `23:45` to turn OFF, wasting 15 minutes of the cheapest power. However, setting only a single ON switch point risked the heat pump inheriting the ON state across midnight into the next day.

### The Solution
Refined [`SwitchProgramScheduler.kt`](../app/src/main/java/de/breast/optimizer/SwitchProgramScheduler.kt) to support single switch points extending up to `24:00` while ensuring that when the next day's schedule is pushed, its leading state is explicitly grounded, eliminating status leakage without burning switch points.

---

## 8. Android Background Execution: Alarms vs JobScheduler

### The Problem
Android's `JobScheduler` imposes a strict **15-minute minimum periodic interval** (`JobInfo.getMinPeriodMillis()`) and allows the OS to delay jobs unpredictably under Doze. This made testing and high-frequency telemetry logging impossible.

### The Solution
We adopted a dual background execution strategy in [`AutoScheduleDaemon.kt`](../app/src/main/java/de/breast/daemon/AutoScheduleDaemon.kt):
1. **Daily / Hourly Schedule Sync:** Handled via `JobScheduler` (`SpotScheduleJobService`) for battery-friendly daily price checks between 12:00 and 15:00 CET.
2. **High-Frequency Telemetry Sampling:** Driven by `AlarmManager.setAndAllowWhileIdle()` with `SCHEDULE_EXACT_ALARM`, triggering `BootCompletedReceiver` at the user's exact chosen frequency (down to **1 minute**), persisting snapshots to SQLite even during deep sleep.

---

## 9. Empirical Linear Regression vs Physical HPC410 Non-Linear Curvature

### The Problem
Early iterations implemented an empirical linear regression engine ($T_{\text{supply}} = a - b \cdot T_{\text{outdoor}}$) on recorded central heating telemetry points. While statistically simple, linear regression proved physically inaccurate because:
1. Heating plant supply curves are inherently non-linear; radiator and underfloor emitters exhibit exponential heat output characteristics ($\dot{Q} \propto (T_{\text{flow}} - T_{\text{room}})^n$).
2. During mild weather seasons ($10^\circ\text{C} - 16^\circ\text{C}$), outdoor temperatures hover in a tight band. Linear regression on noisy telemetry produced erratic slopes ($0.05$ to $1.20$), having no relationship to the true Bosch control curve.

### The Solution
We eliminated the empirical linear fit and implemented the exact non-linear physical curve equation utilized by the **Bosch HPC 410 / Buderus Logamatic** controller:
$$T_{\text{supply}} = T_{\text{base}} + \frac{T_{\text{design}} - T_{\text{base}}}{(-10 - 20)^\alpha} \cdot (T_{\text{outdoor}} - 20)^\alpha + \Delta T_{\text{parallel}}$$
where $\alpha \approx 0.8$. The visualizer displays real steady-state and transient scatter points overlaid directly onto this true physical reference curve.

---

## 10. Multi-Objective Optimization: Budget Priority Undercutting Bug

### The Problem
In early Holistic Optimization iterations, `rawBudget` was formulated as total daily electricity spend:
$$J_{\text{budget}} = \sum_{t} \left(\frac{c_{\text{gross}}(t)}{100} \cdot P_{\text{el}} \cdot \Delta t\right)$$
When normalized against other objectives into $[0, 1]$, shorter durations naturally resulted in lower total euro cost ($J_{\text{budget}} \to 0$ as duration $\to 0$). Under Budget Priority mode ($w_{\text{budget}} = 0.60$), the savings from reducing runtime always outweighed the comfort deficit penalty, causing the optimizer to severely undercut the user's requested runtime (e.g. cutting 6.0h down to 1.5h).

### The Solution
We redefined the budget objective to optimize **price arbitrage** (seeking the lowest electricity rate per delivered kWh) rather than minimizing duration:

$$J_{\text{budget}} = \bar{c}_{\text{gross}} = \frac{1}{N} \sum_{t \in W} c_{\text{gross}}(t)$$

combined with an explicit runtime deficit penalty:

$$\text{Penalty}_{\text{deficit}} = 2.5 \cdot (\max(0, t_{\text{target}} - t_{\text{dur}}))^{1.6}$$

This guarantees the optimizer maintains the user's requested runtime duration (e.g. 6.0h) while targeting the absolute lowest-price hours of the day.

---

## 11. Android 15 (targetSdk 35) Edge-to-Edge Soft Keyboard Overlap

### The Problem
Targeting Android 15 (API 35) automatically enforces Edge-to-Edge rendering. Standard `android:windowSoftInputMode="adjustResize"` alone fails to compress the view hierarchy above the on-screen software keyboard. Tapping on input fields (such as Daily Budget or Avg Power Draw) caused the keyboard to obscure the input boxes and skew the parent `ScrollView`.

### The Solution
In [`MainActivity.kt`](../app/src/main/java/de/breast/ui/MainActivity.kt):
1. Configured `ScrollView` with `isFillViewport = true`.
2. Applied dynamic window insets via `ViewCompat.setOnApplyWindowInsetsListener`:
   ```kotlin
   val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.systemBars())
   scroll.setPadding(0, 0, 0, imeInsets.bottom)
   ```
3. Added focus listeners that smoothly scroll focused fields into clear view above the keyboard.

---

## 12. Non-Holistic Scheduling Budget Bypass Bug

### The Problem
When testing in non-holistic (Standard) mode, the daily budget limit failed to truncate operating hours when electricity costs exceeded the cap.

### Root Cause Analysis
In `AndroidPrefsStores.kt`, `buildScheduleConfig()` contained:
```kotlin
dailyBudgetEuro = if (isHolistic) dailyBudgetEuro else null
```
When Holistic Optimization was toggled off (`isHolistic = false`), `dailyBudgetEuro` was explicitly forced to `null`, completely bypassing the budget-cutting logic. Furthermore, input fields in the UI were only saved on focus loss, meaning clicking "Sync" directly from the keyboard could leave newly typed values uncommitted.

### The Solution
1. Unconditionally passed `dailyBudgetEuro = dailyBudgetEuro` to `SwitchScheduleConfig`.
2. Added `addTextChangedListener` on `budgetInput` and `powerInput` to commit edits immediately in real time.
3. Added explicit calls to `saveInputsToSettings()` at the beginning of all manual schedule sync triggers.

---

## 13. Prior Art & Open-Source Lineage

The reverse-engineering and protocol mapping in BREAST Heatpump was not achieved entirely in isolation, but stands on key foundations established by earlier open-source investigations into the Bosch Thermotechnology cloud:

1. **`guim31/gladys-bosch-homecom` (Guillaume Morin):**
   * Identified the primary PointT cloud API gateway domain (`pointt-api.bosch-thermotechnology.com/pointt-api/api/v1`), gateway query routes (`/gateways`), and foundational JSON structure for reading telemetry nodes and recordings.
2. **`serbanb11/bosch-homecom-hass` (@serbanb11):**
   * The comprehensive Home Assistant integration documentation that established the crucial architectural distinction between **K30 RF** (cloud-only API) and **K40 RF** (local LAN pairing), preventing wasted effort attempting local token requests on K30 hardware.
3. **`karl2510/homecom_alt` (@karl2510):**
   * Invaluable technical reference for the Bosch SingleKey ID OAuth2 PKCE authorization flow (`singlekey-id.com/auth/connect/authorize`), client ID scopes (`pointt.gateway.resource.dashapp`, `pointt.castt.flow.token-exchange`, etc.), and mobile URI scheme handling.
4. **`pschmitt/bosch-thermostat-client-python` (Pawel Schmitt & contributors):**
   * Pioneering groundwork reverse-engineering the nested Bosch resource trees, read/write permissions, and Buderus/Bosch thermostat characteristics.
5. **Weather & Spot Price Infrastructure:**
   * **Open-Meteo** and **Deutscher Wetterdienst (DWD)** for high-precision local solar and temperature forecasts.
   * **Fraunhofer ISE Energy-Charts** and **aWATTar** for European EPEX Spot day-ahead electricity prices.
