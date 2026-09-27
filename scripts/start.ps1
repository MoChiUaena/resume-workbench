param([string]$JavaHome = $env:JAVA_HOME, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
if ($JavaHome) { $env:JAVA_HOME = $JavaHome; $javaExecutable = Join-Path $JavaHome 'bin/java.exe' }
else { $javaExecutable = (Get-Command java -ErrorAction Stop).Source }
$version = (& $javaExecutable -version 2>&1 | Out-String)
if ($version -notmatch 'version "(2[1-9]|[3-9][0-9])\.') { throw '需要 JDK 21 或更新版本。请传入 -JavaHome 指向 JDK 21。' }
$env:PLAYWRIGHT_BROWSERS_PATH = Join-Path $projectRoot '.tools/ms-playwright'
$env:PLAYWRIGHT_SKIP_BROWSER_GC = '1'
if (-not $SkipBuild) {
    Push-Location frontend
    try {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'npm ci failed' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
    } finally { Pop-Location }
    & ./mvnw.cmd -B package
    if ($LASTEXITCODE -ne 0) { throw 'Java build failed' }
    & ./mvnw.cmd -B exec:java '-Dexec.mainClass=com.microsoft.playwright.CLI' '-Dexec.args=install chromium'
    if ($LASTEXITCODE -ne 0) { throw 'Chromium install failed' }
}
if (-not (Test-Path 'target/local-resume-0.1.0-SNAPSHOT.jar')) { throw '请先不带 -SkipBuild 运行一次。' }
& $javaExecutable -jar target/local-resume-0.1.0-SNAPSHOT.jar
