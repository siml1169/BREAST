# BREAST — 博世热泵反向工程 API SingleKey 动态电价优化系统

*语言 / Languages: [English](README.md) | [Deutsch](README_DE.md) | **中文** |*

[![Build Status](https://img.shields.io/badge/Build-全部通过%20(62%2F62%20测试)-brightgreen.svg)]()
[![Platform](https://img.shields.io/badge/平台-Android%2014%2B%20%28API%2026%E2%80%9335%29-blue.svg)]()
[![Target Hardware](https://img.shields.io/badge/硬件型号-Bosch%20Compress%207400i%20AW-orange.svg)]()
[![Gateway](https://img.shields.io/badge/通信网关-Bosch%20Connect--Key%20K30%20RF-purple.svg)]()
[![State](https://img.shields.io/badge/系统状态-日常可用%20(Daily%20Driver)-success.svg)]()

**BREAST** 是一款原生 Android Kotlin 应用程序，专为**博世（Bosch）与布德鲁斯（Buderus）空气源热泵**深度定制，基于欧洲日前电力现货市场浮动价格（**EPEX Spot DE-LU**，以德国 Vattenfall ÖkoStrom Dynamik 动态电价为基准），自主规划与执行最具经济效益的热泵供热日程与负荷调度。

应用通过 **SingleKey ID OAuth2 + PKCE** 直接与博世官方 **PointT 云端 API**（`pointt-api.bosch-thermotechnology.com`）通信，在本地 SQLite 数据库中高频记录系统运行物理遥测，精准渲染符合设备制造标准的**非线性物理供热特性曲线（HPC 410）**，求解先进的**模型预测控制（MPC）建筑热平衡与热惰性模型**，并通过 Android 系统级后台守护进程完全自主运行。

---

## 核心系统特性

### 1. 双重模式运行计划求解引擎

#### A. 全局统筹优化引擎（MPC 与热力学模型预测控制）
* **建筑能量平衡模型（`BuildingEnergyBalanceModel`）：** 实时仿真建筑室内热容蓄热与围护结构散热通量：
  $$Q_{\text{损耗}}(t) = H \cdot (T_{\text{室内}}(t) - T_{\text{室外}}(t))$$
  精准量化地暖水泥砂浆层与 120L 缓冲水箱在停机或低温回退阶段的自然温降。
* **热泵 COP 热力学卡诺模型（`CopThermodynamicModel`）：** 刻画能效随室外气温与供水温差扬程的物理规律：
  $$\text{COP}(t) = \eta_C \cdot \frac{T_{\text{供水}}(t) + 273.15}{T_{\text{供水}}(t) - T_{\text{室外}}(t)}$$
  在电价相近区间优先选中气温较高的午后窗口，以最高热力学 COP 能效充热。
* **闭环控制数字孪生仿真（`ControlUnitSimulator`）：** 精确仿真博世 HPC 410 / Rego 控制器的底层状态机逻辑，包含回水切入启机滞后（$-1.2\text{ K}$）与超温停机（$+0.5\text{ K}$）迟滞区间、混凝土砂浆常微分方程（ODE）传热计算及待机基准电耗。
* **非负运行时间走廊（$+0.0\text{ h}$ 刚性底线）：** 用户设定的目标每日运行时长滑块在所有模式下均作为绝对底线生效。温和秋季天气下算法绝不会跌破滑块意图，同时保留单向正午光热扩展空间。
* **比例弹性扩展与调度量化器协同（Harmonized Quantizer）：** 彻底废除生硬的 1.5–2.0 小时静态硬编码限制，将扩展时长表达为设定时长（$T_{\text{set}}$）的比例函数并与调度量化器对齐：
  $$\Delta H_{\text{quantized}} = \operatorname{round}\left(\frac{T_{\text{set}} \times r_{\text{expansion}}}{Q}\right) \times Q$$
  其中 $Q = 0.5\text{ h}$（30 分钟）完美匹配交互滑块步长，并在底层严格整除为整数个 15 分钟栅格槽位，杜绝非整数碎片化插槽。
* **四种定制化策略偏好：**
  1. **舒适优先（Comfort Priority）：** 最小化室内温差波动（$w_c = 0.60$），允许至多 $+20\%$ 弹性扩展以杜绝居住活动期间地暖板结温降。
  2. **均衡模式（Balanced，推荐）：** 在现货电价、卡诺 COP 能效提振与室内舒适度之间取得最佳平衡，允许至多 $+25\%$ 弹性扩展以吸纳正午太阳能与低电价。
  3. **能效优先（Efficiency / Bang for Buck）：** 最大化每欧元产热量（$\frac{\text{COP}}{c_{\text{gross}}}$），允许至多 $+35\%$ 弹性扩展，促使压缩机在光照最强的正午时段（$10:00\text{–}16:30$）以低频轻载调制（$25\%\text{–}35\%$）巡航运行，同时严格遵守预算。
  4. **预算优先（Budget Priority）：** **严格运行时长锁定（+0% 零额外扩展）**（$r = 0.00 \implies \Delta H = 0.0\text{ h}$），绝对锚定用户设定的目标时长滑块，在全天寻找电价最低的连续窗口，杜绝非预算驱动的随意时长伸缩。
* **压缩机防频繁启停保护：** 强制要求单次连续开机时长不得低于 90 分钟。

#### B. 静态标准运行模式
* 当关闭全局统筹优化时，调度器严格锁定用户滑块设置的**目标每日运行时长**（如设定 6.0 小时即精确排定 6.0 小时），在全天捕捉最便宜的电价波谷。

---

### 2. 每日预算硬上限、两阶段评估与边际成本边缘切削（Marginal Cost Edge Shaving）
* **两阶段预算评估：** 优先在目标时长走廊内挑选候选窗口；只要走廊内的窗口未超出 `dailyBudgetEuro`，即执行完整窗口并设定 `budgetCutMinutes = 0`（彻底根除虚假预算截断上报）。
* **边际成本边缘切削（Marginal Cost Edge Shaving）：** 仅当目标走廊内的所有窗口均突破预算上限时，优化器比较窗口前沿起始插槽（$S$）与后沿终止插槽（$E-1$）的实际产热单位成本 $c_{\text{heat}} = \frac{c_{\text{gross}}}{\text{COP}}$，按 15 分钟粒度迭代切除产热性价比更低的一侧，直至符合预算，从而最大限度保全正午光热最高 COP 核心区间。
* **变频功率自适应调制与经验插值模型：** 告别以往僵化假设固定额定功率（如一刀切按 2.0 kW 计费）的计算缺陷，算法根据建筑实测热阻（$H_{\text{bld}} = 132\text{ W/K}$）、基于真实全月实测数据的倒数温升 COP 经验插值模型（温和秋季 COP 可达 $\approx 8.7$，深冬零下工况连续插值至 $4.09$）以及变频硬件调制深度（$28\%\text{–}100\%$，温和天气下压缩机巡航功率仅 $\approx 0.35\text{–}0.70\text{ kW}$）实时计算电网吸入功率。同时叠加常开二级地暖泵与主控待机的 50W 宽裕经验基线寄生功耗，并预留独立物理分电表实测校准变量。卡片 4 默认留空启用自适应变频（提示 `Auto (~0.6kW)`），亦支持手动输入固定数值强制锁定。
* **双模式无缝防护：** 在全局统筹模式和静态标准模式下均严格生效。
* **零电价机遇吸收（Zero-Price Extension）：** 出现极低或负电价时自动延长开机将富余绿色电力蓄存至地暖砂浆中，且同样受每日总预算约束。
* **输入即时持久化：** 在卡片 4 中修改每日预算（€）或热泵功率（kW）时，文本监听器即刻将数据保存到底层存储，无需多余失焦动作。

---

### 3. 实体温控器手动调节保护（3次轻触防误触覆盖）
* **硬件覆写感知盾牌：** 若用户直接在墙面实体房间温控器上旋转调温，或在博世官方 HomeCom App 中修改了定时，应用立刻检测到日程数字签名不一致。
* **后台自动停运避让：** 自动同步守护进程立即挂起自动推流，优先遵从用户的现场人工干预。
* **3次轻触确认机制：** 若要在手机应用中重新下发计划覆盖硬件，需连续轻触同步按钮 3 次并确认黄色告警，防止误操作抹除手动设置。

---

### 4. 交互式 HPC 410 供热曲线与实时工况遥测
* **博世官方非线性物理曲线方程：** 抛弃简化的线性回归，精确绘制暖通厂家的指数型供水水温曲线：
  $$T_{\text{供水}} = T_{\text{基准}} + \frac{T_{\text{设计}} - T_{\text{基准}}}{(-10 - 20)^\alpha} \cdot (T_{\text{室外}} - 20)^\alpha + \Delta T_{\text{平移}}$$
  支持配置基准温度、设计温度（例如低温地暖 $-10^\circ\text{C}$ 时设计水温 $35^\circ\text{C}$）以及平移偏置（$K$）。
* **全屏横屏可视化画布：** 轻触即可展开横屏大图，包含：
  * HPC 410 官方标准参考曲线 [1–5]。
  * 稳态平衡工况散点与开机初期瞬态散点。
  * 实时工况十字标尺（$T_{\text{室外}}, T_{\text{供水}}$）。
  * 曲线选择状态在全屏与主看板间双向保持同步。
* **实时遥测看板：** 实时显示室外温度、供水温度、回水温度、供回水温差（$\Delta T$）、压缩机调制百分比（%）及机组工作状态（`ch` 地暖、`dhw` 热水、`idle` 待机）。
* **经验建筑热损失辨识与在线自适应学习（`SetbackHeatLossEstimator` & `AdaptiveThermalObserver`）：**
  * **夜间回水降温在线持续学习（`AdaptiveThermalObserver`）：** 在夜间回退阶段实时监测回水温降，利用指数移动平均（EMA）在线更新建筑热损系数（$H_{\text{bld}}$）与降温速率（$\kappa$），并通过 $R^2 \ge 0.65$ 严格质量门槛过滤，动态指导 `BuildingEnergyBalanceModel` 修正开机升温需求。
  * **严格 NOAA 天文级纯黑夜滤波：** 仅提取本地日落至少 $\ge 2\text{ 小时}$至次日日出之间的降温数据，彻底消除日光辐射与外立面白天蓄热的瞬态干扰。算法自动跨午夜无缝拼接连续降温区间（如 19:00 至次日 04:15）。
  * **夜间回退（Setback）模式严格校验：** 严格要求机组处于降温回退状态（设定温度 $T_{\text{setpoint}} \le 18.5^\circ\text{C}$ 或 $\le T_{\text{comfort}} - 2.0\text{ K}$，压缩机关闭 $0\%$，地暖与生活热水均待机，水路回路完全均温 $\le 1.5\text{ K}$）。一旦舒适供暖恢复（如 04:15 CET），数据流立即截断，杜绝舒适期混入。
  * **2R2C 动态参与热容模型：** 连续平滑解耦水泥找平层主动释热与全屋热平衡衰减过程。当地面温热（$T_{\text{return}} \ge T_{\text{comfort}} + 1.0\text{ K}$）时，有效热容为水泥层自身热容（$C_{\text{eff}} = C_{\text{screed}} \approx 4.8\text{ kWh/K}$）；当地面冷透降至室温（$T_{\text{return}} \le T_{\text{comfort}}$）全屋同频降温时，参与热容平滑扩展至全屋总热容（$C_{\text{eff}} = C_{\text{screed}} + C_{\text{indoor}} \approx 13.3\text{ kWh/K}$，$\alpha \in [0.0, 1.0]$）。
  * **全时段最小二乘连续线性回归：** 基于区段内所有 $N$ 个真实采样点进行一元线性回归求取降温斜率与 $R^2$ 拟合优度，杜绝起止两点噪声造成的斜率畸变（$R^2 \ge 0.70$，降温速率 $\ge 0.04\text{ K/h}$）。
  * **多夜 $R^2 \times \text{时长}$ 加权回归：** 跨多夜整合有效黑夜降温段，输出高可信度建筑热损失指标（$W/K$），并以本地时区（CET）显示时间窗口。
  * **设定温度一致性纯净过滤：** 在回归分析中自动剔除设定温度异常变化（如 $25.5^\circ\text{C}$ 测试数据）的脏数据。
  * **窗口运行电量与 24 小时全日总能耗双轨计量：** 清晰区分供热窗口内的压缩机主动做功电量与全天包含地暖二级循环水泵（常开约 25W）及主板电控底噪（约 15W）的 50W 宽裕经验基准寄生功耗（~$1.2\text{ kWh/天}$）；并预留独立变量接口支持物理分电表（如 Shelly 3EM）实测校准。

---

### 5. 后台自动化与 Android 15 兼容性
* **日前电价每小时同步守护进程（`SpotScheduleJobService`）：** 系统服务在每日 12:00–15:00 CET（欧洲日前电价发布窗口）轮询并在电价出清后无感下发明日最优计划，不中断今日已在执行的供热。
* **高频遥测心跳（`AlarmManager`）：** 利用精确系统闹钟穿透 Android Doze 深度休眠，按用户设定的频率记录设备工况。
* **Android 15 沉浸式布局适配：** 满足 API 35 强制 Edge-to-Edge 规范，通过 `WindowInsetsCompat.Type.ime()` 动态监听软键盘弹起高度，防止输入框被遮挡。

---

## 适配实体热泵系统规格

* **热泵机组：** 博世 Compress 7400i AW（`CS7400iAW 9 OR-S`）
  * **室外机：** 单相 230V 变频空气源热泵（额定电功率 $P_{\text{el}} \approx 0.5 - 2.2\text{ kW}$）。
  * **室内水力塔：** AWM 9 / AWMB 9，内置 **9.0 kW 分级辅助电加热器**（`eheater`）。
  * **水力去耦设计：** **120L 供暖缓冲水箱**（*Trennspeicher*），实现生活热水加热与地暖循环水流去耦，互不干扰。
* **系统主控制器：** **HPC 410**（`Id: 158`, 固件 `73.03`, 物料号 `8737712760`）与 **CUHP**（`Id: 172`, 固件 `01.20`），基于 EMS 2.0 总线通信。
* **无线通信网关：** **Connect-Key K30 RF**（`K30RF_v1`, 固件 `15.00.01`, 产品 UUID `101845229`）。

---

## 项目工程结构

```
beautiful-heisenberg/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml          # 权限、JobService、开机广播、OAuth 协议声明
│   │   │   └── java/de/breast/
│   │   │       ├── api/
│   │   │       │   └── PointTApiClient.kt   # 博世 PointT 云端 REST 客户端及遥测抓取
│   │   │       ├── auth/
│   │   │       │   ├── AndroidPrefsStores.kt# SharedPreferences 存储封装（凭据、配置、状态）
│   │   │       │   └── SingleKeyAuthManager.kt # SingleKey ID OAuth2 + PKCE 鉴权管理器
│   │   │       ├── daemon/
│   │   │       │   └── AutoScheduleDaemon.kt# JobScheduler、AlarmManager 与每小时轮询状态机
│   │   │       ├── optimizer/
│   │   │       │   ├── BuildingEnergyBalanceModel.kt # 三状态建筑热平衡及砂浆层蓄热模型
│   │   │       │   ├── ControlUnitSimulator.kt       # 闭环 HPC 410 / Rego 控制器数字孪生仿真
│   │   │       │   ├── CopThermodynamicModel.kt      # 热泵卡诺循环 COP 能效热力学曲线
│   │   │       │   ├── HolisticScheduleOptimizer.kt  # 多目标 MPC 模型预测控制求解器
│   │   │       │   └── SwitchProgramScheduler.kt     # 静态电价栅格调度与预算裁剪算法
│   │   │       ├── spot/
│   │   │       │   ├── SpotPriceClient.kt   # Energy-Charts 与 aWATTar 现货电价拉取服务
│   │   │       │   └── SpotPriceSource.kt   # 动态电价数据源定义
│   │   │       ├── telemetry/
│   │   │       │   ├── HeatPumpTelemetrySnapshot.kt # 不可变设备物理快照实体
│   │   │       │   ├── HeatingCurveAnalyzer.kt     # 供热特性曲线评估及自然温降分析
│   │   │       │   ├── SetbackHeatLossEstimator.kt # NOAA天文夜间热损失反演与多夜加权
│   │   │       │   └── SqliteTelemetryStore.kt     # 本地 SQLite 时序数据存储
│   │   │       ├── ui/
│   │   │       │   ├── HeatingCurveChartView.kt    # 非线性物理供热曲线自定义 Canvas 视图
│   │   │       │   ├── HeatingCurveFullscreenActivity.kt # 横屏交互全屏曲线图
│   │   │       │   ├── MainActivity.kt             # 手机主界面布局及卡片渲染
│   │   │       │   └── SettingsActivity.kt         # 高级热工参数与曲线标定设置
│   │   │       └── weather/
│   │   │           └── WeatherForecastClient.kt    # Open-Meteo 逐小时气象预测客户端
│   │   └── test/java/de/breast/
│   │       ├── BreastCoreTest.kt            # 调度算法、PKCE、预算与 MPC 核心单元测试
│   │       └── telemetry/
│   │           └── HeatingCurveAnalyzerTest.kt # 曲线拟合与 24 小时工况测试
│   └── build.gradle.kts                     # Android 应用模块编译配置
├── build.gradle.kts                         # 根项目编译配置
├── docs/
│   ├── ARCHITECTURE.md                      # 系统架构设计技术文档 ([DE](ARCHITECTURE_DE.md), [ZH](ARCHITECTURE_ZH.md))
│   ├── HEAT_PUMP_OPTIMIZATION_PLAN.md       # 统筹优化算法规格说明书 v2.0 ([DE](HEAT_PUMP_OPTIMIZATION_PLAN_DE.md), [ZH](HEAT_PUMP_OPTIMIZATION_PLAN_ZH.md))
│   ├── POSTMORTEM.md                        # 反向工程技术突破与事后复盘分析
│   └── PROJECT_STATUS.md                    # 当前功能完成度矩阵与版本里程碑
├── scripts/
│   ├── analysis/                            # 遥测数据库与 PointT 录制数据的离线分析
│   ├── simulation/                          # 优化器仿真与临时回归测试脚本
│   ├── adb-singlekey-inspect.ps1            # 用于 SingleKey ID OAuth 重定向的 ADB 辅助脚本
│   ├── check_sync_log.py                    # 通过 ADB 从手机读取应用最近一次同步日志
│   ├── fetch_emon.py                        # 下载 PointT 能耗监测（emon）录制数据
│   ├── probe_emon_details.ps1               # 探测 PointT 能耗监测（emon）接口
│   └── probe_power_endpoints.ps1            # 探测 PointT 热源功率与能耗接口
└── data/
    ├── pointt/                              # PointT API 录制数据与探测结果（JSON）
    └── telemetry/                           # SQLite 遥测数据库
```

---

## 编译工具链与自动化验证

* **Gradle 构建工具：** `9.5.0`
* **Java 运行环境：** OpenJDK `25.0.2`（Android Studio JBR / 兼容 JDK 17）
* **Android Gradle Plugin (AGP)：** `9.3.3`
* **Kotlin 语言版本：** `2.2.10`
* **目标 SDK：** Android 35（`compileSdk = 35`, `minSdk = 26`）
* **单元测试状态：** **62 / 62 全部通过**

### 本地执行测试套件
在项目根目录下通过 PowerShell 运行：
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;" + $env:Path
$gradle = "C:\Users\limuy\.gradle\wrapper\dists\gradle-9.5.0-bin\bvnork1r7n8i6kp5cnkibsc9q\gradle-9.5.0\bin\gradle.bat"
& $gradle testDebugUnitTest
```

### 在真机设备上安装运行
1. 在 Android Studio 中打开项目文件夹：`c:\Users\limuy\Documents\antigravity\beautiful-heisenberg`。
2. 通过 USB 数据线或无线 ADB 连接你的 Android 手机。
3. 点击工具栏的 **Run (`app`)**。
4. 打开手机上的 App，点击 **Connect Bosch SingleKey ID** 完成首次博世账号登录授权，随后点击 **Sync Lowest-Price Schedule Now** 即可开始体验现货电价自主供暖调度。
