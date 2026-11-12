#requires -Version 5.1
<#
.SYNOPSIS
Builds the Spring Boot jar, validates it, uploads it with a manifest, and
verifies the uploaded checksum.

.DESCRIPTION
Runs as the unprivileged deploy account. The privileged install/restart step is
performed by the root-owned ims-deploy-backend orchestrator, because the deploy
account intentionally has no sudo permission for it. This script never pushes,
never deploys by itself and never handles secrets.
#>
[CmdletBinding()]
param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot 'deploy.config.psd1'),
    [string]$RepoRoot = (Join-Path $PSScriptRoot '..\..'),
    [switch]$SkipBuild,
    [switch]$SkipConnectivityTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot 'Deploy-Common.psm1') -Force

$cfg = Get-ImsDeployConfig -Path $ConfigPath
$repo = Get-ImsRepoRoot -Path $RepoRoot
Assert-ImsGitClean -RepoRoot $repo
$commitInfo = Get-ImsGitCommit -RepoRoot $repo
$null = Assert-ImsPrerequisites -Config $cfg

Write-Host "Repository : $repo"
Write-Host "Commit     : $($commitInfo.Full)"
Write-Host "Target     : $($cfg.SshUser)@$($cfg.SshHost):$($cfg.RemoteIncoming)"

if (-not $SkipConnectivityTest) {
    Write-Host 'Checking SSH connectivity (the key passphrase may be requested)...'
    $preflight = Get-ImsRemotePreflight -Config $cfg
    Write-Host '--- remote preflight (read-only) ---'
    Write-Host $preflight
    if (($preflight -join "`n") -notmatch 'health=200') {
        throw 'Remote preflight failed: local /api/health is not 200. Fix the service before deploying.'
    }
}

$backendRoot = Join-Path $repo 'backend'
if ($SkipBuild) {
    $jars = @(Get-ChildItem -LiteralPath (Join-Path $backendRoot 'build\libs') -Filter '*.jar' -File |
        Where-Object { $_.Name -notlike '*-plain.jar' })
    if ($jars.Count -ne 1) { throw 'No existing boot jar to upload; run without -SkipBuild' }
    $jar = $jars[0].FullName
    Write-Host "Using existing jar: $jar"
} else {
    Write-Host 'Building backend (clean test bootJar)...'
    $jar = Invoke-ImsBuildBackend -RepoRoot $repo
}

$jarInfo = Test-ImsBackendJar -JarPath $jar -Commit $commitInfo.Full
Write-Host "Jar validated: commit $($jarInfo.EmbeddedCommit), $($jarInfo.Migrations.Count) migrations"

$work = Join-Path $env:TEMP ("ims-deploy-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null
try {
    $manifestPath = Join-Path $work 'backend.manifest'
    $manifest = New-ImsManifest -Type backend -Commit $commitInfo.Full `
        -ShortCommit $commitInfo.Short -ArtifactPath $jar -ManifestPath $manifestPath
    $null = Read-ImsManifest -Path $manifestPath

    $manifestDir = Join-Path $repo 'deploy\.local\manifests'
    if (-not (Test-Path -LiteralPath $manifestDir)) {
        New-Item -ItemType Directory -Path $manifestDir -Force | Out-Null
    }
    $stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $manifestDir "backend-$stamp.manifest")

    Write-Host 'Uploading jar and manifest to the staging directory...'
    Send-ImsArtifact -Config $cfg -LocalPath $jar -RemoteName 'item-management.jar'
    Send-ImsArtifact -Config $cfg -LocalPath $manifestPath -RemoteName 'backend.manifest'
    Assert-ImsRemoteSha -Config $cfg -RemoteName 'item-management.jar' -ExpectedSha $manifest.Sha256
    Assert-ImsRemoteSha -Config $cfg -RemoteName 'backend.manifest' `
        -ExpectedSha (Get-ImsFileSha256 -Path $manifestPath)
    Write-Host 'Upload verified (sha256 matches).'

    $null = Write-ImsDeploymentRecord -RepoRoot $repo -RelativeHistoryPath $cfg.LocalHistoryFile -Record @{
        action   = 'backend'
        result   = 'uploaded-awaiting-root'
        commit   = $commitInfo.Full
        sha256   = $manifest.Sha256
        artifact = 'item-management.jar'
        host     = $cfg.SshHost
        detail   = 'upload validated; privileged orchestrator not yet executed'
    }

    Write-Host ''
    Write-Host 'UPLOAD COMPLETE - deployment is NOT finished.' -ForegroundColor Yellow
    Write-Host 'The deploy account has no sudo permission for the privileged step.'
    Write-Host 'Run this on the server as root (or an admin with sudo):'
    Write-Host ''
    Write-Host "    /usr/local/sbin/ims-deploy-backend --commit $($commitInfo.Full)" -ForegroundColor Cyan
    Write-Host ''
    Write-Host 'That command takes the database/jar/frontend backups, installs the jar,'
    Write-Host 'restarts item-management, verifies health and rolls back on failure.'
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
