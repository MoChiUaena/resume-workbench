param([Parameter(Mandatory)][string]$JavaHome)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '../RuntimeControl.psm1') -Force
$testRoot=Join-Path ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../output'))) ('runtime-write-failure-'+[guid]::NewGuid().ToString('N'))
$testClasses=Join-Path $testRoot 'classes';$testTarget=Join-Path $testRoot 'target'
New-Item -ItemType Directory -Path $testClasses,$testTarget -Force | Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -d $testClasses (Join-Path $PSScriptRoot 'RuntimeFixture.java')
if($LASTEXITCODE-ne 0){throw 'Fixture compilation failed'}
& (Join-Path $JavaHome 'bin/jar.exe') --create --file (Join-Path $testTarget 'resume-workbench.jar') --main-class RuntimeFixture -C $testClasses .
if($LASTEXITCODE-ne 0){throw 'Fixture packaging failed'}
$testPortListener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$testPortListener.Start();$testPort=$testPortListener.LocalEndpoint.Port;$testPortListener.Stop()
$global:rwFailureJar=Join-Path $testTarget 'resume-workbench.jar'
$global:rwFailureState=Join-Path $testRoot '.tools/runtime/state.json'
$global:rwFailureLock=$null
# Delegate the real hash operation, but force a genuine state replacement failure after spawn.
function global:Get-FileHash {
    param([string]$LiteralPath,[string]$Algorithm)
    $result=Microsoft.PowerShell.Utility\Get-FileHash -LiteralPath $LiteralPath -Algorithm $Algorithm
    if($LiteralPath-eq$global:rwFailureJar-and!$global:rwFailureLock){
        $global:rwFailureLock=[IO.FileStream]::new($global:rwFailureState,[IO.FileMode]::Create,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
        $bytes=[Text.Encoding]::UTF8.GetBytes('{}');$global:rwFailureLock.Write($bytes);$global:rwFailureLock.Flush()
    }
    return $result
}
try{
    $failed=$false;try{Invoke-RwCommand -ProjectRoot $testRoot -Action start -JavaHome $JavaHome -Port $testPort -SkipDatabase | Out-Null}catch{$failed=$true}
    if(!$failed){throw 'State replacement failure must not report successful startup'}
    if($global:rwFailureLock){$global:rwFailureLock.Dispose();$global:rwFailureLock=$null}
    Remove-Item -LiteralPath $global:rwFailureState -ErrorAction SilentlyContinue
    $leftover=@(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object {$_.CommandLine-and$_.CommandLine.Contains($testRoot)})
    if($leftover.Count){throw 'State write failure left an unmanaged Java process'}
    Write-Output 'State write failure after spawning cleans up the owned Java process.'
}finally{
    if($global:rwFailureLock){$global:rwFailureLock.Dispose()}
    Remove-Item -LiteralPath Function:\global:Get-FileHash
    Remove-Variable -Name rwFailureJar,rwFailureState,rwFailureLock -Scope Global
    try{Invoke-RwCommand -ProjectRoot $testRoot -Action stop -Port $testPort | Out-Null}catch{Write-Warning 'Owned failure fixture requires inspection.'}
}
