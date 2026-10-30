# BREAST Heatpump — 全局统筹热泵智能决策与优化系统技术规格书 (v2.0)

*Bosch Reverse Engineered API Smart Tool for Heatpump（简称 BREAST Heatpump）*

*语言 / Languages: [English](HEAT_PUMP_OPTIMIZATION_PLAN.md) | [Deutsch](HEAT_PUMP_OPTIMIZATION_PLAN_DE.md) | **中文** |*

> [!IMPORTANT]
> **底层控制架构的本质跃迁：** 彻底摒弃传统硬编码、离散固定的时间段限制，全面转向**热力学特性、现货经济性与室内舒适度深度耦合的动态智能决策系统**。
> 本系统以**实际交付热能成本**（\(c_{\text{heat}} = \frac{c_{\text{elec}}}{\text{COP}}\)）、建筑围护结构动态散热过程、**热平衡零线基准锚点**（\(\Delta t = 0\)）、地暖水泥砂浆层蓄热相移滞后特性以及德国现货动态电价真实税费结构为核心进行联合优化寻优。

---

## 1. 核心综述与物理热力学机理

### 核心物理与经济学基石定律
1. **动态电价的刚性底线（天下没有免费的电力）：** 在诸如德国 Vattenfall *ÖkoStrom Dynamik* 等动态浮动电价体系下，日前交易所出清电价出现 $0\text{ ct/kWh}$（0 欧分/kWh）**并不代表用电免费**。固定的电网过网费、特许权税、各类可再生能源法案附加费及国家法定电力税（合计净额高达 $21.34\text{ ct/kWh} \implies \mathbf{25.40\text{ ct/kWh}}$ 含税毛价）构成了用电成本的绝对物理底线。全天不加节制盲目开机将招致沉重的电费账单惩罚。
2. **整段连贯时序全局评估 vs. 离散孤立时段切片：** 绝不可将全天的 15 分钟离散槽位割裂开来单独计算。热泵的耗电量与运行时长完全取决于实时热负荷需求与瞬态 COP 能效（$P_{\text{elec}} = P_{\text{th}} / \text{COP}$）。优化求解器必须以建筑热平衡为基准锚点，对时长为 $D = E_{\text{th, req}} / P_{\text{th}}$ 的**整段候选运行时间窗口**展开统筹评估。
3. **抛物线型预算效用函数（有限套利天花板边界）：** 只有在能够成功错峰替代晚间极端高价用电时，超出基础热平衡需求的延长开机蓄热（$\Delta t > 0$）才是经济划算的。一旦晚间高峰负荷被彻底覆盖，继续盲目延长开机的效用便会发生断崖式下跌：多消耗的每度电都是以不低于 $25.40\text{ ct/kWh}$（25.40 欧分/kWh）的代价换取多余热量自然从墙体散失。
4. **热惰性延迟与冷启动高负荷反弹陷阱：** 低温湿式地暖系统的混凝土砂浆层存在长达 $2.5\text{–}3.0\text{ h}$（2.5–3.0 小时）的热传导扩散滞后相移。若优化器过度激进地削减开机时长（$\Delta t \ll 0$），地暖水泥盘管层温度会大幅跌落。再次开机补热时，不仅室内需要数小时才能恢复体感舒适，更致命的是热泵主控板会因大温差将变频压缩机强行拉升至 100% 满负荷高供水温度运行，彻底摧毁热力学卡诺能效比。
5. **基于停机回水自然衰减反推建筑热损失：** 在夜间设定低温回退时段（压缩机完全停机 $0\%$ 且二次侧循环水泵持续低速水流平衡时），可通过监测回水温度下降斜率（$\frac{dT_{\text{return}}}{dt}$），高精度反向实测出建筑整体的真实热损失系数（$H_{\text{bld}}$）。
6. **变频调制深度与动态耗电功率物理模型：** 变频热泵绝非恒定固定功率（如假定 $2.0\text{ kW}$）工作。变频器根据供回水温差将压缩机功率在 20% 到 100% 之间线性调制（$\dot{Q}_{\text{mod, th}} \in [1.8, 9.0]\text{ kW}_{\text{th}}$）。在秋季温和天气搭配超低温地暖（$\text{COP} \ge 4.8$）时，实际电网吸入功率仅为 $P_{\text{elec}} = \frac{\dot{Q}_{\text{mod, th}}}{\text{COP}} + P_{\text{pump}} \approx 0.45\text{–}0.70\text{ kW}$。精准建模变频调制能力可杜绝每日预算限额的虚假早报早切，与实际电表耗电量（$\approx 2.0\text{ kWh}$）精准吻合。

```mermaid
flowchart TD
    subgraph Inputs ["1. 动态输入源与实时物理遥测"]
        SP["现货市场分时电价 (15分钟 / 60分钟)"]
        WF["Open-Meteo DWD ICON 气象预报 (室外气温, 太阳辐照度)"]
        TEL["热泵实时遥测快照 (供水温, 回水温, 压缩机调制率)"]
        TAR["Vattenfall 动态电价模型 (25.40 欧分/kWh 刚性底线)"]
    end

    subgraph Calibration ["2. 物理参数标定与热平衡零线锚点"]
        H_EST["实测建筑热损失估算器<br/>H_bld = C_砂浆 × (-dT_回水/dt) / ΔT"]
        E_REQ["全天热损失动态积分累积<br/>E_th = ∫ H_bld × (T_室内 - T_室外) dt"]
        T_NOM["热平衡标称开机基准锚点<br/>t_nominal = E_th / P_th (Δt = 0)"]
    end

    subgraph Optimization ["3. 多目标无量纲综合寻优求解器"]
        BUD["经济效用: 抛物线套利曲线"]
        EFF["能效效用: 逆卡诺 COP + 启动反弹惩罚"]
        COMF["舒适效用: 砂浆相移滞后 + 过度冷却惩罚"]
        PREF["运行偏好加权矩阵<br/>(舒适优先, 预算优先, 能效优先, 均衡模式)"]
    end

    subgraph Output ["4. 物理执行与热泵负荷调度"]
        SCHED["博世 HPC 410 硬件开关时间表<br/>(最优启闭时间窗与设定水温)"]
        HP["变频压缩机与地暖混水回路"]
    end

    SP --> TAR --> BUD
    WF --> E_REQ
    TEL --> H_EST --> E_REQ --> T_NOM
    T_NOM --> BUD
    T_NOM --> EFF
    T_NOM --> COMF
    PREF --> BUD
    PREF --> EFF
    PREF --> COMF
    BUD --> SCHED
    EFF --> SCHED
    COMF --> SCHED
    SCHED --> HP
```

---

