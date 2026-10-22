# BREAST — Bosch Reverse Engineering API SingleKey Test (Android App)

Native Kotlin Android application (`:app`) configured to match your local Android Studio toolchain (**Gradle `9.5.0`**, **AGP `9.3.3`**, **Kotlin `2.2.10`**, **Android SDK Platform `35`**, **JBR `25`**).

It authenticates with **Bosch SingleKey ID (OAuth2 + PKCE)**, controls Bosch heat pumps via the **PointT Cloud API** (`pointt-api.bosch-thermotechnology.com`), and runs an **automatic hourly background service (`SpotScheduleJobService`)** that checks **Vattenfall ÖkoStrom Dynamik (EPEX Spot DE-LU Day-Ahead)** prices daily starting at 12:00 (and hourly across the 12:00–15:00 release window), applies a single **Heating Target Temperature Slider** (`15.0°C .. 30.0°C`, step `0.5°C`), and pushes the shared 2-window (6h total, cut by daily budget) Heating (`hc1`) & Warm Water (`dhw1`) schedule back to the heat pump.

---

## Toolchain & Compatibility (Matched to Your Local Cache)

- **Gradle Wrapper:** `gradle-9.5.0-bin.zip` (cached in `C:\Users\limuy\.gradle\wrapper\dists\gradle-9.5.0-bin`)
- **Android Studio JBR:** `C:\Program Files\Android\Android Studio\jbr` (Java 25)
- **Android Gradle Plugin:** `com.android.application` `9.3.3` (with built-in Kotlin support)
- **Kotlin Serialization Plugin:** `org.jetbrains.kotlin.plugin.serialization` `2.2.10`
- **Android SDK (`local.properties`):** `C:\Users\limuy\AppData\Local\Android\Sdk` (`compileSdk = 35`, `minSdk = 26`, `build-tools = 35.0.0`)
- **Dependencies (all pre-cached in `.gradle/caches`):**
  - `androidx.core:core-ktx:1.13.1`
  - `androidx.activity:activity-ktx:1.9.3`
  - `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0`
  - `org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3`
  - `com.squareup.okhttp3:okhttp:4.12.0`

---

## Opening & Running in Android Studio

1. Open **Android Studio** and select **Open** $\rightarrow$ `c:\Users\limuy\Documents\antigravity\beautiful-heisenberg`.
2. Let Android Studio sync Gradle (`:app` module).
3. Connect your Android phone via USB and click **Run (`app`)**.

---

## Source Layout (`:app`)

- [`MainActivity.kt`](app/src/main/java/de/breast/ui/MainActivity.kt): Native Android UI with in-app SingleKey ID OAuth `WebView` interceptor, `0.5°C`-step Heating Temperature `SeekBar` slider, Daily Budget input, and live telemetry console.
- [`SingleKeyAuthManager.kt`](app/src/main/java/de/breast/auth/SingleKeyAuthManager.kt) & [`AndroidPrefsStores.kt`](app/src/main/java/de/breast/auth/AndroidPrefsStores.kt): OAuth2 + PKCE (`S256`) client and `SharedPreferences` persistence.
- [`PointTApiClient.kt`](app/src/main/java/de/breast/api/PointTApiClient.kt): Bosch PointT REST client (`/gateways`, `/resource/...`, `switchPrograms` read/write, and recursive endpoint crawler).
- [`SwitchProgramScheduler.kt`](app/src/main/java/de/breast/optimizer/SwitchProgramScheduler.kt): Dynamic programming 2-window (6h total) lowest-price finder with daily EUR budget cut and [`HeatingTemperatureSlider`](app/src/main/java/de/breast/optimizer/SwitchProgramScheduler.kt#L12-L38).
- [`AutoScheduleDaemon.kt`](app/src/main/java/de/breast/daemon/AutoScheduleDaemon.kt): Hourly check state machine & Android `JobScheduler` [`SpotScheduleJobService`](app/src/main/java/de/breast/daemon/AutoScheduleDaemon.kt#L208-L276).
- [`SpotPriceClient.kt`](app/src/main/java/de/breast/spot/SpotPriceClient.kt): Fraunhofer ISE Energy-Charts & aWATTar DE EPEX Spot `DE-LU` client with Vattenfall tariff calculation.
- [`BreastCoreTest.kt`](app/src/test/java/de/breast/BreastCoreTest.kt): Unit tests for PKCE, slider step snapping, 2-window 6h schedule optimization, and daily budget cuts.
