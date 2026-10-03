#Requires -Version 7.2
param([switch]$CheckOnly)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$ownerPath=Join-Path $projectRoot '.tools/runtime/database-owner.json'
$volumeName='local-resume-dev_resume_pgdata'
# Serialize the shared Compose project across checkouts, including the ownership check.
$guard=[Threading.Mutex]::new($false,'Global\ResumeWorkbench.LocalResumeDevDatabase')
$held=$false
try{
    try{$held=$guard.WaitOne(10000)}catch [Threading.AbandonedMutexException]{$held=$true}
    if(!$held){throw '另一份项目正在准备开发数据库，请稍后重试。'}
    $existing=@(& docker ps -a --filter 'label=com.docker.compose.project=local-resume-dev' --filter 'label=com.docker.compose.service=db' --format '{{.ID}}')
    if($LASTEXITCODE-ne 0){throw '无法检查开发数据库归属，请先检查 Docker Desktop。'}
    foreach($containerId in $existing){
        $owner=& docker inspect $containerId --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}'
        if($LASTEXITCODE-ne 0-or[string]::IsNullOrWhiteSpace($owner)-or
            ![IO.Path]::GetFullPath($owner).TrimEnd('\','/').Equals($projectRoot.TrimEnd('\','/'),[StringComparison]::OrdinalIgnoreCase)){
            throw '已有开发数据库属于另一份项目目录；请从原目录启动，或明确配置外部数据库并使用 -SkipDatabase。未修改数据库或本地配置。'
        }
    }
    $volumes=@(& docker volume ls --format '{{.Name}}' --filter ('name='+$volumeName) | Where-Object {$_-eq$volumeName})
    if($LASTEXITCODE-ne 0){throw '无法核验开发数据库的数据卷，未修改任何配置。'}
    if($volumes.Count-and!$existing.Count){
        $created=& docker volume inspect $volumeName --format '{{.CreatedAt}}'
        if($LASTEXITCODE-ne 0){throw '现有开发数据卷无法读取。'}
        $fingerprint=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($created.Trim())))
        $record=$null
        if(Test-Path -LiteralPath $ownerPath){try{$record=Get-Content -LiteralPath $ownerPath -Raw -Encoding utf8 | ConvertFrom-Json}catch{}}
        if(!$record-or!$record.PSObject.Properties['projectRoot']-or!$record.PSObject.Properties['volumeFingerprint']-or!$record.PSObject.Properties['volumeName']-or
            $record.volumeName-ne$volumeName-or$record.volumeFingerprint-ne$fingerprint-or
            ![IO.Path]::GetFullPath($record.projectRoot).Equals($projectRoot,[StringComparison]::OrdinalIgnoreCase)){
            throw '开发数据卷已存在，但无法确认属于当前目录。未重建数据库或修改配置；请核对原项目，或明确配置外部数据库并使用 -SkipDatabase。'
        }
    }
    if($CheckOnly){return}
    $configPath=Join-Path $projectRoot '.env'
    if(!(Test-Path -LiteralPath $configPath)){
        $password=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
        [IO.File]::WriteAllText($configPath,"RESUME_DB_PASSWORD=$password`n")
    }
    $env:RESUME_DB_PASSWORD=$null
    foreach($line in [IO.File]::ReadAllLines($configPath)){if($line-match'^RESUME_DB_PASSWORD=([a-zA-Z0-9_-]+)$'){$env:RESUME_DB_PASSWORD=$Matches[1]}}
    if(!$env:RESUME_DB_PASSWORD){throw '.env 缺少合法的 RESUME_DB_PASSWORD，请检查本地配置文件。'}
    Push-Location $projectRoot
    try{
        & docker compose --project-name local-resume-dev -f compose.dev.yml up -d --wait
        if($LASTEXITCODE-ne 0){throw 'PostgreSQL 启动失败，请检查 Docker Desktop。'}
    }finally{Pop-Location}
    $created=& docker volume inspect $volumeName --format '{{.CreatedAt}}'
    if($LASTEXITCODE-ne 0){throw '数据库已准备，但数据卷归属记录尚未保存，请保留现有容器。'}
    [IO.Directory]::CreateDirectory((Split-Path $ownerPath -Parent)) | Out-Null
    $fingerprint=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($created.Trim())))
    $record=[pscustomobject]@{schemaVersion=1;projectRoot=$projectRoot;volumeName=$volumeName;volumeCreatedAt=$created;volumeFingerprint=$fingerprint}
    $temporary=$ownerPath+'.'+[guid]::NewGuid().ToString('N')+'.tmp'
    try{
        [IO.File]::WriteAllText($temporary,($record | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $temporary -Destination $ownerPath -Force
    }finally{if(Test-Path -LiteralPath $temporary){Remove-Item -LiteralPath $temporary}}
}finally{if($held){$guard.ReleaseMutex()};$guard.Dispose()}