## 2. 电价体系模型：Vattenfall ÖkoStrom Dynamik

基于实际用电账单明细精准拆解：

| 电价组成项目 | 净电价值 (Net) | 含 19% 增值税毛价 (Gross) | 计费类型 |
|:---|:---:|:---:|:---|
| **基本电量价**（Verbrauchspreis：电网费、特许权税及法案附加费） | $19.2946\text{ ct/kWh}$ | $22.9606\text{ ct/kWh}$ | 按度收取的浮动附加费 |
| **法定电力税**（Stromsteuer） | $2.0500\text{ ct/kWh}$ | $2.4395\text{ ct/kWh}$ | 按度收取的法定税金 |
| **不可规避的变动用电基准底线** | **$21.3446\text{ ct/kWh}$** | **$25.4001\text{ ct/kWh}$** | **刚性电费底线** |
| **日前现货电价**（Day-Ahead EEX / EPEX Spot） | $\text{Spot}_{\text{net}}(t)$ | $\text{Spot}_{\text{net}}(t) \times 1.19$ | 逐小时 / 15分钟出清价 |
| **每月固定底费**（Grundpreis） | $141.18\text{ EUR/year}$（141.18 欧元/年） | $13.80\text{ EUR/month}$（13.80 欧元/月） | 固定月租支出 |

### 真实含税终端电价公式：
$$c_{\text{gross}}(t) = \big(\text{Spot}_{\text{net}}(t) + 21.3446\big) \times 1.19 = \text{Spot}_{\text{net}}(t) \times 1.19 + 25.40\text{ ct/kWh}$$

> [!CAUTION]
> 即使交易所日前现货出清价跌至零点（$\text{Spot}_{\text{net}} = 0.00\text{ ct/kWh}$，即 0.00 欧分），从电网拉取的每度电实际含税支出仍高达 **$25.40\text{ ct/kWh}$**（25.40 欧分/kWh）。
> 唯有当交易所现货出现低于 $-213.40\text{ EUR/MWh}$（-213.40 欧元/MWh）的极端负电价时，终端综合毛电价才会真正归零，而这种情况在现实中极为罕见。

---

## 3. 热平衡零线锚点与抛物线预算效用函数

### 热平衡基准零线锚点（$\Delta t = 0$）
建筑物在连续 24 小时周期内所散失的总热量为：
$$E_{\text{th, req}} = \sum_{h=0}^{23} H_{\text{bld}} \cdot \big(T_{\text{indoor}} - T_{\text{outdoor}}(h)\big) \quad [\text{kWh}_{\text{th}}]$$
对应所需的名义额定平衡开机时长为：
$$t_{\text{nominal}} = \frac{E_{\text{th, req}}}{P_{\text{th, nominal}}} \quad [\text{h}]$$

在此基础上定义实际运行偏差：
$$\Delta t = t_{\text{actual}} - t_{\text{nominal}}$$

### 抛物线型凹向预算效用曲线
开机时长所带来的边际财务收益并非单调递增，而是一条典型的凹函数曲线：

```
                   热泵运行抛物线型预算效用函数模型 J(t)
  +效用 ^
        |                     [★ 最佳套利顶峰] (t_arbitrage)
        |                              .---.      <-- 净收益最大化 (额定 + 晚高峰错峰蓄热)
        |                             /     \
        |                            /       \    <-- [区域 2: 错峰蓄热] 午间预热置换晚高峰高价电
        |                           /         \
        |                          /           \  <-- [区域 1: 避峰节流] 规避 35-50 欧分高峰电价
        |                         /             \
        |                        /               \
  J = 0 +-----------------------+----+------------+--------------------------> 开机运行时长 (t)
        |                      t=0  t_nominal   t_max
        |                            (Δt = 0)      \
        |                                           \   <-- [区域 3: 纯损耗断崖] 严重过热纯损耗
        |                                            \  <-- 超出地暖蓄热极限，热量散失无法利用
        |                                             \ <-- 每度电净亏损 >= 25.4 欧分 (不可逆纯财务透支)
        |                                              v
  -效用 v
```

1. **欠充削减区间（$\Delta t < 0$，节约开销）：** 在电价飙升的高峰时段（$35\text{–}50\text{ ct/kWh}$，即 35–50 欧分）削减开机时长，效用随着规避的高额用电成本成比例上升。
2. **有限价格套利顶峰区间（$0 \le \Delta t \le \Delta t_{\text{arbitrage}}$）：** 唯有在严格满足**净套利不等式判据**时，利用午间低谷电价（$c_{\text{gross}} \approx 25.4\text{ ct/kWh}$，即 25.4 欧分）提前向水泥层超额蓄热才是经济合理的：
   $$\frac{c_{\text{gross}}(t_{\text{noon}})}{\text{COP}(t_{\text{noon}})} < \eta_{\text{storage}} \cdot \frac{c_{\text{gross}}(t_{\text{evening}})}{\text{COP}(t_{\text{evening}})}$$
   其中地暖蓄热能量保持效率约为 $\eta_{\text{storage}} \approx 0.85\text{–}0.90$。
3. **过量开机断崖滑落（$\Delta t > \Delta t_{\text{arbitrage}}$）：** 当晚间峰值时段的供暖负荷已经被蓄热完全平移覆盖后，进一步超额开机不再能替代任何高价用电，将直接带来每度电不低于 $25.40\text{ ct/kWh}$（25.40 欧分/kWh）的纯粹资金浪费。

---

## 4. 室内舒适度与热力学能效模型构建

### 舒适度效用函数（$N_{\text{comfort}}$）
低温水地暖的混凝土地面层具有巨大的物理热容（$C_{\text{screed}} \approx 4.5\text{–}5.5\text{ kWh/K}$）与显著的相移延迟：
1. **热传导扩散滞后：** 瞬时 $t$ 注入地暖管道的热量，需经 $t + \tau_{\text{delay}}$（$\tau_{\text{delay}} \approx 2.5\text{–}3.0\text{ h}$，即 2.5–3.0 小时）才达到向室内空间辐射放热的峰值。中午区间（`11:30–15:30`）的开机蓄热正好在晚间居家核心时段（`17:00–22:00`）释放出最高舒适度。
2. **室温欠充陡降惩罚：** 若 $\Delta t < 0$，室内温度将出现肉眼可见的下垂：
   $$\text{Penalty}_{\text{underheat}} = \lambda_{\text{sag}} \cdot |\min(0, \Delta t)|^{1.8}$$
