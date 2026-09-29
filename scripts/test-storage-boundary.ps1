#Requires -Version 7.6

[CmdletBinding()]
param()
. "$PSScriptRoot\BuildSupport.ps1"
$storageSourceRoot = Join-Path (Split-Path -Parent $PSScriptRoot) 'app\src\main'
$storageManifest = Join-Path $storageSourceRoot 'AndroidManifest.xml'
if (Select-String -LiteralPath $storageManifest -Pattern (getDeniedStoragePermissionPattern)) {
    throw 'SAF must not request broad storage or media permissions.'
}
$storagePattern = '\b(ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION|ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION|isExternalStorageManager|getExternalStorageDirectory|getExternalStoragePublicDirectory|directoryAncestors|RequestStoragePermission)\b'
$storagePattern += '|\bMediaStore\b|file://|/storage/emulated|/sdcard|\b(getExternalFilesDir|getExternalFilesDirs|getExternalCacheDir|getExternalCacheDirs|requestLegacyExternalStorage)\b'
$uiPattern = $storagePattern + '|\bjava\.(io\.(File|FileInputStream|FileOutputStream|RandomAccessFile)|nio\.file)\b|\b(absolutePath|canonicalPath|listFiles|walkTopDown|walkBottomUp)\b'
foreach ($storageFile in Get-ChildItem -LiteralPath $storageSourceRoot -Recurse -File -Filter '*.kt') {
    $pattern = if ($storageFile.FullName -match '[\\/]ui[\\/]') { $uiPattern } else { $storagePattern }
    $storageHits = Select-String -LiteralPath $storageFile.FullName -Pattern $pattern -CaseSensitive
    if ($storageHits) { throw ("Legacy filesystem access must not bypass SAF: " + ($storageHits -join [Environment]::NewLine)) }
}
Write-Output 'SAF permission and file-browser boundary: PASS'
