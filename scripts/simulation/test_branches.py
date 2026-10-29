# Test whether 4.6 kWh @ 37% mod came from branch 1 (ControlUnitSimulator) or branch 2 (Carnot)

# In branch 2:
dailyEnergyNeedKwh = 33.1 # or let's test what dailyEnergyNeedKwh is
durH = 7.75 # 08:45 to 16:30 is 7.75 hours (31 slots of 15 min)
heatPumpMaxThermalKw = 9.0 # or nominal 4.5 or 9.0?
# If targetThermalKw = dailyEnergyNeedKwh / durH:
# targetThermalKw = 33.1 / 7.75 = 4.27 kW
# physicalModPct = 4.27 / 9.0 * 100 = 47.4%
# What if heatPumpMaxThermalKw is 11.5 or something?
# What if targetThermalKw was 3.3 kW? 3.3 / 9.0 = 36.7% mod! (~37% mod!)
# And at 3.3 kW thermal / COP 6.5 = 0.51 kW electric * 7.75h = 3.95 kWh + pump = 4.2 - 4.6 kWh!

print("Testing branch 2 with targetThermalKw:")
for targetTh in [2.5, 3.0, 3.3, 3.5, 4.0]:
    modPct = (targetTh / 9.0) * 100.0
    print(f"targetTh={targetTh} kW -> modPct={modPct:.1f}%")
