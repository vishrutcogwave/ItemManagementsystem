#requires -Version 5.1
# Shared helpers for IMS deployment on Windows. No secrets are stored or printed.

Set-StrictMode -Version Latest

function Get-ImsDeployConfig {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Deployment config not found: $Path (copy deploy.config.example.psd1 and fill it in)"
    }
    $cfg = Import-PowerShellDataFile -LiteralPath $Path

    foreach ($key in @('SshHost', 'SshPort', 'SshUser', 'SshKeyPath', 'RemoteIncoming',
            'RemoteBackupDir', 'SiteUrls', 'ApiHealthUrl', 'LocalHistoryFile')) {
        if (-not $cfg.ContainsKey($key)) { throw "Config missing key: $key" }
    }
    if ([string]::IsNullOrWhiteSpace($cfg.SshHost) -or $cfg.SshHost -eq 'CHANGE_ME') {
        throw "Config SshHost is not set"
    }
    if ($cfg.SshPort -lt 1 -or $cfg.SshPort -gt 65535) { throw "Config SshPort is invalid" }
    if ([string]::IsNullOrWhiteSpace($cfg.SshUser)) { throw "Config SshUser is empty" }
    if (-not (Test-Path -LiteralPath $cfg.SshKeyPath -PathType Leaf)) {
        throw "SSH key not found: $($cfg.SshKeyPath)"
    }
    foreach ($pathKey in @('RemoteIncoming', 'RemoteBackupDir')) {
        if (-not $cfg[$pathKey].StartsWith('/')) { throw "Config $pathKey must be an absolute path" }
    }
    if ($cfg.SiteUrls -isnot [array] -or $cfg.SiteUrls.Count -lt 1) { throw "Config SiteUrls is invalid" }
    return $cfg
}

function Get-ImsRepoRoot {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { throw "Repository path not found: $Path" }
    $root = (Resolve-Path -LiteralPath $Path).Path
    $null = & git -C $root rev-parse --show-toplevel 2>$null
    if ($LASTEXITCODE -ne 0) { throw "Not a git repository: $root" }
    return $root
}

function Get-ImsGitCommit {
    param([Parameter(Mandatory = $true)][string]$RepoRoot)
    $full = (& git -C $RepoRoot rev-parse HEAD).Trim()
    if ($full -notmatch '^[0-9a-f]{40}$') { throw "Could not determine git commit" }
    $short = (& git -C $RepoRoot rev-parse --short=7 HEAD).Trim()
    return [pscustomobject]@{ Full = $full; Short = $short }
}

function Assert-ImsGitClean {
    param([Parameter(Mandatory = $true)][string]$RepoRoot)
    $status = & git -C $RepoRoot status --porcelain
    if ($LASTEXITCODE -ne 0) { throw "git status failed" }
    if ($status) {
        $preview = ($status | Select-Object -First 15) -join "`n"
        throw "Working tree is dirty. Commit or resolve these changes before deploying:`n$preview"
    }
}

function Get-ImsFileSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function New-ImsManifest {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('backend', 'frontend')][string]$Type,
        [Parameter(Mandatory = $true)][string]$Commit,
        [Parameter(Mandatory = $true)][string]$ShortCommit,
        [Parameter(Mandatory = $true)][string]$ArtifactPath,
        [Parameter(Mandatory = $true)][string]$ManifestPath
    )

    if ($Commit -notmatch '^[0-9a-f]{40}$') { throw "Commit must be 40 hex characters" }
    if ($ShortCommit -notmatch '^[0-9a-f]{7,12}$') { throw "Short commit is invalid" }
    if (-not $Commit.StartsWith($ShortCommit)) { throw "Short commit is not a prefix of commit" }

    $artifactName = if ($Type -eq 'backend') { 'item-management.jar' } else { 'frontend.tar.gz' }
    $sha = Get-ImsFileSha256 -Path $ArtifactPath
    $size = (Get-Item -LiteralPath $ArtifactPath).Length
    $builtAt = [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")

    $lines = @(
        'schema=1'
        "type=$Type"
        "commit=$Commit"
        "short_commit=$ShortCommit"
        "built_at_utc=$builtAt"
        "artifact=$artifactName"
        "sha256=$sha"
        "size=$size"
    )
    [System.IO.File]::WriteAllText($ManifestPath, (($lines -join "`n") + "`n"), [System.Text.Encoding]::ASCII)

    return [pscustomobject]@{
        Type      = $Type
        Commit    = $Commit
        Short     = $ShortCommit
        Artifact  = $artifactName
        Sha256    = $sha
        Size      = $size
        BuiltAt   = $builtAt
        Manifest  = $ManifestPath
        Source    = $ArtifactPath
    }
}