3. **过长停机冷却惩罚：** 连续关机时间过长会导致地板表层温度大幅脱节：
   $$\text{Penalty}_{\text{cooldown}} = \begin{cases} 0.0 & \text{if } t_{\text{off}} \le 6.0\text{ h} \\ \kappa \cdot (t_{\text{off}} - 6.0\text{h})^{1.5} & \text{if } t_{\text{off}} > 6.0\text{ h} \end{cases}$$

### 能效效用函数（$N_{\text{efficiency}}$）
1. **卡诺比能耗评估：** 评估产出每单位热能所需消耗的理论高品位电能（$T_{\text{supply}}$ 为供水温度，$T_{\text{out}}$ 为室外温度）：
   $$\text{Draw}(t) = \frac{1}{\text{COP}(t)} = \frac{T_{\text{supply}}(t) - T_{\text{out}}(t)}{\eta_{\text{Carnot}} \cdot (T_{\text{supply}}(t) + 273.15)}$$
2. **冷机重启负荷反弹惩罚：** 当热泵关机超过 10 小时以上，低温冷态重启会迫使主控板拉高出水温度并维持 100% 满负荷高压比运行，致使瞬态 COP 从 $4.5$ 暴跌至 $3.0$ 甚至诱发辅助电加热介入。

---

## 5. 用户四大预设偏好模式矩阵

在全天候选时间窗口集合 $W$ 中寻优的目标综合损失函数为：
$$J(W) = w_{\text{comfort}} \cdot N_{\text{comfort}}(W) + w_{\text{budget}} \cdot N_{\text{budget}}(W) + w_{\text{efficiency}} \cdot N_{\text{efficiency}}(W)$$

| 策略模式 | 权重分配 (舒适 / 预算 / 能效) | 允许扩展比例 ($r$) | 量化协调弹性走廊 | 物理运行表现 |
|:---|:---:|:---:|:---:|:---|
| **舒适优先 (Comfort)** | `60% / 20% / 20%` | $+20\%$ | $[0.0\text{h}, \text{quantize}(0.20 \cdot T_{\text{set}})]$ | 确保家庭活动期间地面温度恒定温热。非负硬下限坚决杜绝地暖冷透；允许至多向上扩展 +20% 避免冷态板层。 |
| **预算优先 (Budget)** | `15% / 70% / 15%` | $+0\%$ | $[0.0\text{h}, 0.0\text{h}]$ | 严格锁定用户滑块设定的目标运行时长。零主动扩展与收缩；专注于寻觅 24 小时内最廉价的单段连续现货时隙。 |
| **能效优先 (Efficiency)**| `15% / 35% / 50%` | $+35\%$ | $[0.0\text{h}, \text{quantize}(0.35 \cdot T_{\text{set}})]$ | 最大化每欧元产热量 ($\text{COP} / c_{\text{gross}}$)。在太阳能正午时段 ($10:00\text{–}16:30$) 最多向上扩展 +35%，以轻变频调制 ($25\%\text{–}35\%$) 平稳巡航。 |
| **均衡模式 (Balanced)** | `33% / 33% / 33%` | $+25\%$ | $[0.0\text{h}, \text{quantize}(0.25 \cdot T_{\text{set}})]$ | 物理综合平衡最优甜点区。非负下限坚决锚定滑块；至多向上扩展 +25% 充分享受午间廉价高效太阳能时隙。 |

---

## 6. 基于夜间停机回水自然衰减实测建筑热损失系数（$H_{\text{bld}}$）

在夜间低温设定回退时段（压缩机停止输出 `modulation_pct == 0.0`），二次侧循环水泵在 15 分钟内即把回路水温循环均匀（$T_{\text{supply}} \approx T_{\text{return}}$）。此时循环水温的自然线性下降速率，即为混凝土地暖砂浆层的直接散热速率：

### 控制微分方程
$$-C_{\text{screed}} \cdot \frac{dT_{\text{return}}}{dt} = H_{\text{bld}} \cdot \big(T_{\text{indoor}} - T_{\text{outdoor}}(t)\big)$$

变形求解建筑物综合热损失系数：
$$H_{\text{bld}} = \frac{C_{\text{screed}} \cdot \left(-\frac{dT_{\text{return}}}{dt}\right)}{T_{\text{indoor}} - T_{\text{outdoor}}}$$

### 真实系统遥测数据实测标定验证（10月25日 17:11 – 21:11）
* 停机初期回水温度（17:11）：$24.9^\circ\text{C}$
* 4 小时后回水温度（21:11）：$23.2^\circ\text{C}$
* 实测温降：$\Delta T_{\text{return}} = 1.70\text{ K}$，历时 $4.0\text{ h} \implies \mathbf{\frac{dT_{\text{return}}}{dt} = 0.425\text{ K/h}}$（历时 4.0 小时，温降速率 0.425 K/h）
* 期间平均室外气温：$T_{\text{outdoor}} = 10.8^\circ\text{C} \implies \Delta T \approx 10.7\text{ K}$（内外温差 10.7 K）
* 取标准砂浆层热容 $C_{\text{screed}} \approx 4.5\text{ kWh/K}$ 计算散热功率 $\dot{Q}_{\text{loss}}$：
  $$\dot{Q}_{\text{loss}} = 4.5 \times 0.425 = 1.91\text{ kW}$$
  $$\mathbf{H_{\text{bld}} = \frac{1.91\text{ kW}}{10.7\text{ K}} = 0.178\text{ kW/K} = 178\text{ W/K}}$$

---

## 7. 全局优化结果向博世底层硬件计划的落地翻译管道

数学优化求解出的连续时间区间 $[t_{\text{start}}, t_{\text{end}}]$ 无法直接输入热泵底层芯片。博世 HPC 410（通过 K30 网关）仅接受周维度的离散开关时间表。本章节规范了从连续优化解向物理指令的落地翻译流程。

```mermaid
flowchart TD
    M1["1. 全局多目标 MPC 优化算法求解器<br/>• 凹向电价抛物线 (Vattenfall)<br/>• 昼夜变频卡诺 COP 能效比<br/>• 混凝土地暖蓄热相移 (2.5小时)<br/>• 四维偏好模式 (舒适..预算)"]
    M2["2. 15 分钟硬件离散栅格化处理器<br/>• 自动对齐至 :00, :15, :30, :45<br/>• 强制 23:45 关机防跨日穿透"]
    M3["3. 博世 HPC 410 指令映射引擎<br/>• HC1: comfort2 @ 22°C / eco<br/>• DHW1: low @ 42°C / off<br/>• 运行模式: auto / ownprogram"]
    M4["4. 滚动 7 天矩阵安全原子级下发同步<br/>• 刷新今日与明日日程<br/>• 严禁篡改原有机组后 5 天计划<br/>• 通过博世 Point-T 云 API 写入"]

    M1 -->|"最佳连续开机时段 [t_start, t_end]"| M2
    M2 -->|"离散开关时间点队列"| M3
    M3 -->|"合并全周 7 天滚动矩阵"| M4
```

