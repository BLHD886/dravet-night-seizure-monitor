param([string]$PythonPath='python')
$ErrorActionPreference='Stop'
Push-Location (Join-Path $PSScriptRoot 'prototype')
try {
    & $PythonPath '-m' 'pip' 'install' '--target' 'runtime/python' '-r' 'requirements-gateway.txt'
    if($LASTEXITCODE -ne 0){ throw '依赖安装失败，请检查 Python 与网络，不要继续声称硬件已接通。' }
    & $PythonPath '-m' 'unittest' '-v' 'test_core.py' 'test_live.py'
    if($LASTEXITCODE -ne 0){ throw '自动测试失败，请先修正。' }
} finally { Pop-Location }