function Read-ImsManifest {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) { throw "Manifest not found: $Path" }
    $result = @{}
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line -notmatch '^([a-z0-9_]+)=(.*)$') { throw "Malformed manifest line" }
        $key = $Matches[1]
        if ($result.ContainsKey($key)) { throw "Duplicate manifest key: $key" }
        $result[$key] = $Matches[2]
    }
    foreach ($key in @('schema', 'type', 'commit', 'short_commit', 'built_at_utc', 'artifact', 'sha256', 'size')) {
        if (-not $result.ContainsKey($key)) { throw "Manifest missing key: $key" }
    }
    if ($result['schema'] -ne '1') { throw "Unsupported manifest schema" }
    if ($result['commit'] -notmatch '^[0-9a-f]{40}$') { throw "Manifest commit invalid" }
    if ($result['short_commit'] -notmatch '^[0-9a-f]{7,12}$') { throw "Manifest short commit invalid" }
    if (-not $result['commit'].StartsWith($result['short_commit'])) { throw "Manifest commit prefix mismatch" }
    if ($result['sha256'] -notmatch '^[0-9a-f]{64}$') { throw "Manifest sha256 invalid" }
    if ($result['size'] -notmatch '^\d{1,12}$') { throw "Manifest size invalid" }
    return $result
}

function Test-ImsArchivePath {
    param([Parameter(Mandatory = $true)][string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $false }
    $normalized = $Path -replace '\\', '/'
    if ($normalized.StartsWith('/')) { return $false }
    if ($normalized -match '(^|/)\.\.(/|$)') { return $false }
    return $true
}

function Test-ImsFrontendDist {
    param([Parameter(Mandatory = $true)][string]$DistPath)

    $index = Join-Path $DistPath 'index.html'
    if (-not (Test-Path -LiteralPath $index -PathType Leaf)) { throw "dist/index.html is missing" }
    $assets = Join-Path $DistPath 'assets'
    if (-not (Test-Path -LiteralPath $assets -PathType Container)) { throw "dist/assets is missing" }
    $assetFiles = @(Get-ChildItem -LiteralPath $assets -Recurse -File)
    if ($assetFiles.Count -eq 0) { throw "dist/assets is empty" }
    $envFiles = @(Get-ChildItem -LiteralPath $DistPath -Recurse -File -Force |
        Where-Object { $_.Name -match '^\.env' })
    if ($envFiles.Count -gt 0) { throw "dist contains environment files; refusing to upload" }
    return $assetFiles.Count
}

function Invoke-ImsBuildFrontend {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [switch]$SkipInstall
    )

    Push-Location -LiteralPath $RepoRoot
    try {
        # Native command output must not leak into the function's return value;
        # callers expect the dist path as the only result.
        if (-not $SkipInstall) {
            & npm ci 2>&1 | Out-Host
            if ($LASTEXITCODE -ne 0) { throw "npm ci failed" }
        }
        & npm run lint 2>&1 | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "npm run lint failed" }
        & npm run build 2>&1 | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "npm run build failed" }
    } finally {
        Pop-Location
    }
    $dist = Join-Path $RepoRoot 'dist'
    $count = Test-ImsFrontendDist -DistPath $dist
    Write-Host "Frontend build validated: $count asset files"
    return $dist
}

function New-ImsFrontendArchive {
    param(
        [Parameter(Mandatory = $true)][string]$DistPath,
        [Parameter(Mandatory = $true)][string]$OutputPath
    )

    $tar = (Get-Command tar -ErrorAction Stop).Source
    if (Test-Path -LiteralPath $OutputPath) { Remove-Item -LiteralPath $OutputPath -Force }
    $tarVersion = (& $tar --version 2>&1 | Out-String)
    $forceLocal = $tarVersion -match 'GNU tar'
    $tarArgs = @()
    if ($forceLocal) { $tarArgs += '--force-local' }
    $tarArgs += @('-czf', $OutputPath, '-C', $DistPath, '.')
    & $tar @tarArgs
    if ($LASTEXITCODE -ne 0) { throw "tar failed to create the frontend archive" }

    $listArgs = @()
    if ($forceLocal) { $listArgs += '--force-local' }
    $listArgs += @('-tzf', $OutputPath)
    $entries = @(& $tar @listArgs)
    if ($LASTEXITCODE -ne 0) { throw "tar failed to list the frontend archive" }
    if ($entries.Count -eq 0 -or $entries.Count -gt 5000) { throw "Frontend archive entry count is invalid" }
    foreach ($entry in $entries) {
        if (-not (Test-ImsArchivePath -Path $entry)) { throw "Unsafe archive entry: $entry" }
    }
    $hasIndex = $false
    $hasAssets = $false
    foreach ($entry in $entries) {
        $clean = $entry -replace '^\./', ''
        if ($clean -eq 'index.html') { $hasIndex = $true }
        if ($clean -like 'assets/*') { $hasAssets = $true }
    }
    if (-not $hasIndex) { throw "Frontend archive has no root index.html" }
    if (-not $hasAssets) { throw "Frontend archive has no assets" }
    return $entries.Count
}