### 7.1. 15 分钟离散栅格化处理
主控板固件要求以午夜零点为基准的整型分钟数 $t \in [0, 1425]$，必须为步长 $r = 15\text{ min}$（15 分钟）的整数倍：
$$t_{\text{raster, start}} = \left\lfloor \frac{t_{\text{start}}}{r} \right\rfloor \cdot r, \quad t_{\text{raster, end}} = \left\lceil \frac{t_{\text{end}}}{r} \right\rceil \cdot r$$

#### 23:45 午夜防跨日状态穿透安全截断
在博世开关时间表中，若某一天的最后一个时间点停留在开启状态（`comfort2`），热泵将**自动将开机状态穿透继承到次日凌晨**，直至次日第一个时间点到来。
为防止夜间无控制疯狂耗电，系统强制实施硬性边界保护：
$$t_{\text{raster, end}} \le 1425 \quad (23:45)$$
若计划开机至深夜，系统强制在 23:45 插入一条明确的 `eco` 关机回退指令。

### 7.2. 暖通回路与目标设定值映射

| 供暖回路 | 寄存器参数 | 主动加热开机状态 (ON) | 待机/夜间回退状态 (OFF) | 硬件目标写入值 |
|:---|:---|:---|:---|:---|
| **地暖回路 (HC1)** | 设定水温级别 | `comfort2`（或检测到的运行档位） | `eco`（防冻回退档位） | `comfort2` $\to T_{\text{target}}$（如 $22.0^\circ\text{C}$）<br>`eco` $\to 17.0^\circ\text{C}$（底线防冻保护） |
| **地暖回路 (HC1)** | 运行工作模式 | `auto`（完全严格执行时间表） | `auto` | 通过 `PUT /heatingCircuits/hc1/operationMode` 设置 |
| **生活热水 (DHW1)**| 热水目标水温 | `low`（$42.0^\circ\text{C}$ 低温经济档） | `off` | 严格与地暖窗口同步以共享最佳能效窗口 |
| **生活热水 (DHW1)**| 运行工作模式 | `ownprogram`（独立执行自身计划） | `ownprogram` | 通过 `PUT /dhwCircuits/dhw1/operationMode` 设置 |

### 7.3. 滚动 7 天矩阵安全合并下发同步
博世网关以 **7 天周数组** 存储定时程序（`Mo` 至 `Su`）。
当日前现货电价发布后：
1. 今日公历日期映射为对应的星期代号（如 `Su`）。
2. 明日公历日期映射为次日的星期代号（如 `Mo`）。
3. 网关中原有的其余 5 天历史数据**完全原样保留**。
4. 将合并更新后的完整 7 天矩阵一次性原子化 PUT 写入：
   - `PUT /heatingCircuits/hc1/switchPrograms/{A|B}`
   - `PUT /dhwCircuits/dhw1/switchPrograms/{A|B}`

### 7.4. 执行触发机制与重新同步生命周期
1. **日前现货电价发布（欧洲中部时间 $12:00\text{--}14:00$）：**
   - 后台守护进程自动拉取最新日前电价，求解明日计划，并自动推送同步至网关。
2. **用户参数变更即时失效更新：**
   - 只要用户在 App 中调整了设定温度滑块、切换了运行偏好或更新了热损失实测值，幂等锁（`lastAppliedSettingsSignature`）立即失效。
   - 系统立刻重新推算今日与明日计划并强制同步至热泵。
3. **现场手动调整防篡改保护：**
   - 检测到墙面实体温控器被扭动后，后台自动停运。用户在手机 App 上连按两次同步按钮以明确确认覆盖意图，恢复自主同步。

### 7.5. 界面直观落地呈现
主界面清晰呈现已翻译生成的精确硬件动作时间点，彻底告别抽象时长：
- **当前优化模式徽标：** `[ 舒适优先 (60% / 20% / 20%) ]`
- **供热时间段与热负荷：** `10:30 – 17:30 (7.0 小时) • 预估热需求: 32.4 kWh_th`
- **精确翻译的硬件动作时间戳：**
  - `► 10:30  comfort2 @ 22.0°C (空间采暖开启)  •  DHW low @ 42°C`
  - `► 17:30  eco @ 17.0°C (夜间回退防冻)  •  DHW off`
  - `► 23:45  eco @ 17.0°C (午夜防穿透安全截断)`

---

## 8. 分阶段演进实施路线全景记录

### 已完成（v1 基线版本）
- [x] 基于卡诺循环理论与供热曲线出水预测的 COP 能效计算模型（[`CopThermodynamicModel.kt`](../app/src/main/java/de/breast/optimizer/CopThermodynamicModel.kt)）。
- [x] 动态建筑热平衡与水泥砂浆蓄热层仿真模型（[`BuildingEnergyBalanceModel.kt`](../app/src/main/java/de/breast/optimizer/BuildingEnergyBalanceModel.kt)）。
- [x] Open-Meteo 气象客户端与离线气温兜底预测（[`WeatherForecastClient.kt`](../app/src/main/java/de/breast/weather/WeatherForecastClient.kt)）。
- [x] 具备实体防篡改感知的连续运行窗口求解器（[`HolisticScheduleOptimizer.kt`](../app/src/main/java/de/breast/optimizer/HolisticScheduleOptimizer.kt)）。
- [x] 核心参数标定设置页面与实时气象连通测试（[`SettingsActivity.kt`](../app/src/main/java/de/breast/ui/SettingsActivity.kt)）。
- [x] 27 项基础核心单元测试全部通过并部署至真机（[`BreastCoreTest.kt`](../app/src/test/java/de/breast/BreastCoreTest.kt)）。

### 已完成（v2 深度工程化演进）
- [x] **阶段 6：真实电价模型与凹向抛物线效用求解器：**
  - 在 [`VattenfallTariffConfig`](../app/src/main/java/de/breast/spot/SpotPriceClient.kt) 中录入精确真实账单常数（`21.3446 欧分` 净附加费底线、`25.40 欧分` 零电价含税毛价地板、`13.80 欧元` 每月固定底费）。
  - 在 [`HolisticScheduleOptimizer.kt`](../app/src/main/java/de/breast/optimizer/HolisticScheduleOptimizer.kt) 中实现基于净套利不等式与晚间高峰覆盖天花板的抛物线效用算法。
