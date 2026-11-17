#requires -Version 5.1
<#
.SYNOPSIS
Read-only post-deployment health check for the IMS public endpoints.

.DESCRIPTION
Checks both public sites and the public API health endpoint, and confirms the
public version endpoint exposes only the application name and version. With
-ExpectedCommit it additionally reads the build time and git commit from the
platform-admin endpoint /api/platform/version; pass a platform administrator
bearer token via -AdminToken or the IMS_ADMIN_TOKEN environment variable.
Makes no changes.
#>
[CmdletBinding()]
param(
    [string]$ConfigPath = (Join-Path $PSScriptRoot 'deploy.config.psd1'),
    [string]$ExpectedCommit,
    [string]$AdminToken = $env:IMS_ADMIN_TOKEN,
    [switch]$Json
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Import-Module (Join-Path $PSScriptRoot 'Deploy-Common.psm1') -Force
$cfg = Get-ImsDeployConfig -Path $ConfigPath

$results = New-Object System.Collections.Generic.List[object]
$failed = $false

foreach ($site in $cfg.SiteUrls) {
    $check = Test-ImsHttpOk -Uri ($site.TrimEnd('/') + '/') -Retries 3
    $results.Add([pscustomobject]@{ check = 'site'; uri = $site; ok = $check.Ok; code = $check.Code; error = $check.Error })
    if (-not $check.Ok) { $failed = $true }
}

$api = Test-ImsHttpOk -Uri $cfg.ApiHealthUrl -Retries 3
$results.Add([pscustomobject]@{ check = 'api-health'; uri = $cfg.ApiHealthUrl; ok = $api.Ok; code = $api.Code; error = $api.Error })
if (-not $api.Ok) { $failed = $true }

$publicVersionUrl = $cfg.ApiHealthUrl -replace '/health$', '/version'
try {
    $publicVersion = Invoke-RestMethod -Uri $publicVersionUrl -TimeoutSec 20 -UseBasicParsing
    $propertyNames = @($publicVersion.PSObject.Properties.Name)
    $ok = ($propertyNames -contains 'version') -and
        ($propertyNames -notcontains 'gitCommit') -and
        ($propertyNames -notcontains 'javaVersion') -and
        ($propertyNames -notcontains 'buildTime')
    $detail = 'fields=' + ($propertyNames -join ',')
    $results.Add([pscustomobject]@{ check = 'public-version'; uri = $publicVersionUrl; ok = $ok; code = 200; error = $detail })
    if (-not $ok) { $failed = $true }
} catch {
    $results.Add([pscustomobject]@{ check = 'public-version'; uri = $publicVersionUrl; ok = $false; code = 0; error = $_.Exception.Message })
    $failed = $true
}

if ($ExpectedCommit) {
    $versionUrl = $cfg.ApiHealthUrl -replace '/health$', '/platform/version'
    if (-not $AdminToken) {
        $results.Add([pscustomobject]@{ check = 'runtime-commit'; uri = $versionUrl; ok = $false; code = 0; error = 'pass -AdminToken or set IMS_ADMIN_TOKEN to verify the deployed commit' })
        $failed = $true
    } else {
        try {
            $headers = @{ Authorization = "Bearer $AdminToken" }
            $version = Invoke-RestMethod -Uri $versionUrl -TimeoutSec 20 -UseBasicParsing -Headers $headers
            $reported = [string]$version.gitCommit
            $ok = $reported.StartsWith($ExpectedCommit.Substring(0, [Math]::Min(7, $ExpectedCommit.Length)))
            $results.Add([pscustomobject]@{ check = 'runtime-commit'; uri = $versionUrl; ok = $ok; code = 200; error = "reported=$reported buildTime=$($version.buildTime)" })
            if (-not $ok) { $failed = $true }
        } catch {
            $results.Add([pscustomobject]@{ check = 'runtime-commit'; uri = $versionUrl; ok = $false; code = 0; error = $_.Exception.Message })
            $failed = $true
        }
    }
}

if ($Json) {
    $results | ConvertTo-Json -Depth 4
} else {
    foreach ($result in $results) {
        $label = if ($result.ok) { 'PASS' } else { 'FAIL' }
        Write-Host ("{0}  {1}  HTTP {2}  {3}" -f $label, $result.check, $result.code, $result.uri)
        if ($result.error) { Write-Host "      $($result.error)" }
    }
}

if ($failed) { exit 1 }
exit 0