function Invoke-ImsBuildBackend {
    param([Parameter(Mandatory = $true)][string]$RepoRoot)

    $backend = Join-Path $RepoRoot 'backend'
    Push-Location -LiteralPath $backend
    try {
        # Keep Gradle output out of the function's return value; the callers
        # assign the result directly to the jar path.
        & .\gradlew.bat clean test bootJar 2>&1 | Out-Host
        if ($LASTEXITCODE -ne 0) { throw "Gradle build/tests failed" }
    } finally {
        Pop-Location
    }

    $jars = @(Get-ChildItem -LiteralPath (Join-Path $backend 'build\libs') -Filter '*.jar' -File |
        Where-Object { $_.Name -notlike '*-plain.jar' })
    if ($jars.Count -ne 1) { throw "Expected exactly one boot jar, found $($jars.Count)" }
    return $jars[0].FullName
}

function Test-ImsBackendJar {
    param(
        [Parameter(Mandatory = $true)][string]$JarPath,
        [Parameter(Mandatory = $true)][string]$Commit
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $names = @($zip.Entries | ForEach-Object { $_.FullName })
        if ($names.Count -gt 20000) { throw "Jar contains too many entries" }
        if (-not ($names | Where-Object { $_ -like 'BOOT-INF/classes/*' })) { throw "Not a Spring Boot fat jar" }
        if (-not ($names | Where-Object { $_ -like 'BOOT-INF/lib/*' })) { throw "Jar has no bundled libraries" }
        if (-not ($names -contains 'META-INF/MANIFEST.MF')) { throw "Jar manifest missing" }

        $infoEntry = $zip.GetEntry('META-INF/build-info.properties')
        if ($null -eq $infoEntry) {
            $infoEntry = $zip.GetEntry('BOOT-INF/classes/META-INF/build-info.properties')
        }
        if ($null -eq $infoEntry) { throw "build-info.properties missing from jar" }
        $reader = New-Object System.IO.StreamReader($infoEntry.Open())
        try { $buildInfo = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $commitLine = ($buildInfo -split "`n" | Where-Object { $_ -match '^(build\.)?git\.commit=' } | Select-Object -First 1)
        if (-not $commitLine) { throw "Jar has no embedded git commit" }
        $embedded = ($commitLine -replace '^(build\.)?git\.commit=', '').Trim()
        if (-not $Commit.StartsWith($embedded)) {
            throw "Jar commit ($embedded) does not match HEAD ($Commit)"
        }

        $migrations = @($names | Where-Object { $_ -match '^BOOT-INF/classes/db/migration/V[0-9][0-9._]*__.*\.sql$' })
        if ($migrations.Count -eq 0) { throw "Jar contains no Flyway migrations" }
        return [pscustomobject]@{ EmbeddedCommit = $embedded; Migrations = $migrations; BuildInfo = $buildInfo }
    } finally {
        $zip.Dispose()
    }
}

function Invoke-ImsSsh {
    param(
        [Parameter(Mandatory = $true)]$Config,
        [Parameter(Mandatory = $true)][string]$Command
    )

    $sshArgs = @(
        '-i', $Config.SshKeyPath,
        '-p', [string]$Config.SshPort,
        '-o', 'IdentitiesOnly=yes',
        '-o', 'ConnectTimeout=15',
        '-o', 'ServerAliveInterval=30'
    )
    $sshArgs += "$($Config.SshUser)@$($Config.SshHost)"
    $sshArgs += $Command
    & ssh @sshArgs
    if ($LASTEXITCODE -ne 0) { throw "ssh command failed (exit $LASTEXITCODE)" }
}

function Send-ImsArtifact {
    param(
        [Parameter(Mandatory = $true)]$Config,
        [Parameter(Mandatory = $true)][string]$LocalPath,
        [Parameter(Mandatory = $true)][string]$RemoteName
    )

    if ($RemoteName -notmatch '^[A-Za-z0-9._-]+$') { throw "Invalid remote artifact name" }
    $scpArgs = @(
        '-i', $Config.SshKeyPath,
        '-P', [string]$Config.SshPort,
        '-o', 'IdentitiesOnly=yes',
        '-o', 'ConnectTimeout=15'
    )
    $scpArgs += $LocalPath
    $scpArgs += "$($Config.SshUser)@$($Config.SshHost):$($Config.RemoteIncoming)/$RemoteName"
    & scp @scpArgs
    if ($LASTEXITCODE -ne 0) { throw "scp upload failed for $RemoteName" }
}

function Assert-ImsRemoteSha {
    param(
        [Parameter(Mandatory = $true)]$Config,
        [Parameter(Mandatory = $true)][string]$RemoteName,
        [Parameter(Mandatory = $true)][string]$ExpectedSha
    )

    if ($RemoteName -notmatch '^[A-Za-z0-9._-]+$') { throw "Invalid remote artifact name" }
    $remote = "$($Config.RemoteIncoming)/$RemoteName"
    $output = Invoke-ImsSsh -Config $Config -Command "sha256sum '$remote'"
    $actual = ($output -split '\s+')[0].Trim().ToLowerInvariant()
    if ($actual -ne $ExpectedSha.ToLowerInvariant()) {
        throw "Remote checksum mismatch for $RemoteName"
    }
}

function Get-ImsRemotePreflight {
    param([Parameter(Mandatory = $true)]$Config)

    $command = @(
        'set -e',
        'systemctl is-active item-management || true',
        'ss -ltn | grep ":8080 " || true',
        'curl -s -o /dev/null -w "health=%{http_code}\n" --max-time 10 http://127.0.0.1:8080/api/health || true',
        'sha256sum /opt/item-management/backend/item-management.jar 2>/dev/null || true',
        'ls -l /var/www/item-management/index.html 2>/dev/null || true',
        'df -Pk /var | tail -1'
    ) -join '; '
    return (Invoke-ImsSsh -Config $Config -Command $command)
}

function Write-ImsDeploymentRecord {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][string]$RelativeHistoryPath,
        [Parameter(Mandatory = $true)][hashtable]$Record
    )

    $historyPath = Join-Path $RepoRoot $RelativeHistoryPath
    $dir = Split-Path -Parent $historyPath
    if (-not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    $record['ts'] = [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    $json = ($record | ConvertTo-Json -Compress)
    Add-Content -LiteralPath $historyPath -Value $json -Encoding UTF8
    return $historyPath
}

function Test-ImsHttpOk {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [int]$ExpectedStatus = 200,
        [int]$Retries = 3,
        [int]$DelaySeconds = 3
    )

    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $lastCode = 0
    $lastError = ''
    for ($attempt = 1; $attempt -le $Retries; $attempt++) {
        try {
            $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -MaximumRedirection 5 -TimeoutSec 20
            $lastCode = [int]$response.StatusCode
            if ($lastCode -eq $ExpectedStatus) {
                return [pscustomobject]@{ Ok = $true; Code = $lastCode; Error = '' }
            }
        } catch {
            $lastError = $_.Exception.Message
            if ($_.Exception.Response) {
                try { $lastCode = [int]$_.Exception.Response.StatusCode } catch { $lastCode = 0 }
            }
        }
        if ($attempt -lt $Retries) { Start-Sleep -Seconds $DelaySeconds }
    }
    return [pscustomobject]@{ Ok = $false; Code = $lastCode; Error = $lastError }
}

function Get-ImsJavaVersion {
    # java -version writes to stderr; capturing it with $ErrorActionPreference=Stop
    # would raise NativeCommandError, so relax the preference for this call only.
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { return '' }
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        return (& java -version 2>&1 | Out-String).Trim()
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Assert-ImsPrerequisites {
    param([Parameter(Mandatory = $true)]$Config)

    foreach ($tool in @('git', 'node', 'npm', 'java', 'ssh', 'scp', 'tar')) {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
            throw "Required tool not found on PATH: $tool"
        }
    }
    $javaVersion = Get-ImsJavaVersion
    if ($javaVersion -notmatch 'version "17') {
        throw "Java 17 is required; found: $($javaVersion.Trim())"
    }
    if (-not (Test-Path -LiteralPath $Config.SshKeyPath)) {
        throw "SSH key missing: $($Config.SshKeyPath)"
    }
    return $true
}

Export-ModuleMember -Function *
