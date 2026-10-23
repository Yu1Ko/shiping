param([string]$Serial = "", [switch]$Stop)
$ErrorActionPreference = "Stop"
$mobileRoot = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $mobileRoot ".tools/sdk/platform-tools/adb.exe"
if (!(Test-Path -LiteralPath "$adb")) { $adb = (Get-Command adb -ErrorAction Stop).Source }
$deviceArgs = @()
if ($Serial) { $deviceArgs = @("-s", $Serial) }
function Invoke-Device([string[]]$DeviceCommand) {
    $result = & "$adb" @deviceArgs @DeviceCommand
    if ($LASTEXITCODE -ne 0) { throw "ADB operation failed: $($DeviceCommand[0])" }
    return $result
}
$apk = ((Invoke-Device @("shell", "pm", "path", "cn.shiping.app")) | Where-Object { $_ -match "/base.apk$" }) -replace "^package:", ""
if (!$apk -or $apk -notmatch '^/data/app/[A-Za-z0-9_=/+.~-]+/base\.apk$') { throw "Install the Shiping 1.1.2 APK first." }
$entry = "CLASSPATH='$apk' app_process / cn.shiping.app.MeetingAudioBridge"
if ($Stop) {
    Invoke-Device @("shell", "$entry stop")
    exit
}
# Stop the previous activation if present. A missing helper is expected on first activation.
& "$adb" @deviceArgs shell "$entry stop >/dev/null 2>&1; true" | Out-Null
$randomBytes = New-Object byte[] 32
$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $random.GetBytes($randomBytes) } finally { $random.Dispose() }
$token = ([BitConverter]::ToString($randomBytes)).Replace("-", "").ToLowerInvariant()
# The key is sent on stdin, never in process arguments or logs. Both copies are private.
$token | & "$adb" @deviceArgs shell "umask 077; cat > /data/local/tmp/shiping-meeting-audio-token"
if ($LASTEXITCODE -ne 0) { throw "Cannot provision helper credential" }
$token | & "$adb" @deviceArgs shell "run-as cn.shiping.app sh -c 'umask 077; mkdir -p files; cat > files/meeting-audio-token'"
if ($LASTEXITCODE -ne 0) { throw "Activation requires the installed Shiping debug APK" }
$token = $null
Invoke-Device @("shell", "CLASSPATH='$apk' nohup app_process / cn.shiping.app.MeetingAudioBridge > /data/local/tmp/shiping-meeting-audio.log 2>&1 < /dev/null &") | Out-Null
Start-Sleep -Seconds 2
try { Invoke-Device @("shell", "$entry ping") }
catch {
    & "$adb" @deviceArgs shell cat /data/local/tmp/shiping-meeting-audio.log
    throw "Meeting audio activation failed. This phone may not support the required audio policy."
}
Write-Output "Activated for 8 hours. Select Tencent Meeting (USB activation) in Shiping, then Detect audio. USB may now be disconnected. Reactivate after a phone reboot or APK upgrade."
