param([switch]$Live)
$ErrorActionPreference='Stop'
$workspaceRoot=Split-Path (Split-Path (Split-Path $PSScriptRoot))
$jdk=(Get-ChildItem "$workspaceRoot\build\android_tools\jdk" -Directory | Select-Object -First 1).FullName
$testDir=Join-Path $workspaceRoot 'build\android_direct_tests'
$jsonJar=Join-Path $testDir 'json-20240303.jar'
$source=Join-Path $PSScriptRoot '..\src\top\maler\community'
$files=@('MapCoverage','ScreenFrame','VectorTile','DirectMap','MapTiles','RoadMap','Gateway','AmapDirect','Route','NavMath','Protocol') | ForEach-Object {Join-Path $source ($_.ToString()+'.java')}
& "$jdk\bin\javac.exe" -encoding UTF-8 -cp $jsonJar -d $testDir @files "$PSScriptRoot\MapUpdateTests.java" "$PSScriptRoot\MapUpdateLiveCheck.java"
if($LASTEXITCODE -ne 0){throw 'Map update tests compilation failed'}
& "$jdk\bin\java.exe" -cp "$testDir;$jsonJar" MapUpdateTests "$PSScriptRoot\fixtures\shortbread-public.mvt"
if($LASTEXITCODE -ne 0){throw 'Map update tests failed'}
node "$PSScriptRoot\map-viewport-checks.cjs"
if($LASTEXITCODE -ne 0){throw 'Viewport bridge tests failed'}
if($Live){
 & "$jdk\bin\java.exe" '-Djava.net.useSystemProxies=true' -cp "$testDir;$jsonJar" MapUpdateLiveCheck
 if($LASTEXITCODE -ne 0){throw 'Public base-map live check failed'}
}
