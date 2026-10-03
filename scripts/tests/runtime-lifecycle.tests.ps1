param([Parameter(Mandatory)][string]$JavaHome)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot '../RuntimeControl.psm1') -Force
function Assert-Result([bool]$Condition,[string]$Message){if(!$Condition){throw $Message}}
function Assert-Refused([scriptblock]$Action,[string]$Message){$refused=$false;try{& $Action | Out-Null}catch{$refused=$true};Assert-Result $refused $Message}
function Get-FreePort { $listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0);$listener.Start();try{return $listener.LocalEndpoint.Port}finally{$listener.Stop()} }
$testWorkspace=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../output/runtime-lifecycle'))
$testRoot=Join-Path $testWorkspace ('owned '+[guid]::NewGuid().ToString('N'))
$testOther=Join-Path $testRoot 'unrelated project'
$testData=Join-Path $testRoot 'custom data'
$testPort=Get-FreePort;$testOtherPort=Get-FreePort
$testCreatedRoots=@($testRoot,$testOther)
try{
    foreach($testProject in $testCreatedRoots){
        $testClasses=Join-Path $testProject 'classes';$testTarget=Join-Path $testProject 'target'
        New-Item -ItemType Directory -Path $testClasses,$testTarget -Force | Out-Null
        & (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -d $testClasses (Join-Path $PSScriptRoot 'RuntimeFixture.java')
        if($LASTEXITCODE-ne 0){throw 'Fixture compilation failed'}
        New-Item -ItemType Directory -Path (Join-Path $testClasses 'META-INF') -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $testClasses 'META-INF/build-info.properties') -Value 'build.version=fixture-1' -Encoding utf8NoBOM
        & (Join-Path $JavaHome 'bin/jar.exe') --create --file (Join-Path $testTarget 'resume-workbench.jar') --main-class RuntimeFixture -C $testClasses .
        if($LASTEXITCODE-ne 0){throw 'Fixture packaging failed'}
    }
    $first=Invoke-RwCommand -ProjectRoot $testRoot -Action start -JavaHome $JavaHome -Port $testPort -DataDirectory $testData -SkipDatabase
    Assert-Result ($first.state-eq'running'-and$first.runtime.version-eq'fixture-1') 'Start must reach the intended application and version'
    $again=Invoke-RwCommand -ProjectRoot $testRoot -Action start -JavaHome $JavaHome -Port $testPort -SkipDatabase
    Assert-Result ($again.pid-eq$first.pid) 'Repeated start must not create a duplicate process'
    Assert-Refused {Invoke-RwCommand -ProjectRoot $testRoot -Action start -JavaHome $JavaHome -Port $testPort -Build -SkipDatabase} 'Building over a running JAR must be refused'
    Set-Content -LiteralPath (Join-Path $testRoot 'misreport-pid') -Value 'fixture only'
    Assert-Result ($null-eq(Invoke-RwCommand -ProjectRoot $testRoot -Action status -Port $testPort).runtime) 'Runtime responses with a different PID must not be attributed to this project'
    Remove-Item -LiteralPath (Join-Path $testRoot 'misreport-pid')
    Assert-Refused {Invoke-RwCommand -ProjectRoot $testRoot -Action restart -JavaHome (Join-Path $testRoot 'missing jdk') -Port $testPort -SkipDatabase} 'Invalid Java configuration must be refused'
    Assert-Result ([bool](Get-Process -Id $first.pid -ErrorAction SilentlyContinue)) 'Invalid restart parameters must preserve the healthy instance'
    $testPreviousJavaHome=$env:JAVA_HOME;$testPreviousData=$env:RESUME_DATA_DIR
    $env:JAVA_HOME=Join-Path $testRoot 'shell-default-jdk';$env:RESUME_DATA_DIR=Join-Path $testRoot 'shell-default-data'
    try{$restarted=Invoke-RwCommand -ProjectRoot $testRoot -Action restart -SkipDatabase}finally{$env:JAVA_HOME=$testPreviousJavaHome;$env:RESUME_DATA_DIR=$testPreviousData}
    Assert-Result ($restarted.pid-ne$first.pid-and$restarted.runtime.instanceId-ne$first.runtime.instanceId) 'Restart must switch to a new verified instance'
    Assert-Result ($restarted.port-eq$testPort-and$restarted.runtime.dataDirectory-eq$testData) 'Restart must retain recorded port and data directory unless explicitly overridden'
    Invoke-RestMethod -Uri ('http://127.0.0.1:'+$testPort+'/api/fixture/detach') -Method Post | Out-Null
    $testDetachDeadline=[DateTimeOffset]::UtcNow.AddSeconds(5)
    while((Get-NetTCPConnection -LocalPort $testPort -State Listen -ErrorAction SilentlyContinue)-and[DateTimeOffset]::UtcNow-lt$testDetachDeadline){Start-Sleep -Milliseconds 100}
    $testPeer=Invoke-RwCommand -ProjectRoot $testOther -Action start -JavaHome $JavaHome -Port $testPort -SkipDatabase
    Assert-Result ($null-eq(Invoke-RwCommand -ProjectRoot $testRoot -Action status -Port $testPort).runtime) 'A foreign listener must not supply this project runtime information'
    Invoke-RwCommand -ProjectRoot $testRoot -Action stop -Port $testPort | Out-Null
    Assert-Result ([bool](Get-Process -Id $testPeer.pid -ErrorAction SilentlyContinue)) 'Stopping the owned JVM must preserve a foreign listener'
    Invoke-RwCommand -ProjectRoot $testOther -Action stop -Port $testPort | Out-Null
    $restarted=Invoke-RwCommand -ProjectRoot $testRoot -Action start -JavaHome $JavaHome -Port $testPort -DataDirectory $testData -SkipDatabase
    $statePath=Join-Path $testRoot '.tools/runtime/state.json'
    $savedState=Get-Content -LiteralPath $statePath -Raw -Encoding utf8
    $other=Invoke-RwCommand -ProjectRoot $testOther -Action start -JavaHome $JavaHome -Port $testOtherPort -SkipDatabase
    $tampered=$savedState | ConvertFrom-Json;$tampered.pid=$other.pid
    $tampered | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $statePath -Encoding utf8
    Assert-Refused {Invoke-RwCommand -ProjectRoot $testRoot -Action stop -Port $testPort} 'Tampered process records must not stop another application'
    Assert-Result ([bool](Get-Process -Id $other.pid -ErrorAction SilentlyContinue)) 'Unrelated application must stay alive'
    Set-Content -LiteralPath $statePath -Value $savedState -Encoding utf8
    Invoke-RwCommand -ProjectRoot $testRoot -Action stop -Port $testPort | Out-Null
    Assert-Result ((Get-RwStatus -ProjectRoot $testRoot -Port $testPort).state-eq'stopped') 'Stop must return a stopped state'
    Assert-Refused {Invoke-RwCommand -ProjectRoot $testRoot -Action start -JavaHome $JavaHome -Port $testOtherPort -SkipDatabase} 'Foreign port ownership must block start'
    Assert-Refused {Invoke-RwCommand -ProjectRoot $testRoot -Action stop -Port $testOtherPort} 'Foreign port ownership must block stop'
    Assert-Result ([bool](Get-Process -Id $other.pid -ErrorAction SilentlyContinue)) 'Port conflict must preserve the other application'
    Set-Content -LiteralPath $statePath -Value '{broken record' -Encoding utf8
    Assert-Refused {Invoke-RwCommand -ProjectRoot $testRoot -Action stop -Port $testPort} 'Corrupt records must fail closed'
    Assert-Result ((Get-Content -LiteralPath $statePath -Raw)-match'broken record') 'Corrupt record must be retained for diagnosis'
    Set-Content -LiteralPath $statePath -Value $savedState -Encoding utf8
    $adopted=Invoke-RwCommand -ProjectRoot $testRoot -Action start -JavaHome $JavaHome -Port $testPort -SkipDatabase
    Remove-Item -LiteralPath $statePath
    Assert-Result (!(Get-RwStatus -ProjectRoot $testRoot -Port $testPort).managed) 'Status may identify a legacy absolute-JAR launch without writing a new record'
    Invoke-RwCommand -ProjectRoot $testRoot -Action stop -Port $testPort | Out-Null
    Set-Content -LiteralPath (Join-Path $testRoot 'fail-ready') -Value 'fixture only'
    Assert-Refused {Invoke-RwCommand -ProjectRoot $testRoot -Action start -JavaHome $JavaHome -Port $testPort -SkipDatabase -StartupTimeoutSeconds 2} 'Failed readiness must not report successful startup'
    Assert-Result ((Get-RwStatus -ProjectRoot $testRoot -Port $testPort).state-eq'stopped') 'Failed startup must clean up only its own process'
    Write-Output 'Real Java lifecycle checks passed: start, duplicate start, restart, ownership, port conflict, corrupt metadata, legacy launch, and failed readiness.'
}finally{
    foreach($testProject in $testCreatedRoots){
        try{Invoke-RwCommand -ProjectRoot $testProject -Action stop -Port $(if($testProject-eq$testRoot){$testPort}else{$testOtherPort}) | Out-Null}catch{Write-Warning 'Test fixture cleanup needs inspection; no unrelated process was stopped.'}
    }
    # Deliberately preserve fixture logs/state under ignored output for failure diagnosis.
}
