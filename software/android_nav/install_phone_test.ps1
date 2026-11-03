param([string]$DeviceSerial='')
$ErrorActionPreference='Stop'
$navWorkspace=Split-Path (Split-Path $PSScriptRoot)
$navAdb=Join-Path $navWorkspace 'build\android_tools\sdk\platform-tools\adb.exe'
$navApk=Join-Path $PSScriptRoot 'output\nav-screen.apk'
if(!(Test-Path -LiteralPath $navAdb) -or !(Test-Path -LiteralPath $navApk)){throw 'Prepared ADB or test APK is missing.'}
$navDevices=@(& $navAdb devices | Select-Object -Skip 1 | Where-Object {$_ -match '^\S+\s+device\s*$'} | ForEach-Object {($_ -split '\s+')[0]})
if($DeviceSerial){
    if($DeviceSerial -notin $navDevices){throw 'Selected phone is not connected or has not authorized USB debugging.'}
}elseif($navDevices.Count -eq 1){$DeviceSerial=$navDevices[0]}else{throw 'Connect and authorize exactly one Android phone, or pass DeviceSerial explicitly.'}
function Invoke-NavAdb([string[]]$NavArguments){
    & $navAdb -s $DeviceSerial @NavArguments
    if($LASTEXITCODE -ne 0){throw 'ADB operation failed.'}
}
Invoke-NavAdb @('install','--no-incremental','-r',$navApk)
Invoke-NavAdb @('shell','am','start','-n','top.maler.community/.MainActivity')
Write-Output 'AMap direct app installed. Configure your Web Service key in the app; no USB gateway is needed.'
Write-Output 'Grant permissions on the phone, scan, and select MOTO GPS TFT.'
