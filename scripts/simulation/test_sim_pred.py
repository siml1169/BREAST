import math

# Replicate ControlUnitSimulator logic from Kotlin
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

# Target daily thermal energy need
# In Kotlin: calculateDailyThermalEnergyNeedKwh
# Today outdoor temp was ~12.5°C average during the day, 10.5°C over 24h
# Let's see what dailyEnergyNeedKwh was passed!
dailyEnergyNeedKwh = 33.1 # or let's test different values

def predictModulationPct(outdoorTempC):
    raw = calibratedModAt10C + calibratedModSlopePerK * (10.0 - outdoorTempC)
    return max(20.0, min(100.0, raw))

def predictElectricalPowerKw(modulationPct, outdoorTempC):
    thermalKw = (modulationPct / 100.0) * nominalThermalKw
    flowTemp = comfortRoomTempC + heatingCurveSlope * (20.0 - outdoorTempC)
    deltaLift = max(4.0, flowTemp - outdoorTempC)
    cop = max(1.8, min(8.5, 1.0 / (0.00542 * deltaLift + 0.060)))
    compKw = thermalKw / cop
    return max(0.45, min(2.40, compKw + parasiticBaselinePowerKw)), cop, thermalKw

for tout in [10.0, 11.0, 12.0, 12.5, 13.0, 14.0]:
    mod = predictModulationPct(tout)
    pwr, cop, th = predictElectricalPowerKw(mod, tout)
    print(f"Tout={tout:4.1f}°C -> PredMod={mod:5.1f}% | ThKw={th:4.2f} kW | COP={cop:4.2f} | ElecPower={pwr:5.3f} kW")
