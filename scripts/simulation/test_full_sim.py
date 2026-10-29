import math

# Full simulateSchedule simulation
calibratedModAt10C = 46.194286
calibratedModSlopePerK = 6.1291757
nominalThermalKw = 9.0
buildingLossKwPerK = 0.18
comfortRoomTempC = 22.0
setbackRoomTempC = 17.0
screedThermalMassKwhPerK = 5.0
cutInHysteresisK = 1.2
cutOutHysteresisK = 0.5
heatingCurveSlope = 0.65
parasiticBaselinePowerKw = 0.050
standbyPowerKw = 0.03
parallelShiftK = 0.0

# Weather for today (24 hours) - let's use actual outdoor temps
hourlyTemps = [10.5, 10.3, 10.0, 9.8, 9.6, 9.5, 9.6, 10.0, 10.9, 11.5, 12.0, 12.5, 13.0, 13.5, 13.8, 13.5, 12.8, 12.0, 11.5, 11.0, 10.8, 10.5, 10.4, 10.2]
dailyEnergyNeedKwh = 33.1

def estimateTargetReturnTempC(outdoorTempC, targetRoomTempC):
    flowTemp = targetRoomTempC + heatingCurveSlope * (20.0 - outdoorTempC) + parallelShiftK
    return max(16.0, min(45.0, flowTemp - 4.5))

def predictModulationPct(outdoorTempC):
    raw = calibratedModAt10C + calibratedModSlopePerK * (10.0 - outdoorTempC)
    return max(20.0, min(100.0, raw))

def predictElectricalPowerKw(modulationPct, outdoorTempC):
    thermalKw = (modulationPct / 100.0) * nominalThermalKw
    flowTemp = comfortRoomTempC + heatingCurveSlope * (20.0 - outdoorTempC) + parallelShiftK
    deltaLift = max(4.0, flowTemp - outdoorTempC)
    cop = max(1.8, min(8.5, 1.0 / (0.00542 * deltaLift + 0.060)))
    compKw = thermalKw / cop
    return max(0.45, min(2.40, compKw + parasiticBaselinePowerKw))

# Comfort window: 08:45 to 16:30 (525 min to 990 min)
comfortStartMinute = 525
comfortEndMinute = 990
rasterMinutes = 15
slotsPerDay = 1440 // rasterMinutes
rasterH = rasterMinutes / 60.0

targetComfortReturn = estimateTargetReturnTempC(hourlyTemps[0], comfortRoomTempC)
currentReturnT = targetComfortReturn - (cutInHysteresisK * 0.4)
compressorActive = False
accumulatedThermalKwh = 0.0
windowElectricityKwh = 0.0
totalElectricityKwh = 0.0
activeMinutes = 0
activePowerSum = 0.0
activeModSum = 0.0

for i in range(slotsPerDay):
    startMin = i * rasterMinutes
    endMin = startMin + rasterMinutes
    hourOfDay = min(23, startMin // 60)
    outdoorT = hourlyTemps[hourOfDay]

    isComfort = (startMin >= comfortStartMinute and endMin <= comfortEndMinute)
    currentTargetRoom = comfortRoomTempC if isComfort else setbackRoomTempC
    targetReturnT = estimateTargetReturnTempC(outdoorT, currentTargetRoom)

    needMoreHeat = accumulatedThermalKwh < dailyEnergyNeedKwh
    thermalSatisfied = accumulatedThermalKwh >= dailyEnergyNeedKwh

    if not compressorActive:
        if isComfort and currentReturnT <= (targetReturnT - cutInHysteresisK) and needMoreHeat:
            compressorActive = True
    else:
        if not isComfort and currentReturnT >= targetReturnT:
            compressorActive = False
        elif isComfort and (currentReturnT >= (targetReturnT + cutOutHysteresisK) or thermalSatisfied):
            compressorActive = False

    if compressorActive:
        modPct = predictModulationPct(outdoorT)
        thermalKw = (modPct / 100.0) * nominalThermalKw
        elecPowerKw = predictElectricalPowerKw(modPct, outdoorT)
        activeMinutes += rasterMinutes
        activePowerSum += elecPowerKw
        activeModSum += modPct
        accumulatedThermalKwh += thermalKw * rasterH
    else:
        modPct = 0.0
        thermalKw = 0.0
        elecPowerKw = parasiticBaselinePowerKw if isComfort else standbyPowerKw

    slotKwh = elecPowerKw * rasterH
    totalElectricityKwh += slotKwh
    if isComfort:
        windowElectricityKwh += slotKwh

    buildingLossKw = buildingLossKwPerK * max(0.0, currentTargetRoom - outdoorT)
    netThermalKw = thermalKw - buildingLossKw
    deltaReturnT = (netThermalKw / max(0.5, screedThermalMassKwhPerK)) * rasterH
    currentReturnT += deltaReturnT

print(f"Window Electricity kWh: {windowElectricityKwh:.2f} kWh")
print(f"Total Electricity kWh: {totalElectricityKwh:.2f} kWh")
print(f"Active minutes: {activeMinutes} min ({activeMinutes/60:.2f} h)")
print(f"Accumulated Thermal: {accumulatedThermalKwh:.2f} kWh_th (target was {dailyEnergyNeedKwh} kWh_th)")
if activeMinutes > 0:
    print(f"Average Active Mod: {activeModSum / (activeMinutes / rasterMinutes):.1f}%")
    print(f"Average Active Power: {activePowerSum / (activeMinutes / rasterMinutes):.3f} kW")