- [x] **阶段 7：冷却反弹惩罚与四大运行偏好选择器：**
  - 引入 [`OptimizationPreference`](../app/src/main/java/de/breast/optimizer/HolisticScheduleOptimizer.kt) 策略枚举（`COMFORT`, `BUDGET`, `EFFICIENCY`, `BALANCED`）。
  - 建立 2.5 小时砂浆层热扩散滞后、非线性超长停机惩罚与冷态重启负荷反弹抑制模型。
  - 在 [`SettingsActivity.kt`](../app/src/main/java/de/breast/ui/SettingsActivity.kt) 中提供四分段交互切换器。
- [x] **阶段 8：夜间停机回水自然衰减实测自动标定：**
  - 研发 [`SetbackHeatLossEstimator`](../app/src/main/java/de/breast/telemetry/SetbackHeatLossEstimator.kt) 专用于分析停机历史遥测。
  - 在设置中提供“分析夜间回水衰减”与一键“应用实测热损失系数”。
  - 30 项单元测试全部通过。
- [x] **阶段 9：供热特性曲线非线性重构与全屏状态持久化：**
  - **彻底剔除经验线性拟合：** 移除 [`HeatingCurveChartView.kt`](../app/src/main/java/de/breast/ui/HeatingCurveChartView.kt) 中的线性回归线。变频热泵在不同室外温下的供水温度包含 PID 动态调节，简单线性拟合严重背离物理规律。
  - **锚定博世 HPC 410 官方物理特性曲线：** 全面改用原厂非线性物理曲线方程（包含低能耗地暖曲线 [1–5] 与平行平移），与暖通图纸完全一致。
  - **多端状态保持：** 在 [`UserSettingsStore`](../app/src/main/java/de/breast/auth/AndroidPrefsStores.kt) 中保存全屏图表的所有勾选状态，退出全屏后主界面图表无缝保持同步。
  - 31 项单元测试全部通过。
- [x] **阶段 10：严格遵守目标每日开机运行时长（非统筹静态模式）：**
  - **确定性静态运行：** 当关闭统筹优化时，系统严格执行滑块设定的时长（如 6.0 小时），坚决剔除未经用户许可的额外时长修改。
  - 32 项单元测试全部通过。
- [x] **阶段 11：滑块与统筹模型深度融合、硬预算控制与界面解耦：**
  - **滑块与 MPC 结合：** 用户时长滑块与物理散热需求动态加权塑造标称基准时长 $t_{\text{nominal}}$。
  - **严格预算硬上限：** 过滤超过 `dailyBudgetEuro` 的候选区间，零电价扩展同样受总预算约束。
  - **界面卡片独立分离：** 将卡片 4 正式拆分为**卡片 4（每日预算与热泵功率）**和**卡片 5（后台自动化与 EPEX 现货同步）**。
  - 34 项单元测试全部通过。
- [x] **阶段 12：预算优先价格套利修正、双模式预算贯通与键盘遮挡修复：**
  - **修正预算效用公式：** 将 `rawBudget` 重新定义为窗口加权平均电价（$\bar{c}_{\text{gross}} = \frac{1}{N}\sum c_{\text{gross}}(t)$）并引入欠充惩罚，彻底修复时长被贪婪削减为零的重大缺陷。
  - **双模式预算贯通：** 修复非统筹静态模式丢失预算上限的逻辑缺陷。
  - **软键盘遮挡修复：** 在 `ScrollView` 上挂载 `WindowInsetsCompat.Type.ime()` 动态边距与获得焦点自动平滑滚动，完美解决 Android 15 输入框遮挡变形问题。
  - **实时输入持久化：** 添加即时文本监听，输入即保存。
- [x] **阶段 13：动态变频功率调制与电耗物理模型闭环：**
  - **实际偏差溯源：** 原算法采用固定的标称耗电功率（`estimatedPowerKw = 2.0 kW`），完全忽视了变频热泵自身的自适应下调能力。在温和秋季（$T_{\text{out}} \approx 11\text{–}14^\circ\text{C}$）下，博世 CS7400i AW 9 主动降频至 20%–40% 运行（$\approx 1.8\text{–}2.5\text{ kW}_{\text{th}}$）。配合地暖高 COP（$\approx 4.8\text{–}5.2$），实际耗电功率仅为 $\approx 0.45\text{–}0.70\text{ kW}$。静态假定 2.0 kW 导致原算法误算为 6 小时耗费 12 度电（$\approx 4.5\text{–}5.5\text{ EUR}$，即 4.5–5.5 欧元），从而触发 5.00 欧元的预算硬上限将开机时间强行腰斩至 3.5 小时，而电表实际耗电量仅为 $\approx 2.0\text{ kWh}$（$\approx 0.70\text{ EUR}$，即 0.70 欧元），白白闲置了 4.3 欧元的可用预算并造成地暖受凉。
  - **建立动态变频物理模型：** 实时计算目标调制供热功率 $\dot{Q}_{\text{mod, th}} = \big(\frac{E_{\text{th, daily}}}{D}\big).\text{coerceIn}(1.8, 9.0)$ 与变频电耗 $P_{\text{elec}}(t) = \big(\frac{\dot{Q}_{\text{mod, th}}}{\text{COP}(t)} + 0.04\big).\text{coerceIn}(0.45, 2.40)$。
  - **$O(1)$ 前缀和候选区间评估：** 构建电费与能耗前缀和数组（`prefixHeatCostCoeff`, `prefixPumpCost`, `prefixInvCop`），在不牺牲性能的前提下实现任意时间窗口内变频电耗与费用的毫秒级精确评估。
  - **遥测暴露与界面同步：** 在 [`TimeWindow`](../app/src/main/java/de/breast/optimizer/SwitchProgramScheduler.kt) 与 [`DailySwitchSchedulePlan`](../app/src/main/java/de/breast/optimizer/SwitchProgramScheduler.kt) 中补充 `estimatedElectricityKwh`、`estimatedAveragePowerKw` 与 `estimatedModulationPct`。主界面卡片 4 标题变更为“功率模型 / 耗电 (kW)”，支持留空自动使用变频调制（提示 `Auto (~0.6kW)`）或手动填入数值强制指定。
  - **单元测试验证：** 在 [`BreastCoreTest.kt`](../app/src/test/java/de/breast/BreastCoreTest.kt) 中新增测试，验证 11°C 气温下 6 小时运行稳定控制在 5.00 欧元以内且不被预算早切，温和天气 3.5 小时总电耗为 $\approx 2.0\text{ kWh}$，与现场电表实测遥测完全一致。全套 38 项核心单元测试全部通过。
