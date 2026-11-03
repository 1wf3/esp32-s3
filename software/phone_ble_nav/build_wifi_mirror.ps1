param([string]$Port='' ,[switch]$Upload)
$ErrorActionPreference='Stop'
$navCli='E:/WinGet/Packages/ArduinoSA.IDE.stable_Microsoft.Winget.Source_8wekyb3d8bbwe/resources/app/lib/backend/resources/arduino-cli.exe'
if(!(Test-Path -LiteralPath $navCli)){throw 'Locate the installed Arduino CLI and update navCli in this script.'}
$navBuild='E:/nav-build-wifi-mirror'
$navTarget='esp32:esp32:esp32s3:FlashSize=8M,PartitionScheme=default_8MB,PSRAM=opi,USBMode=hwcdc,CDCOnBoot=cdc'
& $navCli compile --fqbn $navTarget --build-path $navBuild $PSScriptRoot
if($LASTEXITCODE -ne 0){throw 'Firmware compile failed'}
if($Upload){
if(!$Port){$navPorts=@(Get-CimInstance Win32_PnPEntity | Where-Object {$_.DeviceID -like 'USB\VID_303A&PID_1001*' -and $_.Name -match '\(COM[0-9]+\)'} | ForEach-Object {if($_.Name -match '\((COM[0-9]+)\)'){$Matches[1]}});if($navPorts.Count -ne 1){throw 'Pass -Port with the connected ESP32 serial port.'};$Port=$navPorts[0]}
& $navCli upload --port $Port --fqbn $navTarget --input-dir $navBuild;if($LASTEXITCODE -ne 0){throw 'Firmware upload failed'}}
