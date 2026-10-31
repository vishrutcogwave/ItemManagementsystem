param(
    [string]$Runtime = 'win-x64'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$project = Join-Path $root 'ItemManagementPrintBridge\ItemManagementPrintBridge.csproj'
$publish = Join-Path $PSScriptRoot 'publish'
$dist = Join-Path $root 'dist'
$installerExe = Join-Path $dist 'ItemManagementPrintBridgeSetup.exe'
$plainExe = Join-Path $dist 'ItemManagementPrintBridge.exe'

New-Item -ItemType Directory -Force -Path $publish, $dist | Out-Null
Remove-Item (Join-Path $publish '*') -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "Publishing self-contained bridge for $Runtime..."
dotnet publish $project `
    -c Release `
    -r $Runtime `
    --self-contained true `
    -p:PublishSingleFile=true `
    -p:IncludeNativeLibrariesForSelfExtract=true `
    -p:EnableCompressionInSingleFile=true `
    -p:DebugType=None `
    -p:DebugSymbols=false `
    -o $publish
if ($LASTEXITCODE -ne 0) { throw 'dotnet publish failed.' }

$built = Join-Path $publish 'ItemManagementPrintBridge.exe'
if (-not (Test-Path $built)) { throw "Published exe not found at $built." }

Copy-Item $built (Join-Path $PSScriptRoot 'ItemManagementPrintBridge.exe') -Force
Copy-Item $built $plainExe -Force

$sedPath = Join-Path $PSScriptRoot 'ItemManagementPrintBridge.sed'
$sed = @"
[Version]
Class=IEXPRESS
SEDVersion=3
[Options]
PackagePurpose=InstallApp
ShowInstallProgramWindow=0
HideExtractAnimation=1
UseLongFileName=1
InsideCompressed=0
CAB_FixedSize=0
CAB_ResvCodeSigning=0
RebootMode=N
InstallPrompt=
DisplayLicense=
FinishMessage=Item Management Print Bridge is installed. Use the printer icon in the system tray to copy the bridge key, then paste it in Settings - Printers.
TargetName=$installerExe
FriendlyName=Item Management Print Bridge
AppLaunched=Install.cmd
PostInstallCmd=<None>
AdminQuietInstCmd=
UserQuietInstCmd=
SourceFiles=SourceFiles
[Strings]
FILE0="ItemManagementPrintBridge.exe"
FILE1="Install.cmd"
FILE2="install.ps1"
FILE3="README.txt"
[SourceFiles]
SourceFiles0=$PSScriptRoot\
[SourceFiles0]
%FILE0%=
%FILE1%=
%FILE2%=
%FILE3%=
"@
Set-Content -Path $sedPath -Value $sed -Encoding ASCII

Write-Host 'Building installer with IExpress...'
Remove-Item $installerExe -Force -ErrorAction SilentlyContinue
$iexpress = Join-Path $env:SystemRoot 'System32\iexpress.exe'
$process = Start-Process -FilePath $iexpress -ArgumentList @('/N', '/Q', $sedPath) -PassThru -Wait
if ($process.ExitCode -ne 0) { throw "IExpress failed with exit code $($process.ExitCode)." }
if (-not (Test-Path $installerExe)) { throw "Installer was not created at $installerExe." }

$sizeMb = [math]::Round((Get-Item $installerExe).Length / 1MB, 1)
Write-Host "Installer ready: $installerExe ($sizeMb MB)"
Write-Host "Portable exe:   $plainExe"
