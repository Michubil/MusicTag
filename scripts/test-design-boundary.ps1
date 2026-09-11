#Requires -Version 7.6

[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sourceRoot = Join-Path $projectRoot 'app\src\main'
$files = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File | Where-Object { $_.Extension -in '.kt', '.java' })

# Inspect the entire app source set: UI outside ui/ must not bypass this gate.
$legacy = '\b(MusicTagTheme|MainTab|MainPage|BackHandler)\b|\bfun\s+(PageHeader|SettingCard|SettingsSection|Artwork|FileRow|SortDialog)\b'
$sharedVisuals = 'import\s+(androidx\.compose\.material(?:3)?\.|[^\r\n]*\.tokens\.)'
$featureVisuals = '\b[0-9]+(?:\.[0-9]+)?\.(dp|sp)\b|\b(MaterialTheme|RoundedCornerShape|PaddingValues|Color|TextStyle|BitmapFactory|startActivity)\b'
foreach ($file in $files) {
    if ($file.FullName -match '[\\/]core[\\/]designsystem[\\/]') {
        throw "The app must not contain a second Design System: $($file.FullName)"
    }
    $pattern = "$legacy|$sharedVisuals"
    if ($file.FullName -match '[\\/]ui[\\/]') { $pattern += "|$featureVisuals" }
    $hits = Select-String -LiteralPath $file.FullName -Pattern $pattern -CaseSensitive
    if ($hits) { throw ("App UI must use the Design System and Actions: " + ($hits -join [Environment]::NewLine)) }
}
$viewLayouts = @(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'res') -Recurse -File -Filter '*.xml' |
    Where-Object { $_.Directory.Name -match '^layout(?:-|$)' })
if ($viewLayouts.Count -gt 0) { throw ("Legacy View layouts are not allowed: " + ($viewLayouts.FullName -join ', ')) }
Write-Output 'Design System boundary and legacy GUI: PASS'