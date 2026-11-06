#requires -Version 5.1
<#
.SYNOPSIS
Read-only readiness checks for an IMS deployment.

.DESCRIPTION
Verifies local tooling, the deployment key, config validity, git cleanliness and
optionally SSH connectivity and remote read-only preflight. Makes no changes.
#>
[CmdletBinding()]
param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot 'deploy.config.psd1'),
    [string]$RepoRoot = (Join-Path $PSScriptRoot '..\..'),
    [switch]$TestConnectivity
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot 'Deploy-Common.psm1') -Force

$failures = New-Object System.Collections.Generic.List[string]

function Report-Check {
    param([string]$Name, [bool]$Ok, [string]$Detail = '')
    if ($Ok) {
        Write-Host ("PASS  {0}" -f $Name) -ForegroundColor Green
    } else {
        Write-Host ("FAIL  {0}  {1}" -f $Name, $Detail) -ForegroundColor Red
        $failures.Add($Name)
    }
    if ($Detail -and $Ok) { Write-Host "      $Detail" }
}

$repo = $null
try {
    $repo = Get-ImsRepoRoot -Path $RepoRoot
    Report-Check 'git repository' $true $repo
} catch {
    Report-Check 'git repository' $false $_.Exception.Message
}

if ($repo) {
    $status = & git -C $repo status --porcelain
    $clean = [string]::IsNullOrWhiteSpace(($status -join ''))
    $detail = if ($clean) { 'working tree clean' } else { (($status | Select-Object -First 5) -join '; ') }
    Report-Check 'git working tree clean' $clean $detail

    if ($clean) {
        $commit = Get-ImsGitCommit -RepoRoot $repo
        Write-Host "      HEAD $($commit.Full)"
    }
}

foreach ($tool in @('git', 'node', 'npm', 'java', 'ssh', 'scp', 'tar')) {
    $found = Get-Command $tool -ErrorAction SilentlyContinue
    Report-Check "tool: $tool" ($null -ne $found) $(if ($found) { $found.Source } else { 'not on PATH' })
}

$javaVersion = Get-ImsJavaVersion
$javaDetail = if ($javaVersion) { ($javaVersion -split "`n")[0].Trim() } else { 'java not found' }
Report-Check 'Java 17' ($javaVersion -match 'version "17') $javaDetail

$cfg = $null
try {
    $cfg = Get-ImsDeployConfig -Path $ConfigPath
    Report-Check 'deployment config' $true $ConfigPath
} catch {
    Report-Check 'deployment config' $false $_.Exception.Message
}

if ($cfg) {
    Report-Check 'SSH key exists' (Test-Path -LiteralPath $cfg.SshKeyPath) $cfg.SshKeyPath
    $acl = (icacls $cfg.SshKeyPath 2>&1 | Out-String)
    $otherPrincipals = ($acl -split "`n" | Where-Object {
            $_ -match '\\(Users|Authenticated Users|Everyone):' -or $_ -match 'BUILTIN\\Users:'
        })
    Report-Check 'SSH key ACL has no broad principals' ($otherPrincipals.Count -eq 0) `
        (($otherPrincipals -join '; '))

    if ($TestConnectivity) {
        try {
            $null = Invoke-ImsSsh -Config $cfg -Command 'true'
            Report-Check 'SSH connectivity' $true "$($cfg.SshUser)@$($cfg.SshHost):$($cfg.SshPort)"
            $preflight = Get-ImsRemotePreflight -Config $cfg
            Write-Host '--- remote preflight (read-only) ---'
            Write-Host $preflight
            Report-Check 'remote /api/health' ($preflight -match 'health=200') 'expect health=200'
        } catch {
            Report-Check 'SSH connectivity' $false $_.Exception.Message
        }
    }
}

Write-Host ''
if ($failures.Count -gt 0) {
    Write-Host ("Readiness check FAILED ({0} issue(s))." -f $failures.Count) -ForegroundColor Red
    exit 1
}
Write-Host 'All readiness checks passed.' -ForegroundColor Green
exit 0