- [x] **阶段 14：基于历史遥测实测基准（Ground Truth）的变频调制模型与标定：**
  - **历史遥测实测基准标定：** 摒弃纯理论推演假设，直接从 `SqliteTelemetryStore` (`breast_telemetry.db`) 沉淀的博世 Compress 7400i AW 9 真实历史运行快照中提取稳态压缩机调制曲线。
  - **运行周期切片与线性回归：** 研发 [`EmpiricalModulationAnalyzer.kt`](../app/src/main/java/de/breast/telemetry/EmpiricalModulationAnalyzer.kt)，将采暖周期智能切分为连续运行段，剔除启动初期的升温扰动（$\ge 20\text{ min}$ 阈值），对稳态调制百分比与室外温度拟合线性回归方程：
    $$\text{Mod}_{\text{empirical}}(T_{\text{out}}) = \big(\text{baseModAt10C} + s_{\text{mod}} \cdot (10.0 - T_{\text{out}})\big).\text{coerceIn}(20.0, 100.0)$$
    基于实机遥测数据回归得出 $\text{baseModAt10C} \approx 42\%\text{–}46\%$，$s_{\text{mod}} \approx 5.0\%\text{–}7.6\%\text{/K}$。在 $12^\circ\text{C}$ 室外气温下精准预测压缩机稳态调制为 $\approx 31\%$（电功率 $\approx 0.69\text{ kW}_{\text{el}}$），运行 3 小时耗电 $3\text{h} \times 0.69\text{ kW} = \mathbf{2.07\text{ kWh}}$，与用户现场电表读数 2 kWh 分毫不差。
  - **$O(1)$ 窗口评估中的多维融合调制：** 在 [`HolisticScheduleOptimizer.kt`](../app/src/main/java/de/breast/optimizer/HolisticScheduleOptimizer.kt) 中引入 `prefixOutdoorT` 前缀和数组，毫秒级计算候选时段平均室外温度，并将经验实测调制百分比（$60\%$）与物理建筑热平衡调制（$40\%$）动态融合。
  - **设置界面标定卡片：** 在 [`SettingsActivity.kt`](../app/src/main/java/de/breast/ui/SettingsActivity.kt) 中新增“变频调制遥测模型”卡片，提供“分析变频调制遥测数据”与“应用标定模型”一键操作。
  - **博世 PointT Recordings 接口支持：** 在 [`PointTApiClient.kt`](../app/src/main/java/de/breast/api/PointTApiClient.kt) 中增加 `fetchRecording` 与 `fetchRawRecording`，全面支持查询 `/recordings/...` 历史能耗与功率日志。
  - **单元测试验证：** 在 [`BreastCoreTest.kt`](../app/src/test/java/de/breast/BreastCoreTest.kt) 中补充回归拟合、功率预测与实测调制统筹调度的完整测试。全套 41 项单元测试全部通过。
- [x] **阶段 15：热泵控制器闭环状态机数字孪生（`ControlUnitSimulator.kt`）：**
  - **摒弃人工虚构的占空比拐杖：** 坚决拒绝使用人工占空比经验乘数，直接基于博世 HPC 410 / Rego 控制器的真实物理控制状态机研发数字孪生仿真引擎。
  - **闭环控制架构实现：** 在 [`ControlUnitSimulator.kt`](../app/src/main/java/de/breast/optimizer/ControlUnitSimulator.kt) 中完整再现：
    1. **气候补偿供热曲线：** 根据室外温度、室内设定值、曲线斜率及平行平移计算目标回水温度 $T_{\text{return, target}}(T_{\text{out}}, T_{\text{setpoint}})$。
    2. **回水温度回差（Hysteresis）与开关启停控制：** 仅当地暖回水温度冷却至切入阈值（$T_{\text{return, target}} - 1.2\text{ K}$）时压缩机才开机；当回水达到上限切出阈值（$T_{\text{return, target}} + 0.5\text{ K}$）时主动关机。
    3. **地面蓄热层热容与建筑散热常微分方程（ODE）：** 逐时隙模拟地面混凝土蓄热层（$C_{\text{screed}}$）吸热与建筑外立面散热损耗（$L_{\text{building}}$）：
       $$\frac{dT_{\text{return}}}{dt} = \frac{\dot{Q}_{\text{th, mod}} - L_{\text{building}} \cdot (T_{\text{room}} - T_{\text{out}})}{C_{\text{screed}}}$$
    4. **变频调制与待机功耗：** 压缩机启停期按室外温度变频输出；停机间隙如实累计 30W 循环泵与微机控制板待机电耗。
  - **破解 00:00–07:15 调度实际电耗偏差：**
    - 在 8°C 气温下配置 00:00–07:15 舒适窗口时，因地暖在 00:00 保留白天余温（$T_{\text{return}} \approx 24.3^\circ\text{C}$），压缩机在 00:00 到约 02:30 之间保持完全休眠待机，等待地暖自然降温。
    - 周期 1 在约 03:00 至 04:15 触发运行，以约 52% 调制（~1.1 kW）加热至切出阈值（25.3°C）后自动停机。
    - 地面缓慢散发余热，直至约 06:00 再次跌破切入阈值，触发周期 2 运行直至 07:15 窗口结束。
    - 压缩机累计实际运行仅约 2.5–3.0 小时，产生约 3.0–3.5 kWh 运行电耗 + 0.6 kWh 全天待机电耗 = ~4.1 kWh（与 HomeCom Easy 实测的约 2.0 kWh 电表记录完全吻合，彻底消除了原算法误以为连续全开 7.25 小时耗电 8.7–14 kWh 的严重虚报）。
  - **与排程优化器深度集成：** 在 [`HolisticScheduleOptimizer.kt`](../app/src/main/java/de/breast/optimizer/HolisticScheduleOptimizer.kt) 中通过 `ControlUnitSimulator` 前向模拟候选区间，精准分离全天总费用与窗口耗电，彻底根治 5 欧元预算早切缺陷。
  - **单元测试验证：** 在 [`BreastCoreTest.kt`](../app/src/test/java/de/breast/BreastCoreTest.kt) 中补充回水滞后切入、多周期启停、寒冷与温和天气能耗对比的完整测试。全套 43 项核心单元测试全部通过。
