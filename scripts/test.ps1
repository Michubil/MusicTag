#Requires -Version 7.6

[CmdletBinding()]
param([string]$SdkPath)

. "$PSScriptRoot\BuildSupport.ps1"
assertBuildScriptSyntax
& "$PSScriptRoot\test-design-boundary.ps1"
& "$PSScriptRoot\test-storage-boundary.ps1"
$context = getBuildContext -SdkPath $SdkPath
$previous = setBuildEnvironment -Context $context
Push-Location -LiteralPath $context.Root
try {
    $tasks = @(':app:testDebugUnitTest', ':core:designsystem:testDebugUnitTest', ':app:lint', ':core:designsystem:lint')
    invokeCheckedNative -Executable (Join-Path $context.Root 'gradlew.ps1') -Arguments $tasks
    writeTestSummary -Context $context
} finally {
    Pop-Location
    restoreBuildEnvironment -Previous $previous
}
