$ErrorActionPreference='Stop'
$workspaceRoot=Split-Path (Split-Path (Split-Path $PSScriptRoot))
$jdk=(Get-ChildItem "$workspaceRoot\build\android_tools\jdk" -Directory | Select-Object -First 1).FullName
$testDir=Join-Path $workspaceRoot 'build\android_direct_tests';$jsonJar=Join-Path $testDir 'json-20240303.jar'
$source=Join-Path $PSScriptRoot '..\src\top\maler\community';$out=Join-Path $workspaceRoot 'build\screen-map-parity'
New-Item -ItemType Directory -Force $out | Out-Null
& "$jdk\bin\javac.exe" -encoding UTF-8 -cp $jsonJar -d $testDir "$source\Protocol.java" "$source\NavMath.java" "$source\RoadMap.java" "$source\MapCoverage.java" "$source\ScreenFrame.java" "$PSScriptRoot\ScreenSceneTests.java"
if($LASTEXITCODE -ne 0){throw 'Screen scene compilation failed'}
& "$jdk\bin\java.exe" -cp "$testDir;$jsonJar" ScreenSceneTests $out
if($LASTEXITCODE -ne 0){throw 'Screen scene tests failed'}
$renderer=Join-Path $workspaceRoot 'software\phone_ble_nav\tests\renderer_preview'
$native=Join-Path $workspaceRoot 'software\phone_ble_nav\src\ble_protocol.cpp'
$batch=Join-Path $out 'build-renderer.cmd'
$commands=@('@echo off','chcp 65001 >nul','call "E:\Program Files\Microsoft Visual Studio\2022\Community\Common7\Tools\VsDevCmd.bat" -arch=x64 -host_arch=x64','if errorlevel 1 exit /b 1',"cl /nologo /std:c++17 /EHsc /utf-8 /W4 /c /I`"$renderer`" `"$renderer\render.cpp`" /Fo:`"$out\render.obj`"",'if errorlevel 1 exit /b 1',"cl /nologo /std:c++17 /EHsc /utf-8 /W4 /c `"$native`" /Fo:`"$out\ble_protocol.obj`"",'if errorlevel 1 exit /b 1',"link /nologo /OUT:`"$out\render.exe`" `"$out\render.obj`" `"$out\ble_protocol.obj`"",'exit /b %errorlevel%')
$commands | Set-Content -LiteralPath $batch -Encoding utf8NoBOM
& cmd.exe /c $batch
if($LASTEXITCODE -ne 0){throw 'Production renderer host build failed'}
foreach($angle in @(0,45,90,180,270,359)){
  & "$out\render.exe" "$out\scene-$angle.txt" fresh '01010100ff074d639fe700f8' | Set-Content "$out\scene-$angle.svg" -Encoding utf8
  if($LASTEXITCODE -ne 0){throw 'Production renderer failed to decode scene'}
}
node "$PSScriptRoot\screen-map-parity.cjs" $out
if($LASTEXITCODE -ne 0){throw 'Phone / TFT geometry parity failed'}
