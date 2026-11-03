$ErrorActionPreference='Stop'
$workspaceRoot=Split-Path (Split-Path (Split-Path $PSScriptRoot))
$jdkRoot=(Get-ChildItem "$workspaceRoot\build\android_tools\jdk" -Directory | Select-Object -First 1).FullName
$testDir=Join-Path $workspaceRoot 'build\android_java_tests'
New-Item -ItemType Directory -Force -Path $testDir | Out-Null
& "$jdkRoot\bin\javac.exe" -encoding UTF-8 -d $testDir "$PSScriptRoot\..\src\top\maler\community\Protocol.java" "$PSScriptRoot\..\src\top\maler\community\NavMath.java" "$PSScriptRoot\..\src\top\maler\community\RoadMap.java" "$PSScriptRoot\..\src\top\maler\community\MapCoverage.java" "$PSScriptRoot\..\src\top\maler\community\VehicleTelemetry.java" "$PSScriptRoot\ProtocolTests.java"
if($LASTEXITCODE -ne 0){throw 'Java test compilation failed'}
& "$jdkRoot\bin\java.exe" -cp $testDir ProtocolTests "$workspaceRoot\software\phone_ble_nav\tests\ble-navigation-v1.golden.txt"
if($LASTEXITCODE -ne 0){throw 'Java tests failed'}
