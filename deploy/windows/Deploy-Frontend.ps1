#requires -Version 5.1
<#
.SYNOPSIS
Builds the Vite frontend, validates the archive, uploads it with a manifest and
verifies the uploaded checksum.

.DESCRIPTION
Runs as the unprivileged deploy account. The privileged rsync/permission step is
performed by the root-owned ims-deploy-frontend orchestrator, because the deploy
account intentionally has no sudo permission for it.
#>
[CmdletBinding()]
param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot 'deploy.config.psd1'),
    [string]$RepoRoot = (Join-Path $PSScriptRoot '..\..'),
    [switch]$SkipInstall,
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

if ($SkipBuild) {
    $dist = Join-Path $repo 'dist'
    if (-not (Test-Path -LiteralPath $dist)) { throw 'No existing dist directory; run without -SkipBuild' }
    $assetCount = Test-ImsFrontendDist -DistPath $dist
    Write-Host "Using existing dist ($assetCount assets)"
    Write-Host 'WARNING: -SkipBuild uploads the existing dist; it is not tied to this commit.' -ForegroundColor Yellow
} else {
    Write-Host 'Building frontend (npm ci, lint, build)...'
    $dist = Invoke-ImsBuildFrontend -RepoRoot $repo -SkipInstall:$SkipInstall
}

$work = Join-Path $env:TEMP ("ims-deploy-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work -Force | Out-Null
try {
    $tarball = Join-Path $work 'frontend.tar.gz'
    $entryCount = New-ImsFrontendArchive -DistPath $dist -OutputPath $tarball
    Write-Host "Frontend archive validated: $entryCount entries"

    $manifestPath = Join-Path $work 'frontend.manifest'
    $manifest = New-ImsManifest -Type frontend -Commit $commitInfo.Full `
        -ShortCommit $commitInfo.Short -ArtifactPath $tarball -ManifestPath $manifestPath
    $null = Read-ImsManifest -Path $manifestPath

    $manifestDir = Join-Path $repo 'deploy\.local\manifests'
    if (-not (Test-Path -LiteralPath $manifestDir)) {
        New-Item -ItemType Directory -Path $manifestDir -Force | Out-Null
    }
    $stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    Copy-Item -LiteralPath $manifestPath -Destination (Join-Path $manifestDir "frontend-$stamp.manifest")

    Write-Host 'Uploading archive and manifest to the staging directory...'
    Send-ImsArtifact -Config $cfg -LocalPath $tarball -RemoteName 'frontend.tar.gz'
    Send-ImsArtifact -Config $cfg -LocalPath $manifestPath -RemoteName 'frontend.manifest'
    Assert-ImsRemoteSha -Config $cfg -RemoteName 'frontend.tar.gz' -ExpectedSha $manifest.Sha256
    Assert-ImsRemoteSha -Config $cfg -RemoteName 'frontend.manifest' `
        -ExpectedSha (Get-ImsFileSha256 -Path $manifestPath)
    Write-Host 'Upload verified (sha256 matches).'

    $null = Write-ImsDeploymentRecord -RepoRoot $repo -RelativeHistoryPath $cfg.LocalHistoryFile -Record @{
        action   = 'frontend'
        result   = 'uploaded-awaiting-root'
        commit   = $commitInfo.Full
        sha256   = $manifest.Sha256
        artifact = 'frontend.tar.gz'
        host     = $cfg.SshHost
        detail   = 'upload validated; privileged orchestrator not yet executed'
    }

    Write-Host ''
    Write-Host 'UPLOAD COMPLETE - deployment is NOT finished.' -ForegroundColor Yellow
    Write-Host 'The deploy account has no sudo permission for the privileged step.'
    Write-Host 'Run this on the server as root (or an admin with sudo):'
    Write-Host ''
    Write-Host "    /usr/local/sbin/ims-deploy-frontend --commit $($commitInfo.Full)" -ForegroundColor Cyan
    Write-Host ''
    Write-Host 'That command backs up the current frontend, deploys with rsync,'
    Write-Host 'verifies both public sites and restores the previous frontend on failure.'
} finally {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
