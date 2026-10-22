param(
    [ValidateSet("CheckApp", "WatchRedirect", "TriggerAuthUrl")]
    [string]$Action = "WatchRedirect",
    [string]$AuthUrl = ""
)

$PackageName = "com.bosch.tt.dashtt.pointt"

switch ($Action) {
    "CheckApp" {
        Write-Host "Checking connected Android devices and installed HomeCom Easy ($PackageName) package..."
        adb devices -l
        adb shell pm path $PackageName
        adb shell dumpsys package $PackageName | Select-String -Pattern "com.bosch.tt.dashtt.pointt|https|singlekey|pointt-api" -Context 1,2
    }

    "WatchRedirect" {
        Write-Host "Watching adb logcat for SingleKey ID OAuth callback (com.bosch.tt.dashtt.pointt://app/login?code=...)..."
        Write-Host "Press Ctrl+C to stop once the redirect URL appears."
        adb logcat -c
        adb logcat | Select-String -Pattern "com\.bosch\.tt\.dashtt\.pointt://app/login|singlekey-id\.com|pointt-api"
    }

    "TriggerAuthUrl" {
        if ([string]::IsNullOrWhiteSpace($AuthUrl)) {
            Write-Error "Provide -AuthUrl '<url_from_auth-url_command>'"
            exit 1
        }
        Write-Host "Opening SingleKey ID authorization URL on connected Android device..."
        adb shell am start -a android.intent.action.VIEW -d "$AuthUrl"
    }
}
