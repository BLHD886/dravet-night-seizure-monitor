param([string]$PythonPath='python',[switch]$Live,[switch]$Lan)
$ErrorActionPreference='Stop'
$serverArgs=@('-X','utf8','server.py')
if($Live){$serverArgs+='--enable-live'}
if($Lan){$serverArgs+=@('--host','0.0.0.0')}
Push-Location (Join-Path $PSScriptRoot 'prototype')
try { & $PythonPath @serverArgs } finally { Pop-Location }
