param([string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '../RuntimeControl.psm1') -Force
function Assert-Result([bool]$Condition,[string]$Message){if(!$Condition){throw $Message}}
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('rw-control-tests-'+[guid]::NewGuid().ToString('N'))
$testJar=Join-Path $testRoot 'target/resume-workbench.jar'
$testJava=Join-Path $testRoot 'jdk/bin/java.exe'
$testTime=[DateTime]::Parse('2026-10-03T00:00:00Z').ToUniversalTime()
$testState=[pscustomobject]@{projectRoot=$testRoot;jarPath=$testJar;javaPath=$testJava;pid=12345;createdAt=$testTime.ToString('o');port=18765}
$testProcess=[pscustomobject]@{ProcessId=12345;Name='java.exe';ExecutablePath=$testJava;CommandLine=('"'+$testJava+'" -jar "'+$testJar+'"');CreationDate=$testTime}
Assert-Result (Test-RwProcessIdentity -ProjectRoot $testRoot -State $testState -Process $testProcess) 'The exact project process should be recognized'
$testPrecisionState=$testState | ConvertTo-Json | ConvertFrom-Json
$testPreciseTime=$testTime.AddTicks(1234567)
$testPrecisionState.createdAt=$testPreciseTime.ToString('o')
$testPrecisionState=$testPrecisionState | ConvertTo-Json | ConvertFrom-Json
$testPreciseProcess=$testProcess | ConvertTo-Json | ConvertFrom-Json
$testPreciseProcess.CreationDate=$testPreciseTime
Assert-Result (Test-RwProcessIdentity -ProjectRoot $testRoot -State $testPrecisionState -Process $testPreciseProcess) 'JSON date conversion must preserve subsecond process identity'
foreach($testChange in @('reused-pid','other-jar','other-executable','other-project','extra-arguments','missing-identity')){
    $testAlteredState=$testState | ConvertTo-Json | ConvertFrom-Json
    $testAlteredProcess=$testProcess | ConvertTo-Json | ConvertFrom-Json
    switch($testChange){
        'reused-pid' {$testAlteredProcess.CreationDate=$testTime.AddSeconds(10)}
        'other-jar' {$testAlteredProcess.CommandLine='java.exe -jar unrelated.jar'}
        'other-executable' {$testAlteredProcess.ExecutablePath=Join-Path $testRoot 'other/java.exe'}
        'other-project' {$testAlteredState.projectRoot=Join-Path $testRoot 'other'}
        'extra-arguments' {$testAlteredProcess.CommandLine+=' --other-service'}
        'missing-identity' {$testAlteredState.PSObject.Properties.Remove('createdAt')}
    }
    Assert-Result (!(Test-RwProcessIdentity -ProjectRoot $testRoot -State $testAlteredState -Process $testAlteredProcess)) ('Unsafe process identity accepted: '+$testChange)
}
$testForeignState=$testState | ConvertTo-Json | ConvertFrom-Json
$testForeignState.jarPath=Join-Path $testRoot 'another.jar'
Assert-Result (!(Test-RwProcessIdentity -ProjectRoot $testRoot -State $testForeignState -Process $testProcess)) 'Stored metadata cannot redirect management to an unrelated JAR'
$testInactive=Get-RwStatus -ProjectRoot $testRoot -Port 49151
Assert-Result ($testInactive.state -eq 'stopped') 'An absent project must be stopped'
Assert-Result (!(Test-Path -LiteralPath $testRoot)) 'Reading status must not create state directories'
Write-Output 'Runtime identity and read-only status checks passed (11 cases).'
