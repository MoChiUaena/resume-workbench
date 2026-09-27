$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$configPath = Join-Path $projectRoot '.env'
if (-not (Test-Path -LiteralPath $configPath)) {
    $password = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
    [System.IO.File]::WriteAllText($configPath, "RESUME_DB_PASSWORD=$password`n")
}
foreach ($line in [System.IO.File]::ReadAllLines($configPath)) {
    if ($line -match '^RESUME_DB_PASSWORD=([a-zA-Z0-9_-]+)$') { $env:RESUME_DB_PASSWORD = $Matches[1] }
}
if (-not $env:RESUME_DB_PASSWORD) { throw '.env 缺少合法的 RESUME_DB_PASSWORD，请检查本地配置文件。' }
Push-Location $projectRoot
try {
    & docker compose -f compose.dev.yml up -d --wait
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL 启动失败，请检查 Docker Desktop。' }
} finally { Pop-Location }
