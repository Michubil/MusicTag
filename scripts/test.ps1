#Requires -Version 7.6

[CmdletBinding()]
param([string]$SdkPath, [switch]$MatchingOnly)

. "$PSScriptRoot\BuildSupport.ps1"
& "$PSScriptRoot\test-storage-boundary.ps1"
$context = getBuildContext -SdkPath $SdkPath
$previous = setBuildEnvironment -Context $context
Push-Location -LiteralPath $context.Root
try {
    $modules = @('app')
    $tasks = @(':app:externalNativeBuildDebug', ':app:testDebugUnitTest')
    if ($MatchingOnly) {
        foreach ($filter in @(
            'data.match.*', 'data.network.*', 'data.fingerprint.*', 'data.model.*', 'data.lyrics.*',
            'data.AppPreferencesSourceTest', 'data.SourceSelectionsTest', 'data.storage.SafAudioCommitterTest',
            'data.LocalFileWorkTest', 'data.AudioMetadataReaderTest', 'ui.FileWorkTest',
            'data.id3.*', 'data.flac.*', 'data.wav.*'
        )) {
            $tasks += '--tests', "top.michubil.musictag.$filter"
        }
    } else {
        $modules += 'core/designsystem'
        $tasks += ':core:designsystem:testDebugUnitTest', ':app:lint', ':core:designsystem:lint'
    }
    invokeCheckedNative -Executable (Join-Path $context.Root 'gradlew.ps1') -Arguments $tasks

    foreach ($module in $modules) {
        $reports = Join-Path $context.Root "$module/build/test-results/testDebugUnitTest"
        $files = @(Get-ChildItem -LiteralPath $reports -Filter 'TEST-*.xml' -File -ErrorAction Stop)
        if ($files.Count -eq 0) { throw "Missing test results for $module." }
        $total = 0; $failed = 0; $errors = 0; $skipped = 0
        foreach ($file in $files) {
            [xml]$report = Get-Content -LiteralPath $file.FullName -Raw
            $suite = $report.testsuite
            $total += [int]$suite.tests
            $failed += [int]$suite.failures
            $errors += [int]$suite.errors
            $skipped += [int]$suite.skipped
        }
        if ($total -eq 0 -or $failed -gt 0 -or $errors -gt 0 -or $skipped -gt 0) {
            throw "Incomplete test results for ${module}: $total total, $failed failures, $errors errors, $skipped skipped."
        }
        Write-Output "${module}: $total passed; reports: $reports"
    }
} finally {
    Pop-Location
    restoreBuildEnvironment -Previous $previous
}
