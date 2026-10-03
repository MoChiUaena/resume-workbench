$ErrorActionPreference='Stop'
$testRoot=Join-Path ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../output'))) ('db-ownership-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $testRoot 'scripts') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../prepare-db.ps1') -Destination (Join-Path $testRoot 'scripts/prepare-db.ps1')
$global:rwOwnershipMarker=Join-Path $testRoot 'unexpected-compose-up'
$global:rwOwnershipMode='container'
$global:rwOwnershipRoot=$testRoot
function global:docker {
    $global:LASTEXITCODE=0
    switch($args[0]){
        'ps' {if($global:rwOwnershipMode-in @('container','owned')){return 'ownership-fixture'}}
        'inspect' {if($global:rwOwnershipMode-eq'owned'){return $global:rwOwnershipRoot}else{return 'C:\unrelated-resume-checkout'}}
        'volume' {if($args[1]-eq'ls'){return 'local-resume-dev_resume_pgdata'}else{return '2026-09-27T05:20:05Z'}}
        'compose' {[IO.File]::WriteAllText($global:rwOwnershipMarker,'unexpected reconciliation')}
        default {throw 'Unexpected Docker operation in ownership fixture'}
    }
}
try{
    $refused=$false
    try{& (Join-Path $testRoot 'scripts/prepare-db.ps1')}catch{$refused=$true}
    if(!$refused){throw 'Database preparation must refuse a Compose project owned by another checkout'}
    if(Test-Path -LiteralPath $global:rwOwnershipMarker){throw 'Foreign database preparation caused an external mutation'}
    if(Test-Path -LiteralPath (Join-Path $testRoot '.env')){throw 'Foreign ownership must be checked before creating credentials'}
    $global:rwOwnershipMode='volume';$refused=$false
    try{& (Join-Path $testRoot 'scripts/prepare-db.ps1')}catch{$refused=$true}
    if(!$refused-or(Test-Path -LiteralPath $global:rwOwnershipMarker)){throw 'An unowned retained database volume must not be reconciled'}
    $global:rwOwnershipMode='owned'; & (Join-Path $testRoot 'scripts/prepare-db.ps1')
    if(!(Test-Path -LiteralPath (Join-Path $testRoot '.tools/runtime/database-owner.json'))){throw 'Owned database preparation must record persistent volume identity'}
    Remove-Item -LiteralPath $global:rwOwnershipMarker
    $global:rwOwnershipMode='volume'; & (Join-Path $testRoot 'scripts/prepare-db.ps1') -CheckOnly
    if(Test-Path -LiteralPath $global:rwOwnershipMarker){throw 'Read-only ownership preflight must not reconcile containers'}
    & (Join-Path $testRoot 'scripts/prepare-db.ps1')
    if(!(Test-Path -LiteralPath $global:rwOwnershipMarker)){throw 'The recorded owner must be able to recreate its retained volume'}
    Write-Output 'Foreign resources are rejected; owned container/retained-volume preparation and read-only preflight passed.'
}finally{Remove-Item -LiteralPath Function:\global:docker;Remove-Variable -Name rwOwnershipMarker,rwOwnershipMode,rwOwnershipRoot -Scope Global}
