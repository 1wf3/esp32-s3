param([string]$DeviceSerial='')
$ErrorActionPreference='Stop'
$gatewayWorkspace=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$gatewayBackend=Join-Path $PSScriptRoot 'backend'
$gatewayAdb=Join-Path $gatewayWorkspace 'build\android_tools\sdk\platform-tools\adb.exe'
$gatewayLogDirectory=Join-Path $gatewayWorkspace 'output\gateway_logs'
function Get-GatewayHealth {
    try { return Invoke-RestMethod -Uri 'http://127.0.0.1:8787/healthz' -TimeoutSec 2 }
    catch { return $null }
}
$gatewayHealth=Get-GatewayHealth
if(-not $gatewayHealth){
    if(Get-NetTCPConnection -State Listen -LocalPort 8787 -ErrorAction SilentlyContinue){throw 'Port 8787 is occupied by a service that did not pass the gateway health check.'}
    if(!(Test-Path -LiteralPath (Join-Path $gatewayBackend '.env'))){throw 'Gateway .env is missing. Restore the existing local configuration; do not share its API keys.'}
    $gatewayNode=(Get-Command node -ErrorAction Stop).Source
    New-Item -ItemType Directory -Path $gatewayLogDirectory -Force | Out-Null
    $gatewayStamp=[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmssZ')
    $gatewayStdout=Join-Path $gatewayLogDirectory ($gatewayStamp+'-stdout.log')
    $gatewayStderr=Join-Path $gatewayLogDirectory ($gatewayStamp+'-stderr.log')
    $gatewayStarted=Start-Process -FilePath $gatewayNode -ArgumentList '--env-file=.env','src/server.js' -WorkingDirectory $gatewayBackend -WindowStyle Hidden -RedirectStandardOutput $gatewayStdout -RedirectStandardError $gatewayStderr -PassThru
    for($gatewayAttempt=0;$gatewayAttempt -lt 15;$gatewayAttempt++){
        Start-Sleep -Milliseconds 500
        $gatewayHealth=Get-GatewayHealth
        if($gatewayHealth){break}
        if($gatewayStarted.HasExited){throw ('Gateway stopped during startup. See local log: '+$gatewayStderr)}
    }
    if(-not $gatewayHealth){throw ('Gateway did not become ready. See local log: '+$gatewayStderr)}
}
if($gatewayHealth.status -ne 'ok' -or $gatewayHealth.provider -ne 'amap' -or -not $gatewayHealth.ready_for_live_navigation){throw 'Gateway is reachable but not configured for live AMap navigation. Existing configuration was not changed.'}
Write-Output 'PASS: local gateway is ready for live navigation.'
if(!(Test-Path -LiteralPath $gatewayAdb)){throw 'Project ADB executable is missing.'}
$gatewayConnected=@(& $gatewayAdb devices | Select-Object -Skip 1 | Where-Object {$_ -match '^\S+\s+device\s*$'} | ForEach-Object {($_ -split '\s+')[0]})
if($LASTEXITCODE -ne 0){throw 'Unable to read Android devices.'}
if($DeviceSerial){
    if($DeviceSerial -notin $gatewayConnected){throw 'Selected phone is not connected or USB debugging has not been authorized.'}
}elseif($gatewayConnected.Count -eq 1){$DeviceSerial=$gatewayConnected[0]}
else{throw 'Keep exactly one Android phone connected by a data cable and authorize USB debugging, then run again.'}
& $gatewayAdb -s $DeviceSerial reverse tcp:8787 tcp:8787
if($LASTEXITCODE -ne 0){throw 'USB forwarding failed; check the phone data cable.'}
& $gatewayAdb -s $DeviceSerial reverse --list
if($LASTEXITCODE -ne 0){throw 'Unable to verify USB forwarding.'}
Write-Output 'PASS: phone-to-computer USB forwarding is configured.'
& $gatewayAdb -s $DeviceSerial shell am start -n top.maler.community/.MainActivity
if($LASTEXITCODE -ne 0){throw 'Unable to open the existing phone app. No app was installed or replaced.'}
Write-Output 'On the phone, keep http://127.0.0.1:8787/ and tap Save/Test Gateway.'
Write-Output 'Keep the phone data cable connected while using this local debugging gateway.'
