#Requires -Version 7.6

[CmdletBinding()]
param([string]$SdkPath, [switch]$MatchingOnly)

. "$PSScriptRoot\BuildSupport.ps1"
& "$PSScriptRoot\test-storage-boundary.ps1"
$context = getBuildContext -SdkPath $SdkPath
$previous = setBuildEnvironment -Context $context
Push-Location -LiteralPath $context.Root
try {
    if ($MatchingOnly) {
        $tasks = @(':app:externalNativeBuildDebug', ':app:testDebugUnitTest',
            '--tests', 'top.michubil.musictag.data.match.*',
            '--tests', 'top.michubil.musictag.data.network.*',
            '--tests', 'top.michubil.musictag.data.model.*',
            '--tests', 'top.michubil.musictag.data.lyrics.*',
            '--tests', 'top.michubil.musictag.data.AppPreferencesSourceTest',
            '--tests', 'top.michubil.musictag.data.SourceSelectionsTest',
            '--tests', 'top.michubil.musictag.data.storage.SafAudioCommitterTest',
            '--tests', 'top.michubil.musictag.data.LocalFileWorkTest',
            '--tests', 'top.michubil.musictag.data.AudioMetadataReaderTest',
            '--tests', 'top.michubil.musictag.ui.FileWorkTest',
            '--tests', 'top.michubil.musictag.data.id3.*',
            '--tests', 'top.michubil.musictag.data.flac.*',
            '--tests', 'top.michubil.musictag.data.wav.*')
    } else {
        $tasks = @(':app:testDebugUnitTest', ':core:designsystem:testDebugUnitTest',
            ':app:externalNativeBuildDebug', ':app:lint', ':core:designsystem:lint')
    }
    invokeCheckedNative -Executable (Join-Path $context.Root 'gradlew.ps1') -Arguments $tasks
    if ($MatchingOnly) {
        writeTestSummary -Context $context -Modules @('app')
    } else {
        writeTestSummary -Context $context
    }
} finally {
    Pop-Location
    restoreBuildEnvironment -Previous $previous
}
