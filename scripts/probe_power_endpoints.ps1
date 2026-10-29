$ErrorActionPreference = "Continue"
$adb = "C:\Users\limuy\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# 1. Refresh or pull token
Write-Host "=== 1. Checking SingleKey ID Tokens ==="
$tokensFile = "C:\Users\limuy\.gemini\antigravity\brain\7f48fff9-69d9-4015-a010-b792103afabf\scratch\latest_tokens.json"
$tokens = if (Test-Path $tokensFile) { Get-Content -Raw $tokensFile | ConvertFrom-Json } else { $null }

# Try pulling fresh tokens from phone if adb is connected
if (Test-Path $adb) {
    try {
        $xmlRaw = & $adb shell "run-as de.breast cat /data/data/de.breast/shared_prefs/breast_auth_prefs.xml" 2>$null
        if ($xmlRaw -and $xmlRaw.Contains("oauth_tokens_json")) {
            [xml]$xml = $xmlRaw
            $entry = $xml.map.string | Where-Object { $_.name -eq "oauth_tokens_json" }
            if ($entry -and $entry.'#text') {
                $phoneTokens = $entry.'#text' | ConvertFrom-Json
                if ($phoneTokens.refresh_token) {
                    $tokens = $phoneTokens
                    Write-Host "Loaded latest tokens from phone de.breast prefs!"
                }
            }
        }
    } catch {
        Write-Host "adb pull skipped: $($_.Exception.Message)"
    }
}

# Refresh token via SingleKey ID
$clientId = "762162C0-FA2D-4540-AE66-6489F189FADC"
if ($tokens.refresh_token) {
    try {
        $body = @{
            grant_type = "refresh_token"
            client_id = $clientId
            refresh_token = $tokens.refresh_token
        }
        $refreshRes = Invoke-RestMethod -Uri "https://singlekey-id.com/auth/connect/token" -Method Post -Body $body -ContentType "application/x-www-form-urlencoded"
        $tokens = @{
            access_token = $refreshRes.access_token
            refresh_token = $refreshRes.refresh_token
            token_type = $refreshRes.token_type
            expires_in = $refreshRes.expires_in
        }
        $tokens | ConvertTo-Json | Set-Content $tokensFile
        Write-Host "SingleKey ID token refreshed successfully!"
    } catch {
        Write-Host "SingleKey refresh error: $($_.Exception.Message)"
    }
}

$token = $tokens.access_token
$gwId = "101845229"
$baseUrl = "https://pointt-api.bosch-thermotechnology.com/pointt-api/api/v1/gateways/$gwId/resource"
$headers = @{
    "Authorization" = "Bearer $token"
    "Accept" = "application/json"
}

# 2. Probe PointT Cloud API endpoints
Write-Host "`n=== 2. Probing PointT Cloud API Telemetry on Gateway $gwId ==="
$candidatePaths = @(
    "/heatSources",
    "/heatSources/actualPower",
    "/heatSources/actualCHPower",
    "/heatSources/actualDHWPower",
    "/heatSources/actualModulation",
    "/heatSources/nominalPower",
    "/heatSources/numberOfStarts",
    "/heatSources/workingTime",
    "/heatSources/energyMonitoring",
    "/heatSources/energyMonitoring/consumption",
    "/heatSources/energyMonitoring/electricalEnergy",
    "/heatSources/energyMonitoring/heatEnergy",
    "/heatSources/electricHeater",
    "/heatSources/electricHeater/actualPower",
    "/heatSources/elHeater/actualPower",
    "/heatSources/hs1",
    "/heatSources/hs1/actualPower",
    "/heatSources/hs1/actualModulation",
    "/heatSources/hs1/energyMonitoring",
    "/system/heatSources",
    "/system/appliance",
    "/system/appliance/actualPower",
    "/system/energy",
    "/system/sensors",
    "/recordings",
    "/recordings/heatSources",
    "/recordings/heatSources/actualPower",
    "/recordings/heatSources/actualCHPower",
    "/recordings/heatSources/actualDHWPower",
    "/recordings/heatSources/energyMonitoring",
    "/recordings/energy",
    "/energy"
)

$foundEndpoints = @()

foreach ($path in $candidatePaths) {
    try {
        $res = Invoke-RestMethod -Uri "$baseUrl$path" -Headers $headers -Method Get -TimeoutSec 10
        $val = if ($res.value -ne $null) { $res.value } else { "(no direct value)" }
        $unit = if ($res.unitOfMeasure) { $res.unitOfMeasure } else { "" }
        $type = if ($res.type) { $res.type } else { "" }
        $refCount = if ($res.references) { "$($res.references.Count) refs" } else { "" }
        
        Write-Host "FOUND $path => value='$val' $unit ($type $refCount)" -ForegroundColor Green
        $foundEndpoints += [PSCustomObject]@{
            Path = $path
            Value = $val
            Unit = $unit
            Type = $type
            References = if ($res.references) { ($res.references | ForEach-Object { $_.id }) -join ", " } else { "" }
            FullJson = ($res | ConvertTo-Json -Depth 5 -Compress)
        }

        # If there are sub-references under heatSources or recordings, check them too!
        if ($res.references) {
            foreach ($sub in $res.references) {
                if ($candidatePaths -notcontains $sub.id) {
                    try {
                        $subRes = Invoke-RestMethod -Uri "$baseUrl$($sub.id)" -Headers $headers -Method Get -TimeoutSec 10
                        $subVal = if ($subRes.value -ne $null) { $subRes.value } else { "(no direct value)" }
                        $subUnit = if ($subRes.unitOfMeasure) { $subRes.unitOfMeasure } else { "" }
                        Write-Host "   -> SUB-REF $($sub.id) => value='$subVal' $subUnit ($($subRes.type))" -ForegroundColor Cyan
                        $foundEndpoints += [PSCustomObject]@{
                            Path = $sub.id
                            Value = $subVal
                            Unit = $subUnit
                            Type = $subRes.type
                            FullJson = ($subRes | ConvertTo-Json -Depth 5 -Compress)
                        }
                    } catch {}
                }
            }
        }
    } catch {
        $resp = $_.Exception.Response
        $code = if ($resp) { [int]$resp.StatusCode } else { "ERR" }
        Write-Host "NOT FOUND $path (HTTP $code)" -ForegroundColor DarkGray
    }
}

# 3. Check for local K30 gateway on the LAN (192.168.178.x)
Write-Host "`n=== 3. Scanning LAN for Local Gateway Interface ==="
if (Test-Path $adb) {
    try {
        $phoneIp = & $adb shell "ip -f inet addr show wlan0" 2>$null
        Write-Host "Phone Wi-Fi IP: $phoneIp"
        $phoneArp = & $adb shell "ip neigh show" 2>$null
        Write-Host "Phone ARP Neighbors:"
        $phoneArp | ForEach-Object { Write-Host "   $_" }
    } catch {}
}

# Check local ARP table on Windows
try {
    $localArp = Get-NetNeighbor -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -like "192.168.178.*" -and $_.State -ne "Unreachable" }
    if ($localArp) {
        Write-Host "Windows Local Neighbors on 192.168.178.x:"
        $localArp | Select-Object IPAddress, LinkLayerAddress, State | Format-Table | Out-String | Write-Host
    }
} catch {}

# Save full results to scratch JSON
$dataDir = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\data\pointt'))
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
$resultsFile = Join-Path $dataDir 'heat_sources_power_probe_results.json'
$foundEndpoints | ConvertTo-Json -Depth 6 | Set-Content $resultsFile
Write-Host "`nProbe complete! Results saved to $resultsFile"
