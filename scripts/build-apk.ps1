#Requires -Version 7.6

[CmdletBinding()]
param([string]$SdkPath, [switch]$FullNativeValidation, [string]$Mp3SamplePath)

. "$PSScriptRoot\BuildSupport.ps1"
$context = Get-BuildContext -SdkPath $SdkPath
& "$PSScriptRoot\test.ps1" -SdkPath $context.Sdk -Mp3SamplePath $Mp3SamplePath
$previous = Set-BuildEnvironment -Context $context
Push-Location -LiteralPath $context.Root
try {
    Invoke-CheckedNative -Executable (Join-Path $context.Root 'gradlew.ps1') -Arguments @(':app:assembleRelease')
    $builtApk = Get-ReleaseApk -Context $context
    & "$PSScriptRoot\verify-apk.ps1" -SdkPath $context.Sdk -ApkPath $builtApk -FullNativeValidation:$FullNativeValidation
    Write-Output "Release $($context.Version) ($($context.VersionCode))"
    Write-Output $builtApk
} finally {
    Pop-Location
    Restore-BuildEnvironment -Previous $previous
}
