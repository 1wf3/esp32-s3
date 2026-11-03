$ErrorActionPreference='Stop'
$workspaceRoot=Split-Path (Split-Path (Split-Path $PSScriptRoot))
$jdkRoot=(Get-ChildItem "$workspaceRoot\build\android_tools\jdk" -Directory | Select-Object -First 1).FullName
$testDir=Join-Path $workspaceRoot 'build\android_direct_tests'
New-Item -ItemType Directory -Force -Path $testDir | Out-Null
$jsonJar=Join-Path $testDir 'json-20240303.jar'
if (!(Test-Path $jsonJar)) { throw 'JVM test dependency required: org.json:json:20240303 from Maven Central, in build/android_direct_tests.' }
$expected=Join-Path $testDir 'expected-routes.json'
Push-Location $workspaceRoot
try {
  node --input-type=module -e "import{readFileSync,writeFileSync}from'node:fs';import{transformAmapRouteOptionsV2}from'./software/phone_gateway/backend/src/amap-transformer.js';writeFileSync('build/android_direct_tests/expected-routes.json',JSON.stringify(transformAmapRouteOptionsV2(JSON.parse(readFileSync('software/phone_gateway/backend/fixtures/amap-route-v2.json')))));"
  if($LASTEXITCODE -ne 0){throw 'Reference transformer failed'}
} finally { Pop-Location }
$source=Join-Path $PSScriptRoot '..\src\top\maler\community'
$files=@('AmapDirect','Route','NavMath','Protocol') | ForEach-Object { Join-Path $source ($_.ToString()+'.java') }
& "$jdkRoot\bin\javac.exe" -encoding UTF-8 -cp $jsonJar -d $testDir @files "$PSScriptRoot\AmapDirectTests.java"
if($LASTEXITCODE -ne 0){throw 'Direct tests compilation failed'}
& "$jdkRoot\bin\java.exe" -cp "$testDir;$jsonJar" AmapDirectTests "$workspaceRoot\software\phone_gateway\backend\fixtures\amap-route-v2.json" $expected "$PSScriptRoot\amap-electrobike-public.json"
if($LASTEXITCODE -ne 0){throw 'Direct tests failed'}
