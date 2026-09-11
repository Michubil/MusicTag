#Requires -Version 7.6

[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\BuildSupport.ps1"
$storageProjectRoot = Split-Path -Parent $PSScriptRoot
$storageSourceRoot = Join-Path $storageProjectRoot 'app\src\main'
$storageManifest = Join-Path $storageSourceRoot 'AndroidManifest.xml'
$storagePermissions = Get-DeniedStoragePermissionPattern
if (Select-String -LiteralPath $storageManifest -Pattern $storagePermissions) {
    throw 'SAF must not request broad storage or media permissions.'
}
$storageSources = @(Get-ChildItem -LiteralPath $storageSourceRoot -Recurse -File -Filter '*.kt')
foreach ($storageFile in $storageSources) {
    $storagePattern = '\b(ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION|ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION|isExternalStorageManager|getExternalStorageDirectory|getExternalStoragePublicDirectory|directoryAncestors|RequestStoragePermission)\b'
    $storagePattern += '|\bMediaStore\b|file://|/storage/emulated|/sdcard|\b(getExternalFilesDir|getExternalFilesDirs|getExternalCacheDir|getExternalCacheDirs|requestLegacyExternalStorage)\b'
    if ($storageFile.FullName -match '[\\/]ui[\\/]') {
        $storagePattern += '|\bjava\.(io\.(File|FileInputStream|FileOutputStream|RandomAccessFile)|nio\.file)\b|\b(absolutePath|canonicalPath|listFiles|walkTopDown|walkBottomUp)\b'
    }
    $storageHits = Select-String -LiteralPath $storageFile.FullName -Pattern $storagePattern -CaseSensitive
    if ($storageHits) { throw ("Legacy filesystem access must not bypass SAF: " + ($storageHits -join [Environment]::NewLine)) }
}
Write-Output 'SAF permission and file-browser boundary: PASS'
