param([switch]$Live)
$ErrorActionPreference='Stop'
$workspaceRoot=Split-Path (Split-Path (Split-Path $PSScriptRoot))
$jdkRoot=(Get-ChildItem "$workspaceRoot\build\android_tools\jdk" -Directory | Select-Object -First 1).FullName
$testDir=Join-Path $workspaceRoot 'build\android_direct_tests'
$jsonJar=Join-Path $testDir 'json-20240303.jar'
$source=Join-Path $PSScriptRoot '..\src\top\maler\community'
$files=@('MapCoverage','ScreenFrame','VectorTile','DirectMap','MapTiles','RoadMap','Gateway','AmapDirect','Appearance','Route','NavMath','Protocol') | ForEach-Object {Join-Path $source ($_.ToString()+'.java')}
& "$jdkRoot\bin\javac.exe" -encoding UTF-8 -cp $jsonJar -d $testDir @files "$PSScriptRoot\MapAppearanceTests.java"
if($LASTEXITCODE -ne 0){throw 'Map / appearance test compilation failed'}
$testArgs=@("$PSScriptRoot\fixtures\shortbread-public.mvt","$PSScriptRoot\fixtures\shortbread-public-meta.json","$PSScriptRoot\fixtures\shortbread-expected.json");if($Live){$testArgs+=@('live')}
& "$jdkRoot\bin\java.exe" '-Djava.net.useSystemProxies=true' -cp "$testDir;$jsonJar" MapAppearanceTests @testArgs
if($LASTEXITCODE -ne 0){throw 'Map / appearance tests failed'}
