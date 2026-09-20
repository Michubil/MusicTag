#Requires -Version 7.6

[CmdletBinding()]
param([string]$SdkPath)

. "$PSScriptRoot\BuildSupport.ps1"
$context = getBuildContext -SdkPath $SdkPath
$signingKey = $env:MUSICTAG_KEYSTORE_PATH
if (-not $signingKey) { $signingKey = Join-Path $env:USERPROFILE '.android/debug.keystore' }
if (-not (Test-Path -LiteralPath $signingKey -PathType Leaf)) {
    throw 'Existing Music Tag signing key is missing. Restore it before packaging.'
}
$previous = setBuildEnvironment -Context $context
Push-Location -LiteralPath $context.Root
try {
    invokeCheckedNative -Executable (Join-Path $context.Root 'gradlew.ps1') -Arguments @(':app:assembleRelease')
    $builtApk = getReleaseApk -Context $context
    & "$PSScriptRoot\verify-apk.ps1" -SdkPath $context.Sdk -ApkPath $builtApk
    Write-Output "Release $($context.Version) ($($context.VersionCode))"
    Write-Output $builtApk
} finally {
    Pop-Location
    restoreBuildEnvironment -Previous $previous
}
