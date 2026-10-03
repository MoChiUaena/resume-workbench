#Requires -Version 7.2
Set-StrictMode -Version Latest

function Test-RwSamePath([string]$Left,[string]$Right) {
    if([string]::IsNullOrWhiteSpace($Left)-or[string]::IsNullOrWhiteSpace($Right)){return $false}
    try{return [IO.Path]::GetFullPath($Left).TrimEnd('\','/').Equals([IO.Path]::GetFullPath($Right).TrimEnd('\','/'),[StringComparison]::OrdinalIgnoreCase)}catch{return $false}
}

function Test-RwProcessIdentity([string]$ProjectRoot,[object]$State,[object]$Process) {
    if(!$State-or!$Process){return $false}
    foreach($property in @('projectRoot','jarPath','javaPath','pid','createdAt')){
        if(!$State.PSObject.Properties[$property]-or[string]::IsNullOrWhiteSpace([string]$State.$property)){return $false}
    }
    try{
        $expectedJar=Join-Path ([IO.Path]::GetFullPath($ProjectRoot)) 'target/resume-workbench.jar'
        if(!(Test-RwSamePath $ProjectRoot $State.projectRoot)-or!(Test-RwSamePath $expectedJar $State.jarPath)){return $false}
        if([long]$State.pid-le 0-or[long]$Process.ProcessId-ne[long]$State.pid-or$Process.Name-ne'java.exe'){return $false}
        if(!(Test-RwSamePath $Process.ExecutablePath $State.javaPath)-or[IO.Path]::GetFileName($State.javaPath)-ne'java.exe'){return $false}
        $recordedTime=if($State.createdAt-is[DateTime]){[DateTimeOffset]$State.createdAt}else{[DateTimeOffset]::Parse([string]$State.createdAt)}
        if($recordedTime.UtcTicks-ne([DateTimeOffset]([DateTime]$Process.CreationDate).ToUniversalTime()).UtcTicks){return $false}
        $java=[regex]::Escape([IO.Path]::GetFullPath($State.javaPath));$jar=[regex]::Escape([IO.Path]::GetFullPath($expectedJar))
        return [regex]::IsMatch($Process.CommandLine, ('^(?:"'+$java+'"|'+$java+')\s+-jar\s+(?:"'+$jar+'"|'+$jar+')\s*$'),[Text.RegularExpressions.RegexOptions]::IgnoreCase)
    }catch{return $false}
}

function Get-RwListeners([int]$Port) {
    try{return @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop)}
    catch{if($_.FullyQualifiedErrorId-like'*CmdletizationQuery_NotFound*'){return @()};throw '无法读取端口状态，请在有本机进程查询权限的终端运行。'}
}

function Get-RwProcess([long]$ProcessId) {
    return Get-CimInstance Win32_Process -Filter ('ProcessId = '+$ProcessId) -ErrorAction Stop
}

function Get-RwStoredState([string]$ProjectRoot) {
    $statePath=Join-Path $ProjectRoot '.tools/runtime/state.json'
    if(!(Test-Path -LiteralPath $statePath)){return $null}
    try{return Get-Content -LiteralPath $statePath -Raw -Encoding utf8 | ConvertFrom-Json -ErrorAction Stop}
    catch{throw '应用运行记录无法读取；原文件保留，不能依据该记录停止进程。'}
}

function Get-RwStatus([string]$ProjectRoot,[int]$Port=18765) {
    $ProjectRoot=[IO.Path]::GetFullPath($ProjectRoot)
    $stored=Get-RwStoredState $ProjectRoot
    $listeners=@(Get-RwListeners $Port)
    $result=[ordered]@{state='stopped';message='应用未启动';port=$Port;pid=$null;projectRoot=$ProjectRoot;process=$null;identity=$stored;managed=$false;runtime=$null;listening=$false}
    if($stored){
        if(!$stored.PSObject.Properties['pid']){throw '应用运行记录缺少进程身份；不能停止任何进程。'}
        $process=Get-RwProcess ([long]$stored.pid)
        if($process){
            if(!(Test-RwProcessIdentity $ProjectRoot $stored $process)){throw '运行记录与进程身份不一致；不能停止任何进程。'}
            $result.state='running';$result.message='本项目应用运行中';$result.pid=[long]$process.ProcessId
            $result.process=$process;$result.identity=$stored;$result.managed=$true
            if($stored.PSObject.Properties['port']){$result.port=[int]$stored.port}
            $bound=Get-RwBoundRuntime $ProjectRoot $stored $result.port
            $result.runtime=$bound.runtime;$result.listening=$bound.listening
            if(!$bound.listening){$result.message='本项目 Java 进程仍在运行，但尚未确认目标端口就绪；请查看日志。'}
            return [pscustomobject]$result
        }
    }
    if($listeners.Count){
        $owners=@($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
        if($owners.Count-eq 1){
            $process=Get-RwProcess $owners[0]
            if($process){
                $candidate=[pscustomobject]@{projectRoot=$ProjectRoot;jarPath=(Join-Path $ProjectRoot 'target/resume-workbench.jar');javaPath=$process.ExecutablePath;pid=$process.ProcessId;createdAt=([DateTime]$process.CreationDate).ToUniversalTime().ToString('o');port=$Port}
                if(Test-RwProcessIdentity $ProjectRoot $candidate $process){
                    $result.state='running';$result.message='本项目应用运行中（未由新入口启动）';$result.pid=[long]$process.ProcessId
                    $result.process=$process;$result.identity=$candidate
                    $bound=Get-RwBoundRuntime $ProjectRoot $candidate $Port
                    $result.runtime=$bound.runtime;$result.listening=$bound.listening;return [pscustomobject]$result
                }
            }
        }
        $result.state='port-in-use';$result.message='端口被其他程序占用；不会停止该程序'
    }
    return [pscustomobject]$result
}

function Get-RwRuntime([int]$Port) {
    try{return Invoke-RestMethod -Uri ('http://127.0.0.1:'+$Port+'/api/runtime') -TimeoutSec 2 -ErrorAction Stop}catch{return $null}
}

function Get-RwBoundRuntime([string]$ProjectRoot,[object]$Identity,[int]$Port) {
    $owners=@(Get-RwListeners $Port | Select-Object -ExpandProperty OwningProcess -Unique)
    $result=[pscustomobject]@{listening=($owners.Count-eq 1-and$owners[0]-eq$Identity.pid);runtime=$null}
    if(!$result.listening){return $result}
    $runtime=Get-RwRuntime $Port
    if(!$runtime){return $result}
    foreach($property in @('pid','jarPath','jarSha256','version')){if(!$runtime.PSObject.Properties[$property]){return $result}}
    if($runtime.pid-ne$Identity.pid-or!(Test-RwSamePath $runtime.jarPath (Join-Path $ProjectRoot 'target/resume-workbench.jar'))){return $result}
    if($Identity.PSObject.Properties['jarSha256']-and$runtime.jarSha256-ne$Identity.jarSha256){return $result}
    $currentOwners=@(Get-RwListeners $Port | Select-Object -ExpandProperty OwningProcess -Unique)
    if($currentOwners.Count-eq 1-and$currentOwners[0]-eq$Identity.pid){$result.runtime=$runtime}
    return $result
}

function Get-RwPackage([string]$ProjectRoot) {
    $jar=Join-Path $ProjectRoot 'target/resume-workbench.jar'
    if(!(Test-Path -LiteralPath $jar)){return [pscustomobject]@{version=$null;buildTime=$null;jarPath=$jar;sha256=$null}}
    $version=$null;$buildTime=$null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive=[IO.Compression.ZipFile]::OpenRead($jar)
    try{
        $entry=$archive.GetEntry('BOOT-INF/classes/META-INF/build-info.properties')
        if(!$entry){$entry=$archive.GetEntry('META-INF/build-info.properties')}
        if($entry){
            $reader=[IO.StreamReader]::new($entry.Open());try{$properties=$reader.ReadToEnd()}finally{$reader.Dispose()}
            if($properties-match'(?m)^build.version=(.+)\r?$'){$version=$Matches[1].Trim()}
            if($properties-match'(?m)^build.time=(.+)\r?$'){$buildTime=$Matches[1].Trim()}
        }
        if(!$version){
            $entry=$archive.GetEntry('META-INF/MANIFEST.MF')
            if($entry){$reader=[IO.StreamReader]::new($entry.Open());try{$manifest=$reader.ReadToEnd()}finally{$reader.Dispose()};if($manifest-match'(?m)^Implementation-Version: (.+)\r?$'){$version=$Matches[1].Trim()}}
        }
    }finally{$archive.Dispose()}
    return [pscustomobject]@{version=$version;buildTime=$buildTime;jarPath=$jar;sha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()}
}

function Save-RwState([string]$ProjectRoot,[object]$State) {
    $path=Join-Path $ProjectRoot '.tools/runtime/state.json'
    $temporary=$path+'.'+[guid]::NewGuid().ToString('N')+'.tmp'
    try{
        [IO.File]::WriteAllText($temporary,($State | ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $temporary -Destination $path -Force -ErrorAction Stop
    }finally{if(Test-Path -LiteralPath $temporary){Remove-Item -LiteralPath $temporary}}
}

function Stop-RwApplication([string]$ProjectRoot,[object]$Status) {
    if($Status.state-eq'port-in-use'){throw $Status.message}
    if($Status.state-eq'stopped'){return}
    $held=Get-Process -Id $Status.pid -ErrorAction SilentlyContinue
    if(!$held){return}
    try{
        # Retain the OS handle before checking CIM. Termination never resolves the PID again.
        $null=$held.SafeHandle
        $process=Get-RwProcess $Status.pid
        if(!$process-or$held.HasExited){return}
        if(!(Test-RwProcessIdentity $ProjectRoot $Status.identity $process)){throw '进程身份已改变，未停止任何进程。'}
        $heldTicks=([DateTimeOffset]$held.StartTime.ToUniversalTime()).UtcTicks
        $cimTicks=([DateTimeOffset]([DateTime]$process.CreationDate).ToUniversalTime()).UtcTicks
        if([Math]::Abs($heldTicks-$cimTicks)-ge 10){throw '进程句柄与创建时间不一致，未停止任何进程。'}
        $held.Kill()
        if(!$held.WaitForExit(10000)){throw '停止尚未完成，运行记录保留，请重新检查状态。'}
    }finally{$held.Dispose()}
}

function Resolve-RwJava([string]$JavaHome) {
    $path=if($JavaHome){Join-Path $JavaHome 'bin/java.exe'}else{(Get-Command java.exe -ErrorAction Stop).Source}
    $path=(Resolve-Path -LiteralPath $path -ErrorAction Stop).Path
    $version=& $path -version 2>&1 | Out-String
    if($LASTEXITCODE-ne 0-or$version-notmatch'version "(2[1-9]|[3-9][0-9])\.'){throw '需要 JDK 21 或更新版本；请通过 -JavaHome 指定 JDK 目录。'}
    return $path
}

function Build-RwApplication([string]$ProjectRoot,[string]$JavaPath) {
    $old=Join-Path $ProjectRoot 'target/resume-workbench.jar'
    if(Test-Path -LiteralPath $old){Copy-Item -LiteralPath $old -Destination (Join-Path $ProjectRoot '.tools/runtime/previous.jar') -Force}
    $env:JAVA_HOME=Split-Path (Split-Path $JavaPath -Parent) -Parent
    Push-Location $ProjectRoot
    try{
        Push-Location 'frontend'
        try{
            & npm.cmd ci | Out-Host;if($LASTEXITCODE-ne 0){throw '前端依赖安装失败。'}
            & npm.cmd run build | Out-Host;if($LASTEXITCODE-ne 0){throw '前端构建失败。'}
        }finally{Pop-Location}
        & ./mvnw.cmd -B package | Out-Host;if($LASTEXITCODE-ne 0){throw 'Java 构建失败；应用尚未启动。'}
        & ./mvnw.cmd -B exec:java '-Dexec.mainClass=com.microsoft.playwright.CLI' '-Dexec.args=install chromium' | Out-Host
        if($LASTEXITCODE-ne 0){throw '浏览器资源准备失败。'}
    }finally{Pop-Location}
}

function Start-RwApplication([string]$ProjectRoot,[string]$JavaPath,[int]$Port,[string]$DataDirectory,[bool]$Build,[bool]$SkipDatabase,[int]$StartupTimeoutSeconds) {
    if(@(Get-RwListeners $Port).Count){throw '目标端口已被占用，未启动第二个应用。'}
    $environmentKeys=@('JAVA_HOME','PORT','SERVER_ADDRESS','RESUME_DATA_DIR','RESUME_RUNTIME_LOG_DIR','RESUME_DB_PASSWORD','PLAYWRIGHT_BROWSERS_PATH','PLAYWRIGHT_SKIP_BROWSER_GC')
    $previousEnvironment=@{};foreach($key in $environmentKeys){$previousEnvironment[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
    $child=$null;$state=$null
    try{
        $logs=Join-Path $ProjectRoot '.tools/runtime/logs'
        [IO.Directory]::CreateDirectory($logs) | Out-Null
        $env:JAVA_HOME=Split-Path (Split-Path $JavaPath -Parent) -Parent
        $env:PORT=[string]$Port;$env:SERVER_ADDRESS='127.0.0.1'
        $env:RESUME_DATA_DIR=if($DataDirectory){[IO.Path]::GetFullPath($DataDirectory)}else{Join-Path $ProjectRoot 'data'}
        $env:RESUME_RUNTIME_LOG_DIR=$logs
        $env:PLAYWRIGHT_BROWSERS_PATH=Join-Path $ProjectRoot '.tools/ms-playwright'
        $env:PLAYWRIGHT_SKIP_BROWSER_GC='1'
        if(!$SkipDatabase){& (Join-Path $ProjectRoot 'scripts/prepare-db.ps1') | Out-Host}
        $jar=Join-Path $ProjectRoot 'target/resume-workbench.jar'
        if($Build-or!(Test-Path -LiteralPath $jar)){Build-RwApplication $ProjectRoot $JavaPath}
        $package=Get-RwPackage $ProjectRoot
        if(!$package.sha256){throw '应用安装包未准备好。'}
        if(@(Get-RwListeners $Port).Count){throw '构建期间目标端口已被占用，未启动应用。'}
        $stamp=[DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)
        $stdout=Join-Path $logs ($stamp+'-stdout.log');$stderr=Join-Path $logs ($stamp+'-stderr.log')
        $child=Start-Process -FilePath $JavaPath -ArgumentList @('-jar',('"'+$jar+'"')) -WorkingDirectory $ProjectRoot -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
        $null=$child.SafeHandle
        $process=Get-RwProcess $child.Id
        if(!$process){throw ('Java 启动后退出，请查看日志：'+$logs)}
        $state=[pscustomobject]@{schemaVersion=1;projectRoot=$ProjectRoot;jarPath=$jar;javaPath=$JavaPath;pid=$child.Id;createdAt=([DateTime]$process.CreationDate).ToUniversalTime().ToString('o');port=$Port;jarSha256=$package.sha256;version=$package.version;stdout=$stdout;stderr=$stderr;dataDirectory=$env:RESUME_DATA_DIR}
        if(!(Test-RwProcessIdentity $ProjectRoot $state $process)){throw '新进程身份无法核对；请保留启动日志并检查状态。'}
        Save-RwState $ProjectRoot $state
        $deadline=[DateTimeOffset]::UtcNow.AddSeconds($StartupTimeoutSeconds)
        while([DateTimeOffset]::UtcNow-lt$deadline){
            $process=Get-RwProcess $state.pid
            if(!$process){break}
            $owners=@(Get-RwListeners $Port | Select-Object -ExpandProperty OwningProcess -Unique)
            if($owners.Count-eq 1-and$owners[0]-eq$state.pid){
                try{
                    $health=Invoke-RestMethod -Uri ('http://127.0.0.1:'+$Port+'/api/health') -TimeoutSec 2 -ErrorAction Stop
                    $runtime=Get-RwRuntime $Port
                    $matches=!$runtime-or($runtime.pid-eq$state.pid-and(Test-RwSamePath $runtime.jarPath $jar)-and$runtime.jarSha256-eq$package.sha256)
                    if($health.status-eq'ok'-and$matches){return Get-RwStatus $ProjectRoot $Port}
                }catch{}
            }
            Start-Sleep -Milliseconds 200
        }
        $failed=[pscustomobject]@{state='running';pid=$state.pid;identity=$state}
        Stop-RwApplication $ProjectRoot $failed
        throw ('应用未能在 '+$StartupTimeoutSeconds+' 秒内就绪，已停止本次启动的进程；日志保留在 '+$logs)
    }catch{
        $startupError=$_.Exception.Message
        if($child-and!$child.HasExited){
            try{
                $current=Get-RwProcess $child.Id
                if(!$state-and$current){
                    $heldTicks=([DateTimeOffset]$child.StartTime.ToUniversalTime()).UtcTicks
                    $currentTicks=([DateTimeOffset]([DateTime]$current.CreationDate).ToUniversalTime()).UtcTicks
                    if([Math]::Abs($heldTicks-$currentTicks)-lt 10){$state=[pscustomobject]@{projectRoot=$ProjectRoot;jarPath=$jar;javaPath=$JavaPath;pid=$child.Id;createdAt=([DateTime]$current.CreationDate).ToUniversalTime().ToString('o')}}
                }
                if($state-and(Test-RwProcessIdentity $ProjectRoot $state $current)){Stop-RwApplication $ProjectRoot ([pscustomobject]@{state='running';pid=$child.Id;identity=$state})}
                else{throw '无法核验本次启动的进程身份'}
            }catch{throw ('启动失败，进程 PID '+$child.Id+' 需要检查；日志保留在 '+$logs+'。'+$startupError)}
        }
        throw $startupError
    }finally{if($child){$child.Dispose()};foreach($key in $environmentKeys){[Environment]::SetEnvironmentVariable($key,$previousEnvironment[$key],'Process')}}
}

function Invoke-RwCommand {
    param([Parameter(Mandatory)][string]$ProjectRoot,[ValidateSet('start','stop','restart','status','logs')][string]$Action='status',
        [string]$JavaHome=$env:JAVA_HOME,[ValidateRange(1024,65535)][int]$Port=18765,[string]$DataDirectory=$env:RESUME_DATA_DIR,
        [switch]$Build,[switch]$SkipDatabase,[ValidateRange(2,120)][int]$StartupTimeoutSeconds=45)
    $ProjectRoot=[IO.Path]::GetFullPath($ProjectRoot)
    $saved=Get-RwStoredState $ProjectRoot
    if($saved){
        if(!$saved.PSObject.Properties['projectRoot']-or!$saved.PSObject.Properties['jarPath']-or!(Test-RwSamePath $saved.projectRoot $ProjectRoot)-or!(Test-RwSamePath $saved.jarPath (Join-Path $ProjectRoot 'target/resume-workbench.jar'))){throw '运行记录属于其他项目，不能用于启动或停止。'}
        if(!$PSBoundParameters.ContainsKey('Port')-and$saved.PSObject.Properties['port']){$Port=[int]$saved.port;if($Port-lt 1024-or$Port-gt 65535){throw '保存的端口配置无效。'}}
        if(!$PSBoundParameters.ContainsKey('DataDirectory')-and$saved.PSObject.Properties['dataDirectory']){$DataDirectory=[string]$saved.dataDirectory}
        if(!$PSBoundParameters.ContainsKey('JavaHome')-and$saved.PSObject.Properties['javaPath']){$JavaHome=Split-Path (Split-Path $saved.javaPath -Parent) -Parent}
    }
    $status=Get-RwStatus $ProjectRoot $Port
    if($Action-in @('status','logs')){return $status}
    $runtimeDirectory=Join-Path $ProjectRoot '.tools/runtime'
    [IO.Directory]::CreateDirectory($runtimeDirectory) | Out-Null
    try{$lock=[IO.FileStream]::new((Join-Path $runtimeDirectory 'operation.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)}
    catch{throw '另一个启动或停止操作正在进行，请稍后查看状态。'}
    try{
        $status=Get-RwStatus $ProjectRoot $Port
        if($status.state-eq'port-in-use'){throw $status.message}
        if($Action-eq'start'-and$status.state-eq'running'){
            if($Build){throw '应用正在运行，不能覆盖运行中的 JAR；请使用 restart -Build。'}
            if($status.port-ne$Port){throw '本项目正在另一个端口运行；请先查看状态或明确重启。'}
            return $status
        }
        if($Action-ne'stop'){
            $javaPath=Resolve-RwJava $JavaHome
            if($DataDirectory){$DataDirectory=[IO.Path]::GetFullPath($DataDirectory)}
            if($Build-and(!(Test-Path -LiteralPath (Join-Path $ProjectRoot 'frontend/package-lock.json'))-or!(Test-Path -LiteralPath (Join-Path $ProjectRoot 'mvnw.cmd'))-or!(Get-Command npm.cmd -ErrorAction SilentlyContinue))){throw '构建工具或源码不完整，原应用保持运行。'}
            if(!$SkipDatabase){& (Join-Path $ProjectRoot 'scripts/prepare-db.ps1') -CheckOnly | Out-Host}
        }
        if($Action-in @('stop','restart')){Stop-RwApplication $ProjectRoot $status}
        if($Action-eq'stop'){return Get-RwStatus $ProjectRoot $Port}
        return Start-RwApplication $ProjectRoot $javaPath $Port $DataDirectory $Build.IsPresent $SkipDatabase.IsPresent $StartupTimeoutSeconds
    }finally{$lock.Dispose()}
}

Export-ModuleMember -Function Test-RwProcessIdentity,Get-RwStatus,Get-RwPackage,Invoke-RwCommand
