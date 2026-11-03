$ErrorActionPreference='Stop'
$workspaceRoot=Split-Path (Split-Path $PSScriptRoot)
$template=Get-Content -LiteralPath "$PSScriptRoot\phone_debug.template.html" -Raw -Encoding utf8
$fixture=Get-Content -LiteralPath "$workspaceRoot\software\phone_gateway\shared\demo_fixture\jinan_big_data_to_inspur.json" -Raw -Encoding utf8
$html=$template.Replace('__DEMO_FIXTURE__',$fixture.Replace('<','\u003c'))
$previewDir=Join-Path $workspaceRoot 'output\previews'
New-Item -ItemType Directory -Force -Path $previewDir | Out-Null
$output=Join-Path $previewDir '手机导航实机调试.html'
[System.IO.File]::WriteAllText($output,$html,[System.Text.UTF8Encoding]::new($false))
Write-Output $output
