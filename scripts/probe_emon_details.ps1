$ErrorActionPreference = "Continue"
$tokensFile = "C:\Users\limuy\.gemini\antigravity\brain\7f48fff9-69d9-4015-a010-b792103afabf\scratch\latest_tokens.json"
$tokens = Get-Content -Raw $tokensFile | ConvertFrom-Json
$token = $tokens.access_token

$gwId = "101845229"
$baseUrl = "https://pointt-api.bosch-thermotechnology.com/pointt-api/api/v1/gateways/$gwId/resource"
$headers = @{
    "Authorization" = "Bearer $token"
    "Accept" = "application/json"
}

$paths = @(
    "/heatSources/actualModulation",
    "/heatSources/electricityConsumption",
    "/heatSources/emon",
    "/heatSources/nominalCHPower",
    "/heatSources/compressor",
    "/heatSources/eHeater",
    "/heatSources/additionalHeater",
    "/heatSources/outdoorType",
    "/heatSources/indoorType",
    "/heatSources/pumpCirculationSpeed",
    "/heatSources/sensors",
    "/system/sensors/temperatures/outdoor_t1",
    "/recordings/heatSources/emon/ch",
    "/recordings/heatSources/emon/dhw",
    "/recordings/heatSources/emon/total",
    "/recordings/heatSources/actualSupplyTemperature",
    "/recordings/heatingCircuits/hc1",
    "/recordings/dhwCircuits/dhw1"
)

$results = @()
foreach ($p in $paths) {
    try {
        $res = Invoke-RestMethod -Uri "$baseUrl$p" -Headers $headers -Method Get -TimeoutSec 10
        Write-Host "SUCCESS ${p}:" -ForegroundColor Green
        $res | ConvertTo-Json -Depth 6 | Write-Host
        $results += [PSCustomObject]@{
            Path = $p
            Ok = $true
            Data = $res
        }
        if ($res.references) {
            foreach ($sub in $res.references) {
                try {
                    $subRes = Invoke-RestMethod -Uri "$baseUrl$($sub.id)" -Headers $headers -Method Get -TimeoutSec 10
                    Write-Host "   -> SUB-REF $($sub.id):" -ForegroundColor Cyan
                    $subRes | ConvertTo-Json -Depth 6 | Write-Host
                    $results += [PSCustomObject]@{
                        Path = $sub.id
                        Ok = $true
                        Data = $subRes
                    }
                } catch {
                    Write-Host "   -> SUB-REF $($sub.id) failed: $($_.Exception.Message)" -ForegroundColor Red
                }
            }
        }
    } catch {
        $resp = $_.Exception.Response
        $code = if ($resp) { [int]$resp.StatusCode } else { "ERR" }
        Write-Host "FAILED $p (HTTP $code)" -ForegroundColor DarkGray
        $results += [PSCustomObject]@{
            Path = $p
            Ok = $false
            HttpCode = $code
        }
    }
}

$dataDir = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\data\pointt'))
New-Item -ItemType Directory -Force -Path $dataDir | Out-Null
$results | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $dataDir 'emon_details_results.json')
