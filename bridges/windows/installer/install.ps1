$ErrorActionPreference = 'Stop'

$target = Join-Path $env:LOCALAPPDATA 'ItemManagementPrintBridge'
$exe = Join-Path $target 'ItemManagementPrintBridge.exe'
$source = Join-Path $PSScriptRoot 'ItemManagementPrintBridge.exe'

New-Item -ItemType Directory -Force -Path $target | Out-Null
Get-Process -Name 'ItemManagementPrintBridge' -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 500
Copy-Item -Path $source -Destination $exe -Force

$runKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
New-Item -Path $runKey -Force | Out-Null
Set-ItemProperty -Path $runKey -Name 'ItemManagementPrintBridge' -Value ('"' + $exe + '" --autostart')

$programs = [Environment]::GetFolderPath('Programs')
try {
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut((Join-Path $programs 'Item Management Print Bridge.lnk'))
    $shortcut.TargetPath = $exe
    $shortcut.WorkingDirectory = $target
    $shortcut.Description = 'Silent receipt printing bridge for Item Management System'
    $shortcut.Save()
} catch {
    Write-Host "Could not create the Start Menu shortcut: $($_.Exception.Message)"
}

Start-Process -FilePath $exe
Write-Host ''
Write-Host 'Item Management Print Bridge is installed and running.'
Write-Host 'Look for the printer icon in the Windows system tray, open it and copy the bridge key.'
Write-Host 'Then paste the key in the store website under Settings > Printers.'
Write-Host ''
Write-Host 'Installed at:' $target
Write-Host 'Uninstall: close the tray app and delete this folder, then remove the ItemManagementPrintBridge value from HKCU\Software\Microsoft\Windows\CurrentVersion\Run.'
Start-Sleep -Seconds 6