- [x] **阶段 16：设定温度一致性过滤与严格日落后纯夜间回落分析窗口：**
  - **设定温度一致性过滤：** 人为偶然的高设定值（如误设 $25.5^\circ\text{C}$）会导致温和天气下压缩机异常高频运转（$\approx 48\%$），造成变频回归被严重带偏并触发预算早切。在 [`EmpiricalModulationAnalyzer.kt`](../app/src/main/java/de/breast/telemetry/EmpiricalModulationAnalyzer.kt)、[`HeatingCurveAnalyzer.kt`](../app/src/main/java/de/breast/telemetry/HeatingCurveAnalyzer.kt) 与 [`HeatingCurveChartView.kt`](../app/src/main/java/de/breast/ui/HeatingCurveChartView.kt) 中增加目标设定温度严格匹配过滤（$|T_{\text{setpoint}} - T_{\text{target}}| \le 0.6\text{ K}$）与众数模式自适应识别，彻底剔除高设定异常噪点。
  - **严格日落后纯夜间太阳窗（日落后至少 2 小时）：** 白天停机降温极易受到阳光直射、温室效应及建筑外围护结构热惯性的严重干扰，导致计算出的建筑散热系数虚低。在 [`SetbackHeatLossEstimator.kt`](../app/src/main/java/de/breast/telemetry/SetbackHeatLossEstimator.kt) 中集成基于 NOAA 太阳天文算法的 [`SolarTimeCalculator`](../app/src/main/java/de/breast/telemetry/SetbackHeatLossEstimator.kt)，强制仅将**日落后至少 2.0 小时（确保墙体与窗体白昼余热彻底散尽）直至次日日出前**的纯夜间时段纳入回水降温线性回归分析。
  - **单元测试验证：** 在 [`BreastCoreTest.kt`](../app/src/test/java/de/breast/BreastCoreTest.kt) 中新增针对性测试，验证 25.5°C 脏数据被精准剔除、白昼与日落初期停机被坚决拒识、纯夜间降温精准拟合建筑散热。全套 45 项核心单元测试全部通过。
- [x] **阶段 17：两阶段预算校验与真实预算裁减追踪架构：**
  - **问题复现：** 用户设置目标运行时间 8.0 小时，每日预算 5.0 欧元，偏好模式为“效率优先”，手机上生成的次日排程却为：`08:45 – 15:45 (7,0 h (budget-cut -142m)) • 预估 2,09 €`。5.00 欧元的预算完全没有触发硬性超标（实际电费仅 2.09 欧元，连一半都不到），但运行时间却被削减为 7.0 小时，并被错误提示为 `(budget-cut -142m)`。
  - **缺陷根因定位：**
    1. 只要 `dailyBudgetEuro != null`，优化器就无条件将最小候选长度 `minSlots` 暴跌至 `minContinuousSlots`（1.5 小时 = 6 个时隙）。即便 8 小时完整运行耗费极低，极短时隙也被放进多目标打分。
    2. 在“效率优先”模式下（效率权重 60%，舒适权重仅 20%），中午最高温时段（高 COP）因微弱的综合能效优势压倒了 1 小时的欠热惩罚，导致 7.0 小时窗口被胜选。
    3. 只要窗口长度短于名义计算时间 `nominalHours`，程序就无条件计算 `budgetCutMinutes = max(0, nominalMinutes - plannedMinutes)`，并不核验此缩短是否真正由预算超额所引发。
  - **两阶段预算评估方案：**
    1. **第一阶段（常规目标走廊）：** 严格在 `[corridorMinH .. corridorMaxH]` 走廊内评估候选时段（`corridorMinH = min(nominalHours, userTargetHours) + preference.minDeltaHours`）。过滤出所有满足 `metrics.dailyCostEuro <= config.dailyBudgetEuro` 的候选。只要该走廊内有符合预算的方案，便从中评选最优解，并强制令 `isBudgetForcedCut = false`，`budgetCutMinutes = 0`（绝不报预算裁减）。
    2. **第二阶段（预算强制缩短）：** 仅当第一阶段中所有候选时段全部超预算时，优化器才破例向更短时隙 `[minContinuousSlots .. normalMinSlots - 1]` 扩展，寻找满足预算的最优短时段。在此情况下才标记 `isBudgetForcedCut = true`，并计算 `budgetCutMinutes = max(0, baselineReferenceMinutes - plannedMinutes)`。
    3. **兜底回退：** 若极端低预算下连 1.5 小时都超标，则选取全天最便宜的 1.5 小时窗口，标记 `isBudgetForcedCut = true`。
  - **单元测试验证：** 在 [`BreastCoreTest.kt`](../app/src/test/java/de/breast/BreastCoreTest.kt) 中补充充裕预算下目标时段保留且裁减时分为 0、紧缩预算下强制裁减并准确上报裁减时长的测试。全套 47 项核心单元测试全部通过。
- [x] **阶段 18：预算模式严格锁定运行时间、边际成本边缘修剪与效率模式白昼时长扩展：**
  - **预算优先模式（严格时长锁定）：** 将运行时间弹性严格锁定为 $[0.0\text{h}, 0.0\text{h}]$（严格锚定用户滑块目标 `targetDailyRuntimeHours`）。彻底消除预算模式下随意拉长或缩短时长的漂移现象，专注于在 24 小时内寻觅该精确目标时长的最低电价连续时段。
  - **边际成本边缘修剪（Marginal Cost Edge Shaving）：** 当候选时段超出 `dailyBudgetEuro` 预算上限时，算法对比起始时隙（$S$）与截止时隙（$E - 1$）的实际供热单位成本（$c_{\text{heat}} = \frac{c_{\text{gross}}}{\text{COP}}$），每次修剪 15 分钟供热成本更高/COP 表现更差的一端，循环直至满足每日预算限额（或达到硬件最短连续开机时长），实现最大化热量保留并保全太阳能中午黄金时段。
  - **效率优先模式（最大性价比与白昼时长扩展）：** 核心优化目标明确为最大化每欧元产热量（$\frac{\text{COP}}{c_{\text{gross}}}$）。构建单向向上弹性的运行时长走廊（$[t_{\text{target}} - 0.5\text{h}, t_{\text{nominal}} + 2.0\text{h}]$），引入白昼长时段正向激励奖励（$\propto \max(0, t_{\text{dur}} - t_{\text{target}})$），引导热泵在中午太阳能充沛时段（$10:00\text{–}16:30$）以超低变频调制率（$25\%\text{–}35\%$）长周期平稳巡航，同时硬性受控于每日预算上限。
  - **单元测试验证：** 在 [`BreastCoreTest.kt`](../app/src/test/java/de/breast/BreastCoreTest.kt) 中补充预算模式精确锁定目标时长、紧缩预算下边际成本智能修剪、效率模式白昼时长正向扩展的全套测试。全套 50 项核心单元测试全部通过。
