param()
$ErrorActionPreference='Stop'
$workspaceRoot=Split-Path (Split-Path $PSScriptRoot)
$taskPython=Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
if(!(Test-Path -LiteralPath $taskPython)){throw 'Python 3.12 runtime not found. Use the prepared Codex workspace runtime.'}
Set-Location -LiteralPath $workspaceRoot
& $taskPython (Join-Path $PSScriptRoot 'server.py')
