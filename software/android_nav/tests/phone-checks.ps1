param([string]$Mode='probe',[string]$ExpectedColor='',[string]$RestoreColor='')
$ErrorActionPreference='Stop'
$workspaceRoot=Split-Path (Split-Path (Split-Path $PSScriptRoot))
$jdkRoot=(Get-ChildItem "$workspaceRoot\build\android_tools\jdk" -Directory | Select-Object -First 1).FullName
$sdk=Join-Path $workspaceRoot 'build\android_tools\sdk';$bin=Join-Path $jdkRoot 'bin';$bt=Join-Path $sdk 'android-15'
$env:JAVA_HOME=$jdkRoot;$env:PATH="$bin;$env:PATH"
$work=Join-Path 'E:\nav-android-build' ('phone-checks-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force "$work\classes","$work\dex" | Out-Null
Copy-Item "$sdk\android-35\android.jar" "$work\android.jar"
Copy-Item "$PSScriptRoot\PhoneChecks.java" "$work\PhoneChecks.java"
'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="top.maler.community.checks"><uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35"/><uses-permission android:name="android.permission.INTERNET"/><application android:label="Navigation checks"/><instrumentation android:name=".PhoneChecks" android:targetPackage="top.maler.community"/></manifest>' | Set-Content "$work\AndroidManifest.xml" -Encoding utf8
function RunTool([string]$Program,[string[]]$Arguments){& $Program @Arguments;if($LASTEXITCODE -ne 0){throw "Phone check tool failed: $Program"}}
RunTool "$bin\javac.exe" @('-encoding','UTF-8','-source','8','-target','8','-cp',"$work\android.jar",'-d',"$work\classes","$work\PhoneChecks.java")
RunTool "$bin\jar.exe" @('--create','--file',"$work\classes.jar",'-C',"$work\classes",'.')
RunTool "$bt\d8.bat" @('--lib',"$work\android.jar",'--min-api','26','--output',"$work\dex","$work\classes.jar")
RunTool "$bt\aapt2.exe" @('link','-I',"$work\android.jar",'--manifest',"$work\AndroidManifest.xml",'-o',"$work\base.apk")
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[System.IO.Compression.ZipFile]::Open("$work\base.apk",[System.IO.Compression.ZipArchiveMode]::Update)
try{[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$work\dex\classes.dex",'classes.dex') | Out-Null}finally{$zip.Dispose()}
RunTool "$bt\zipalign.exe" @('-f','4',"$work\base.apk","$work\aligned.apk")
RunTool "$bt\apksigner.bat" @('sign','--ks','E:\nav-android-build\nav-debug.keystore','--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',"$work\checks.apk","$work\aligned.apk")
RunTool "$sdk\platform-tools\adb.exe" @('install','--no-incremental','-r',"$work\checks.apk")
$instrumentArgs=@('shell','am','instrument','-w','-e','mode',$Mode)
if($ExpectedColor){if($ExpectedColor -notmatch '^#?[0-9a-fA-F]{6}$'){throw 'ExpectedColor must be a six-digit hex color'};$instrumentArgs+=@('-e','expectedColor',$ExpectedColor.TrimStart('#'))}
if($RestoreColor){if($RestoreColor -notmatch '^#?[0-9a-fA-F]{6}$'){throw 'Invalid restore color'};$instrumentArgs+=@('-e','restoreColor',$RestoreColor.TrimStart('#'))}
$instrumentArgs+=@('top.maler.community.checks/.PhoneChecks')
RunTool "$sdk\platform-tools\adb.exe" $instrumentArgs