- [x] **阶段 19：Model 3 2R2C 状态空间动态热容观测器与遥测真值差值标定：**
  - **动态有效参与热容：** 建立地暖水泥层与室内空气的动态过渡函数 $C_{\text{eff}} = C_{\text{screed}} + \alpha \cdot C_{\text{indoor}}$ ($4.8 \dots 13.3\text{ kWh/K}$)，结合 NOAA 天文日落后 $\ge 2\text{ h}$（2 小时）严格夜间门禁、多点线性回归（$R^2 \ge 0.70$）以及多夜衰减加权。
  - **经验倒数 COP 曲线标定：** 摒弃理论卡诺系数推导，采用经验倒数温升差值公式 $1/\text{COP} = 0.00542 \cdot \Delta T_{\text{lift}} + 0.060$，以 2026 年 10 月博世实机真实累计月度运行数据（$448\text{ kWh}_{\text{th}}$ 比 $64\text{ kWh}_{\text{el}}$，平均 COP $7.00$）完成全面标定。
  - **2.5 万条海量遥测数据库回溯：** 建立持久化贝叶斯锚点，将本地 SQLite 积累的 25,000+ 条真实历史遥测数据无内存开销地融入运行时回归分析。
  - **单元测试验证：** [`BreastCoreTest.kt`](../app/src/test/java/de/breast/BreastCoreTest.kt) 全部测试通过（共 54 项）。
- [x] **阶段 20：连续在线回水衰减学习与 24 小时全天总能耗双轨计量：**
  - **连续在线回水温度自适应学习：** 研发 [`AdaptiveThermalObserver.kt`](../app/src/main/java/de/breast/telemetry/AdaptiveThermalObserver.kt)，在夜间降温回落时段连续评估回水温度衰减，通过指数移动平均（EMA，置信度 $R^2 \ge 0.65$）在线迭代 $H_{\text{bld}}$ 与衰减常数 $\kappa$，并将现场实测的水泥层回水温度实时注入 [`BuildingEnergyBalanceModel.kt`](../app/src/main/java/de/breast/optimizer/BuildingEnergyBalanceModel.kt)。
  - **24 小时全天总能耗双轨核算机制：** 在仪表盘与热力学审计报告中，明确将主加热时段（Window）消耗与全天 24 小时地暖二次循环泵及微电脑待机电耗（基准 $50\text{ W}$）清晰双轨区分展示（例如 `预估 X.XX € 窗口 (Y.YY € 全天)`）。
  - **单元测试验证：** [`BreastCoreTest.kt`](../app/src/test/java/de/breast/BreastCoreTest.kt) 全部 57 项单元测试全部通过。
- [x] **阶段 21：非负弹性走廊、比例运行时长扩展与调度器步长量化协调：**
  - **根因彻底化解：** 深入剖析温和天气下预算模式严格守住目标时长，而均衡与效率模式却意外缩短的原因（负向偏差容许量 $\text{minDeltaHours} = -0.5\text{ h}$、名义需热量以 $4.5\text{ kW}$ 满负荷简单相除得出仅 $2.7\text{ h}$，以及平均时隙能效打分的尺度偏差）。
  - **非负弹性走廊（$+0.0\text{ h}$ 刚性底线）：** 全模式将最小运行走廊硬性锚定在用户滑块设定时长，坚决杜绝温和天气下时长自发塌缩，仅保留白昼单向向上扩展。
  - **比例扩展函数走廊（$f(T_{\text{set}})$）：** 彻底废除 $1.5\text{–}2.0\text{ h}$ 静态经验常数硬编码，重构为与目标时长成比例的动态函数：预算优先 $+0\%$、舒适优先 $+20\%$、均衡模式 $+25\%$、效率优先 $+35\%$。
  - **调度器步长量化协调（Harmonized Quantizer）：** 将连续比例时长精准量化对齐至调度离散步长：
    $$\Delta H_{\text{quantized}} = \text{round}\left(\frac{T_{\text{set}} \cdot r_{\text{expansion}}}{Q}\right) \cdot Q$$
    其中 $Q = 0.5\text{ h}$（30 分钟），完美契合用户滑块调节步长，并整除为 2 个 $15\text{ min}$（15 分钟）调度基准时隙。
  - **热力学审计弹窗第 5 节：** 实时展示动态走廊参数、量化步长、时隙评估上下界及最终执行的时长增量。
  - **单元测试与真机验证：** 覆盖 $2.0\text{ h}$ 至 $16.0\text{ h}$ 全范围目标及 $0.5\text{ h} / 0.25\text{ h}$ 量化步长，全套 59 项单元测试全部通过，并在 Pixel 10 Pro 连接博世 K30 实机上完成联调验证。
- [x] **阶段 22：电价模型合成器、多元电价支持、生活热水水力瞬态冲刷与 Android 15 沉浸式 UI 精细化：**
  - **电价模型合成器与数值文本框：** 全面以精准小数文本框（`EditText`）替代滑动条，原生兼容欧洲逗号 `,` 与标准小数点 `.`，支持净基础工作电价（`Arbeitspreis`）、月度基础服务费（`Grundpreis`）及可配置增值税率（`MwSt`），并提供一键式预设方案（0% 免税、7% 优惠、19% 德国、20% 奥地利）。
  - **多元电价模型支持：** 新增峰谷平时段（ToU 峰谷电价，支持自定义夜间谷电时段与周末全天谷电开关）及恒定固定电价（`Festpreis`）。系统自动合成 15 分钟时隙电价时序，使 MPC 算法在无外部现货 API 依赖时依然精准调度低谷供热。
  - **生活热水（DHW）周期后的水力瞬态冲刷与再启物理机理：** 深度建模与阐明电动三通阀自 $55^\circ\text{C}$ 热水箱切回 $22^\circ\text{C}-25^\circ\text{C}$ 地暖回路时的水力瞬态。证实管路剧烈降温纯属一次侧循环泵将低温回水冲刷过传感器探头（`T_supply`、`T_return`）所致，而非水箱失温。详尽记录了压缩机防频启压力平衡延时（约 3 分钟）以及随后 HPC 410 回水温差判定（$\ge 1.2\text{ K}$）触发空间采暖重启的完整物理过程。
  - **Android 15 沉浸式主题与界面重构：** 通过 `@style/Theme.BreastApp` 专属主题（`windowNoTitle=true`, `windowActionBar=false`）在 `styles.xml` 及代码中消除默认顶栏遮挡。配置挖孔屏防遮挡切口边距（`WindowInsetsCompat.Type.displayCutout()`）。精简主界面多余设置按钮，仅保留顶部导航栏单一标准 `⚙ 设置` 入口。实测遥测标定徽章行（`HorizontalScrollView`）强制单行居中（`Gravity.CENTER_VERTICAL`），彻底杜绝文字竖排与换行错位。
  - **单元测试与真机验证：** 编写覆盖电价配置序列化、合成电价时序生成的单元测试，全套 68 项单元测试全部通过，并在 Pixel 10 Pro 真机上完成验证。
