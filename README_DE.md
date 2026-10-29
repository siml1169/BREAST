# BREAST — Bosch Reverse Engineering API SingleKey Test

*Sprachen / Languages: [English](README.md) | **Deutsch** | [中文](README_ZH.md)*

[![Build Status](https://img.shields.io/badge/Build-Bestanden%20(62%2F62%20Tests)-brightgreen.svg)]()
[![Platform](https://img.shields.io/badge/Plattform-Android%2014%2B%20%28API%2026%E2%80%9335%29-blue.svg)]()
[![Target Hardware](https://img.shields.io/badge/Hardware-Bosch%20Compress%207400i%20AW-orange.svg)]()
[![Gateway](https://img.shields.io/badge/Gateway-Bosch%20Connect--Key%20K30%20RF-purple.svg)]()
[![State](https://img.shields.io/badge/Status-Einsatzbereit%20(Daily%20Driver)-success.svg)]()

**BREAST** ist eine native Android-Kotlin-App zur autonomen Optimierung von Schaltzeitplänen und Stromkosten für **Bosch- & Buderus-Luft/Wasser-Wärmepumpen** anhand dynamischer Day-Ahead-Börsenstrompreise (**EPEX Spot DE-LU** via Vattenfall ÖkoStrom Dynamik).

Die App kommuniziert über **SingleKey ID OAuth2 + PKCE** direkt mit der **PointT Cloud-API** (`pointt-api.bosch-thermotechnology.com`), protokolliert hochauflösende Anlagentelemetrie in einer lokalen SQLite-Datenbank, visualisiert die physikalische **nichtlineare Buderus/Bosch HPC 410 Vorlauf-Heizkurve**, berechnet ein fortschrittliches **Modellprädiktives Gebäude-Energiebilanzmodell (MPC)** und arbeitet vollkommen autonom im Hintergrund.

---

## Hauptfunktionen

### 1. Duale Betriebszeitplan-Optimierungsmodi

#### A. Ganzheitliche Optimierungs-Engine (MPC & Thermodynamik)
* **Gebäude-Energiebilanzmodell (`BuildingEnergyBalanceModel`):** Berechnet fortlaufend die Raumwärmebilanz und den Transmissionswärmeverlust:
  $$Q_{\text{Verlust}}(t) = H \cdot (T_{\text{Innen}}(t) - T_{\text{Außen}}(t))$$
  Quantifiziert die Wärmespeicherfähigkeit von Estrich und 120L-Pufferspeicher während der Absenkzeiten.
* **Thermodynamisches Wärmepumpen-COP-Modell (`CopThermodynamicModel`):** Berechnet die Carnot-Leistungszahl abhängig von Vorlauf- und Außentemperatur:
  $$\text{COP}(t) = \eta_C \cdot \frac{T_{\text{Vorlauf}}(t) + 273.15}{T_{\text{Vorlauf}}(t) - T_{\text{Außen}}(t)}$$
  Bevorzugt wärmere Nachmittagsstunden mit thermodynamisch maximalem Wirkungsgrad.
* **Closed-Loop-Regelungs-Digital-Twin (`ControlUnitSimulator`):** Reproduziert exakt die Zustandsmaschine des Bosch HPC 410 / Rego-Reglers, einschließlich Rücklauftemperatur-Einschalthysterese ($-1.2\text{ K}$) und Ausschalthysterese ($+0.5\text{ K}$), Estrich-Wärmedifferenzialgleichung (ODE) und Standby-Verbrauch.
* **Nicht-negative Laufzeitkorridore ($+0.0\text{ h}$ feste Untergrenze):** Der Ziel-Laufzeit-Schieberegler des Nutzers wirkt in allen Modi als strikte, nicht unterschreitbare Untergrenze. Ein unerwünschtes Einbrechen der Betriebszeit bei mildem Herbstwetter ist ausgeschlossen, während einseitige solare Tages-Ausdehnung voll erhalten bleibt.
* **Proportionale Korridorausdehnung & Quantisierer-Harmonisierung:** Ersetzt starre Stundengrenzen durch eine mathematische Funktion der gewählten Ziel-Laufzeit ($T_{\text{Ziel}}$):
  $$\Delta H_{\text{quantisiert}} = \operatorname{round}\left(\frac{T_{\text{Ziel}} \times r_{\text{Ausdehnung}}}{Q}\right) \times Q$$
  wobei $Q = 0.5\text{ h}$ ($30\text{ min}$) dem Schieberegler entspricht und sich exakt in ganzzahlige $15\text{ min}$-Rasterslots des Planers zerlegt.
* **4 Maßgeschneiderte Betriebs-Präferenzen:**
  1. **Komfort-Priorität:** Minimiert Schwankungen der Raumtemperatur ($w_c = 0.60$), erlaubt bis zu $+20\%$ Mehrlaufzeit gegen Auskühlen des Estrichs während der Nutzungszeiten.
  2. **Ausgewogen (Empfohlen):** Optimal austarierte Dreiklang-Balance zwischen Börsenstrompreis, thermodynamischem COP-Hub und Wohnkomfort mit bis zu $+25\%$ Mehrlaufzeit zur Mittags- und Solarstromnutzung.
  3. **Effizienz-Priorität (COP / Bang for Buck):** Maximiert die abgegebene Nutzwärme pro Euro ($\frac{\text{COP}}{c_{\text{brutto}}}$) mit bis zu $+35\%$ Mehrlaufzeit, um im thermodynamischen Bestpunkt bei niedriger Inverter-Modulation ($25\%\text{–}35\%$) über die sonnenreichen Mittagsstunden ($10:00\text{–}16:30$) budgetkonform zu cruisen.
  4. **Budget-Priorität:** **Strikte Laufzeiteinhaltung (+0% extra)** ($r = 0.00 \implies \Delta H = 0.0\text{ h}$). Wählt das absolut günstigste zusammenhängende Spot-Fenster exakt der Schieberegler-Dauer ohne freiwillige Dehnung oder Stauchung.
* **Verdichter-Schutz vor Taktung:** Erzwingt Mindestlaufzeiten von 90 Minuten pro Heizfenster.

#### B. Statischer Standard-Laufzeitmodus
* Bei deaktivierter ganzheitlicher Optimierung hält sich der Planer strikt an den eingestellten **Ziel-Laufzeit-Schieberegler** (z. B. exakt 6.0 h) und platziert das Fenster in der günstigsten Preiswanne des Tages.

---

### 2. Budgetobergrenze, Zwei-Stufen-Bewertung & Marginal-Cost-Kantenbeschneidung
* **Zwei-Stufen-Budgetbewertung:** Bewertet Kandidatenfenster zunächst strikt innerhalb des Ziellaufzeit-Korridors. Passt das Korridorfenster in `dailyBudgetEuro`, wird es mit `budgetCutMinutes = 0` ausgeführt (vollständige Eliminierung falscher Budgetkürzungs-Meldungen).
* **Marginal-Cost-Kantenbeschneidung (Edge Shaving):** Nur wenn ausnahmslos jedes Fenster im Zielkorridor die Budgetgrenze überschreitet, vergleicht der Optimierer die spezifischen Wärmekosten $c_{\text{Wärme}} = \frac{c_{\text{brutto}}}{\text{COP}}$ an der Startkante ($S$) und an der Endkante ($E - 1$). Er trimmt iterativ 15-Minuten-Slots an der Kante mit der geringeren Kosteneffizienz ab, bis das Budget eingehalten wird, wodurch die sonnigen Mittagsstunden mit hohem COP geschützt bleiben.
* **Dynamische Inverter-Leistungsmodulation & Empirische Interpolation:** Statt von einer starren Dauerleistung (z. B. 2.0 kW) auszugehen, berechnet der Optimierer die elektrische Leistungsaufnahme dynamisch anhand des realen Heizbedarfs ($H_{\text{Geb}} = 132\text{ W/K}$), der empirischen reziproken Hub-COP-Interpolation ($\text{COP} \approx 8.7$ in der Übergangszeit bis $4.09$ im tiefen Winter) und der Hardware-Modulationsgrenzen ($28\%\text{–}100\%$, ca. $0.35\text{–}0.70\text{ kW}$ an milden Tagen). Kontinuierliche parasitäre Lasten (sekundäre Umwälzpumpe + Standby-Elektronik, großzügiger 50W-Daumenwert) werden mit nativer Submeter-Kalibrierung berücksichtigt. Karte 4 unterstützt automatische Modulation (`Auto (~0.6kW)`) oder feste manuelle Wattangaben.
* **Dualer Schutz:** Wirkt gleichermaßen im MPC-Modus und im statischen Modus.
* **Nullpreis-Chancen-Verlängerung:** Nimmt günstige/negative Strompreise automatisch zur Estrich-Pufferung mit – strikt gedeckelt durch das Tagesbudget.
* **Echtzeit-Eingabespeicherung:** Änderungen an Budget (€) oder Wärmepumpen-Nennleistung (kW) werden sofort beim Tippen persistent gespeichert.

---

### 3. Schutz vor versehentlichem Überschreiben (3-Tap-Barriere)
* **Thermostat-Übersteuerungsschutz:** Erkennt sofort, wenn an der Raumregler-Bedieneinheit an der Wand oder in der Bosch HomeCom-App manuell Schaltungen vorgenommen wurden.
* **Automatischer Schutz:** Der Hintergrund-Daemon pausiert automatische Zeitplan-Überschreibungen, um manuelle Benutzereingriffe zu respektieren.
* **3-Tap-Bestätigung:** Ein Überschreiben der Hardware erfordert dreimaliges Antippen des Synchronisations-Buttons mit explizitem Warnhinweis.

---

### 4. Interaktive HPC 410 Vorlauf-Heizkurve & Telemetrie
* **Physikalische nichtlineare HPC 410 Kennlinie:** Zeichnet die herstellereigene Soll-Vorlaufkurve:
  $$T_{\text{Vorlauf}} = T_{\text{Basis}} + \frac{T_{\text{Auslegung}} - T_{\text{Basis}}}{(-10 - 20)^\alpha} \cdot (T_{\text{Außen}} - 20)^\alpha + \Delta T_{\text{Parallel}}$$
  mit konfigurierbarem Fußpunkt, Auslegungspunkt (z. B. $35^\circ\text{C}$ @ $-10^\circ\text{C}$ für Fußbodenheizung) und Parallelverschiebung ($K$).
* **Interaktiver Vollbild-Visualisierer:** Antippen öffnet die Querformat-Grafik mit:
  * HPC 410 Referenzkurven [1–5].
  * Stationären Messpunkten vs. transienten Aufheizphasen.
  * Live-Fadenkreuz des aktuellen Arbeitspunkts ($T_{\text{Außen}}, T_{\text{Vorlauf}}$).
  * Bidirektional synchronisierter Kurvenauswahl mit dem Haupt-Dashboard.
* **Echtzeit-Sensoranzeigen:** Außentemperatur, Vorlauftemperatur, Rücklauftemperatur, Spreizung ($\Delta T$), Verdichter-Modulation (%) und Betriebsstatus (`ch`, `dhw`, `idle`).
* **Empirische Gebäude-Wärmeverlust-Bestimmung & Online-Lernen (`SetbackHeatLossEstimator` & `AdaptiveThermalObserver`):**
  * **Kontinuierliches Online-Lernen der Rücklauftemperatur (`AdaptiveThermalObserver`):** Überwacht kontinuierlich das Abkühlverhalten während der Nachtabsenkung und aktualisiert $H_{\text{Geb}}$ und Abkühlrate $\kappa$ online via EMA (gefiltert durch strikte $R^2 \ge 0.65$-Qualitätsgrenzen). Speist Live-Messwerte des Estrich-Rücklaufs in `BuildingEnergyBalanceModel` ein.
  * **Strikter NOAA-Astronomischer Nachtfilter:** Berücksichtigt Abkühlungsdaten strikt erst ab $\ge 2\text{ Stunden}$ nach lokalem Sonnenuntergang bis zum Sonnenaufgang. Eliminiert Einstrahlung und thermische Speicherverzögerungen der Gebäudehülle vollständig und überbrückt Mitternachtsgrenzen nahtlos (z. B. 19:00 bis 04:15 Uhr am Folgemorgen).
  * **Verifikation des Absenkbetriebs:** Bestätigt, dass die Wärmepumpe tatsächlich im Absenkmodus arbeitet ($T_{\text{Soll}} \le 18.5^\circ\text{C}$ oder $T_{\text{Soll}} \le T_{\text{Komfort}} - 2.0\text{ K}$, Kompressor AUS, Modulation $0\%$, keine Heiz-/Warmwasseranforderung, Hydraulikkreis ausgeglichen $\le 1.5\text{ K}$). Bricht sofort ab, sobald der Komfort-Heizbetrieb wieder startet (z. B. um 04:15 Uhr CET).
  * **Dynamische 2R2C Wärmekapazität:** Unterscheidet kontinuierlich zwischen aktiver Estrich-Pufferentladung ($T_{\text{Rücklauf}} \ge T_{\text{Komfort}} + 1.0\text{ K} \implies C_{\text{eff}} = C_{\text{Estrich}} \approx 4.8\text{ kWh/K}$) und gekoppelter Gesamthaus-Gleichgewichtsentladung ($T_{\text{Rücklauf}} \le T_{\text{Komfort}} \implies C_{\text{eff}} = C_{\text{Estrich}} + C_{\text{Innen}} \approx 13.3\text{ kWh/K}$) mit stufenlosem Übergangsfaktor $\alpha \in [0.0, 1.0]$.
  * **Kontinuierliche lineare Regression nach kleinster Fehlerquadratsumme:** Berechnet Steigung und $R^2$ über alle $N$ Abtastwerte eines Blocks ohne Randwert-Rauschen ($R^2 \ge 0.70$, Abkühlrate $\ge 0.04\text{ K/h}$).
  * **Mehrtägige $R^2 \times \text{Dauer}$ gewichtete Mittelung:** Aggregiert mehrere gültige Nachtperioden zu einem robusten Gebäude-Wärmeverlustwert ($W/K$), dargestellt mit lokalen CET-Zeitangaben.
  * **Solltemperatur-Konsistenzfilter:** Schließt abweichende Test-Solltemperaturen ($25.5^\circ\text{C}$) aus Modulations- und Heizkurvenanpassungen aus.
  * **Fensterbezogene vs. 24h-Gesamtenergie-Bilanzierung:** Weist neben dem aktiven Heizfensterverbrauch auch die 24-Stunden-Gesamtstromaufnahme inklusive des ungemessenen Dauerverbrauchs der sekundären Fußboden-Umwälzpumpe (~25W) und der Regelelektronik (~15W) mit einem großzügigen 50W-Basis-Daumenwert (~$1.2\text{ kWh/Tag}$) transparent aus; vorbereitet für Submeter-Kalibrierung (z. B. Shelly 3EM).

---

### 5. Hintergrund-Automatisierung & Android 15 Kompatibilität
* **Stündlicher EPEX Spot Abgleich (`SpotScheduleJobService`):** Prüft stündlich die Veröffentlichung der Day-Ahead-Preise (12:00–15:00 Uhr CET) und überträgt den Plan für morgen, ohne den laufenden Tag zu unterbrechen.
* **Hochfrequenz-Telemetrie-Ticker (`AlarmManager`):** Zuverlässige Datenerfassung auch im tiefen Android Doze-Energiesparmodus.
* **Android 15 Edge-to-Edge Design:** Volle Unterstützung von Android 15 (targetSdk 35) mit dynamischer `WindowInsetsCompat.Type.ime()`-Tastaturanpassung gegen Textfeld-Verdeckungen.

---

## Ziel-Anlagenspezifikationen

* **Wärmepumpe:** Bosch Compress 7400i AW (`CS7400iAW 9 OR-S`)
  * **Außeneinheit:** Modulierender Inverter-Verdichter (230V einphasig, $P_{\text{el}} \approx 0.5 - 2.2\text{ kW}$).
  * **Inneneinheit / Hydrauliktower:** AWM 9 / AWMB 9 mit integriertem **9.0 kW stufenelektrischem Zuheizer** (`eheater`).
  * **Hydraulische Entkopplung:** **120L Heizungspufferspeicher** (*Trennspeicher*) für unterbrechungsfreie Raumheizung bei Warmwasserbetrieb.
* **Systemregler:** **HPC 410** (`Id: 158`, FW `73.03`, Teile-Nr. `8737712760`) & **CUHP** (`Id: 172`, FW `01.20`) über EMS 2.0 Bus.
* **Gateway:** **Connect-Key K30 RF** (`K30RF_v1`, FW `15.00.01`, Produkt-UUID `101845229`).

---

## Projektstruktur

```
beautiful-heisenberg/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml          # Berechtigungen, JobService, BootReceiver, OAuth URI
│   │   │   └── java/de/breast/
│   │   │       ├── api/
│   │   │       │   └── PointTApiClient.kt   # Bosch PointT Cloud-REST-Client & Telemetrie
│   │   │       ├── auth/
│   │   │       │   ├── AndroidPrefsStores.kt# SharedPreferences-Speicher (Tokens, Einstellungen, Status)
│   │   │       │   └── SingleKeyAuthManager.kt # SingleKey ID OAuth2 + PKCE Manager
│   │   │       ├── daemon/
│   │   │       │   └── AutoScheduleDaemon.kt# JobScheduler, AlarmManager, stündlicher Regelzyklus
│   │   │       ├── optimizer/
│   │   │       │   ├── BuildingEnergyBalanceModel.kt # 3-Zustands-Gebäude-Wärmeverlust & Trägheit
│   │   │       │   ├── ControlUnitSimulator.kt       # Closed-Loop HPC 410 / Rego-Digital-Twin
│   │   │       │   ├── CopThermodynamicModel.kt      # Thermodynamische Carnot-COP-Kennlinie
│   │   │       │   ├── HolisticScheduleOptimizer.kt  # Mehrkriterielle MPC-Optimierungs-Engine
│   │   │       │   └── SwitchProgramScheduler.kt     # Statischer Raster-Planer & Budget-Kürzer
│   │   │       ├── spot/
│   │   │       │   ├── SpotPriceClient.kt   # Energy-Charts & aWATTar DE EPEX-Preis-Client
│   │   │       │   └── SpotPriceSource.kt   # Dynamische Preisquellen-Definitionen
│   │   │       ├── telemetry/
│   │   │       │   ├── HeatPumpTelemetrySnapshot.kt # Unveränderliches Telemetrie-Snapshot-Modell
│   │   │       │   ├── HeatingCurveAnalyzer.kt     # Heizkurvenbewertung & Auskühlrate
│   │   │       │   ├── SetbackHeatLossEstimator.kt # NOAA-Nachtwärmeverlust-Regression & Mittelung
│   │   │       │   └── SqliteTelemetryStore.kt     # SQLite-Datenbank für Zeitreihen
│   │   │       ├── ui/
│   │   │       │   ├── HeatingCurveChartView.kt    # Nichtlineare HPC 410 Canvas-Ansicht
│   │   │       │   ├── HeatingCurveFullscreenActivity.kt # Interaktiver Vollbild-Visualisierer
│   │   │       │   ├── MainActivity.kt             # Haupt-Dashboard & Karten-Layout
│   │   │       │   └── SettingsActivity.kt         # Erweiterte Einstellungen & Kalibrierung
│   │   │       └── weather/
│   │   │           └── WeatherForecastClient.kt    # Open-Meteo Wetter-Client
│   │   └── test/java/de/breast/
│   │       ├── BreastCoreTest.kt            # Zeitplan-, PKCE-, Budget- & MPC-Tests
│   │       └── telemetry/
│   │           └── HeatingCurveAnalyzerTest.kt # Kurvenanpassungs- & 24h-Profiltests
│   └── build.gradle.kts                     # App-Build-Konfiguration
├── build.gradle.kts                         # Root-Build-Konfiguration
├── docs/
│   ├── ARCHITECTURE.md                      # Technische Architektur ([DE](ARCHITECTURE_DE.md), [ZH](ARCHITECTURE_ZH.md))
│   ├── HEAT_PUMP_OPTIMIZATION_PLAN.md       # Optimierungsspezifikation v2.0 ([DE](HEAT_PUMP_OPTIMIZATION_PLAN_DE.md), [ZH](HEAT_PUMP_OPTIMIZATION_PLAN_ZH.md))
│   ├── POSTMORTEM.md                        # Reverse-Engineering-Erkenntnisse & Fehleranalysen
│   └── PROJECT_STATUS.md                    # Aktuelle Feature-Matrix und Versionsstatus
├── scripts/
│   ├── analysis/                            # Offline-Analyse von Telemetrie-DBs & PointT-Aufzeichnungen
│   ├── simulation/                          # Optimierer-Simulationen & Ad-hoc-Regressionsskripte
│   ├── adb-singlekey-inspect.ps1            # ADB-Hilfsskript für den SingleKey-ID-OAuth-Redirect
│   ├── check_sync_log.py                    # Letztes Sync-Protokoll der App per ADB vom Handy lesen
│   ├── fetch_emon.py                        # PointT-Energiemonitor-Aufzeichnungen (emon) herunterladen
│   ├── probe_emon_details.ps1               # PointT-Energiemonitor-Endpunkte (emon) abfragen
│   └── probe_power_endpoints.ps1            # PointT-Leistungs- & Energie-Endpunkte der Wärmeerzeuger abfragen
└── data/
    ├── pointt/                              # PointT-API-Aufzeichnungen & Probe-Ergebnisse (JSON)
    └── telemetry/                           # SQLite-Telemetrie-Datenbanken
```

---

## Toolchain & Verifizierung

* **Gradle:** `9.5.0`
* **Java / JBR:** OpenJDK `25.0.2` (Android Studio JBR / JDK 17 kompatibel)
* **Android Gradle Plugin:** `9.3.3`
* **Kotlin:** `2.2.10`
* **Target SDK:** Android 35 (`compileSdk = 35`, `minSdk = 26`)
* **Unit-Tests:** **62 / 62 bestanden**

### Lokale Unit-Tests ausführen
Über PowerShell im Projektverzeichnis:
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
$gradle = "C:\Users\limuy\.gradle\wrapper\dists\gradle-9.5.0-bin\bvnork1r7n8i6kp5cnkibsc9q\gradle-9.5.0\bin\gradle.bat"
& $gradle testDebugUnitTest
```

### Installation auf dem Smartphone
1. Projekt in Android Studio öffnen (`c:\Users\limuy\Documents\antigravity\beautiful-heisenberg`).
2. Android-Gerät über USB oder WLAN-ADB verbinden.
3. Auf **Run (`app`)** klicken.
4. In der App auf **Connect Bosch SingleKey ID** tippen, einmalig anmelden und **Sync Lowest-Price Schedule Now** antippen.
