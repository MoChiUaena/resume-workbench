#Requires -Version 7.2
param([string]$JavaHome=$env:JAVA_HOME,[switch]$SkipBuild)
& (Join-Path $PSScriptRoot 'workbench.ps1') -Action start -JavaHome $JavaHome -Build:(-not $SkipBuild)
if($LASTEXITCODE){exit $LASTEXITCODE}
