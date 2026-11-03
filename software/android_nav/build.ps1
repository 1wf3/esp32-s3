param(
  [string]$JdkRoot = '',
  [string]$SdkRoot = '',
  [string]$WorkRoot = 'E:\nav-android-build'
)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$workspaceRoot = Split-Path (Split-Path $projectRoot)
if (!$JdkRoot) { $JdkRoot = (Get-ChildItem (Join-Path $workspaceRoot 'build\android_tools\jdk') -Directory | Select-Object -First 1).FullName }
if (!$SdkRoot) { $SdkRoot = Join-Path $workspaceRoot 'build\android_tools\sdk' }
$javaBin = Join-Path $JdkRoot 'bin'
$env:JAVA_HOME = $JdkRoot
$env:PATH = "$javaBin;$env:PATH"
$androidJar = Join-Path $SdkRoot 'android-36\android.jar'
$buildTools = Join-Path $SdkRoot 'android-15'
if (!(Test-Path $androidJar) -or !(Test-Path "$javaBin\javac.exe")) { throw 'Set JdkRoot and SdkRoot to the prepared JDK and Android SDK directories.' }
# ASCII temporary paths avoid native Windows toolchain Unicode-path failures.
# Every invocation gets a fresh child directory; no existing output is deleted.
$workDir = Join-Path $WorkRoot ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path "$workDir\classes","$workDir\dex",$WorkRoot | Out-Null
Copy-Item -LiteralPath "$projectRoot\src" -Destination $workDir -Recurse
Copy-Item -LiteralPath "$projectRoot\AndroidManifest.xml" -Destination $workDir
Copy-Item -LiteralPath $androidJar -Destination "$workDir\android.jar"
$androidJar=Join-Path $workDir 'android.jar'
function Invoke-BuildTool([string]$Program, [string[]]$Arguments) {
  & $Program @Arguments
  if ($LASTEXITCODE -ne 0) { throw "Build tool failed: $Program ($LASTEXITCODE)" }
}
$sources = Get-ChildItem "$workDir\src" -Recurse -Filter '*.java' | Select-Object -ExpandProperty FullName
Invoke-BuildTool "$javaBin\javac.exe" (@('-encoding','UTF-8','-source','8','-target','8','-classpath',$androidJar,'-d',"$workDir\classes") + $sources)
Invoke-BuildTool "$javaBin\jar.exe" @('--create','--file',"$workDir\classes.jar",'-C',"$workDir\classes",'.')
Invoke-BuildTool "$buildTools\d8.bat" @('--lib',$androidJar,'--min-api','26','--output',"$workDir\dex","$workDir\classes.jar")
Invoke-BuildTool "$buildTools\aapt2.exe" @('link','-I',$androidJar,'--manifest',"$workDir\AndroidManifest.xml",'--min-sdk-version','26','--target-sdk-version','35','-o',"$workDir\base.apk")
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkArchive = [System.IO.Compression.ZipFile]::Open("$workDir\base.apk",[System.IO.Compression.ZipArchiveMode]::Update)
try {
  foreach ($dex in Get-ChildItem "$workDir\dex" -Filter '*.dex') {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apkArchive,$dex.FullName,$dex.Name,[System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
  }
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apkArchive,"$workspaceRoot\software\phone_ble_nav\upstream\NOTICE",'assets/NOTICE') | Out-Null
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apkArchive,"$workspaceRoot\software\phone_ble_nav\upstream\LICENSE.md",'assets/LICENSE.md') | Out-Null
  foreach ($asset in Get-ChildItem "$projectRoot\assets" -Recurse -File) {
    $entry = 'assets/' + $asset.FullName.Substring((Join-Path $projectRoot 'assets').Length + 1).Replace('\','/')
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($apkArchive,$asset.FullName,$entry) | Out-Null
  }
} finally { $apkArchive.Dispose() }
Invoke-BuildTool "$buildTools\zipalign.exe" @('-f','4',"$workDir\base.apk","$workDir\aligned.apk")
$debugKey = Join-Path $WorkRoot 'nav-debug.keystore'
if (!(Test-Path $debugKey)) {
  Invoke-BuildTool "$javaBin\keytool.exe" @('-genkeypair','-keystore',$debugKey,'-alias','androiddebugkey','-storepass','android','-keypass','android','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=Android Debug')
}
New-Item -ItemType Directory -Force -Path "$projectRoot\output" | Out-Null
$outputApk = Join-Path $projectRoot 'output\nav-screen.apk'
Invoke-BuildTool "$buildTools\apksigner.bat" @('sign','--v4-signing-enabled','false','--ks',$debugKey,'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',$outputApk,"$workDir\aligned.apk")
Invoke-BuildTool "$buildTools\apksigner.bat" @('verify','--verbose',$outputApk)
Get-FileHash -LiteralPath $outputApk -Algorithm SHA256 | Format-List
