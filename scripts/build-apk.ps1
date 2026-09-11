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
    $deliveryDirectory = Join-Path $context.Root 'apk'
    New-Item -ItemType Directory -Path $deliveryDirectory -Force | Out-Null
    $deliveryApk = Join-Path $deliveryDirectory ([IO.Path]::GetFileName($builtApk))
    Copy-Item -LiteralPath $builtApk -Destination $deliveryApk -Force
    $builtHash = (Get-FileHash -LiteralPath $builtApk -Algorithm SHA256).Hash
    $deliveryHash = (Get-FileHash -LiteralPath $deliveryApk -Algorithm SHA256).Hash
    if ($deliveryHash -ne $builtHash) { throw 'Delivered APK differs from the verified build.' }
    Set-Content -LiteralPath "$deliveryApk.sha256" -Value "$deliveryHash  $([IO.Path]::GetFileName($deliveryApk))"
    Write-Output "Release $($context.Version) ($($context.VersionCode)); SHA-256: $deliveryHash"
    Write-Output $deliveryApk
} finally {
    Pop-Location
    Restore-BuildEnvironment -Previous $previous
}
