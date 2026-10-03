#Requires -Version 7.2
param([ValidateSet('start','stop','restart','status','logs')][string]$Action='status',
    [string]$JavaHome=$env:JAVA_HOME,[ValidateRange(1024,65535)][int]$Port=18765,
    [string]$DataDirectory=$env:RESUME_DATA_DIR,[switch]$Build,[switch]$SkipDatabase,
    [ValidateRange(2,120)][int]$StartupTimeoutSeconds=45,[switch]$Json)
$ErrorActionPreference='Stop'
Import-Module (Join-Path $PSScriptRoot 'RuntimeControl.psm1') -Force
$taskProjectRoot=[IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
try{
    $taskCommand=@{ProjectRoot=$taskProjectRoot;Action=$Action;Build=$Build;SkipDatabase=$SkipDatabase;StartupTimeoutSeconds=$StartupTimeoutSeconds}
    foreach($taskOption in @('JavaHome','Port','DataDirectory')){if($PSBoundParameters.ContainsKey($taskOption)){$taskCommand[$taskOption]=$PSBoundParameters[$taskOption]}}
    $taskStatus=Invoke-RwCommand @taskCommand
    $taskPackage=Get-RwPackage $taskProjectRoot
    $taskPublic=[pscustomobject]@{state=$taskStatus.state;message=$taskStatus.message;pid=$taskStatus.pid;port=$taskStatus.port;url=('http://127.0.0.1:'+$taskStatus.port);installedVersion=$taskPackage.version;installedJarSha256=$taskPackage.sha256;runtime=$taskStatus.runtime;dataDirectory=$(if($taskStatus.identity-and$taskStatus.identity.PSObject.Properties['dataDirectory']){$taskStatus.identity.dataDirectory}elseif($DataDirectory){[IO.Path]::GetFullPath($DataDirectory)}else{Join-Path $taskProjectRoot 'data'});logsDirectory=(Join-Path $taskProjectRoot '.tools/runtime/logs')}
    if($Json){$taskPublic | ConvertTo-Json -Depth 5}else{
        Write-Output $taskPublic.message
        if($taskPublic.pid){Write-Output ('PID: '+$taskPublic.pid)}
        Write-Output ('地址: '+$taskPublic.url)
        Write-Output ('安装包版本: '+$(if($taskPublic.installedVersion){$taskPublic.installedVersion}else{'尚未构建或未记录'}))
        if($taskPublic.installedJarSha256){Write-Output ('安装包校验值: '+$taskPublic.installedJarSha256.Substring(0,16))}
        if($taskPublic.runtime){Write-Output ('运行版本: '+$taskPublic.runtime.version);Write-Output ('启动时间: '+$taskPublic.runtime.startedAt)}
        Write-Output ('数据目录: '+$taskPublic.dataDirectory)
        Write-Output ('日志目录: '+$taskPublic.logsDirectory)
    }
    exit 0
}catch{Write-Error $_.Exception.Message;exit 1}
