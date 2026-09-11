#Requires -Version 7.6

[CmdletBinding()]
param([string]$SdkPath, [string]$Mp3SamplePath)

. "$PSScriptRoot\BuildSupport.ps1"
Test-BuildScriptSyntax
& "$PSScriptRoot\test-design-boundary.ps1"
& "$PSScriptRoot\test-storage-boundary.ps1"
$context = Get-BuildContext -SdkPath $SdkPath
$sample = $null
$sampleHash = $null
if ($Mp3SamplePath) {
    $sample = Get-Item -LiteralPath $Mp3SamplePath -ErrorAction Stop
    if ($sample.PSIsContainer -or $sample.Extension -ine '.mp3') { throw 'MP3 sample must be an existing .mp3 file.' }
    $sampleHash = (Get-FileHash -LiteralPath $sample.FullName -Algorithm SHA256).Hash
}
$previous = Set-BuildEnvironment -Context $context
Push-Location -LiteralPath $context.Root
try {
    $tasks = @(':app:testDebugUnitTest', ':core:designsystem:testDebugUnitTest', ':app:lint', ':core:designsystem:lint')
    if ($sample) { $tasks += "-PmusicTagMp3Sample=$($sample.FullName)" }
    Invoke-CheckedNative -Executable (Join-Path $context.Root 'gradlew.ps1') -Arguments $tasks
    Write-TestSummary -Context $context -RequireMp3Sample:([bool]$sample)
} finally {
    Pop-Location
    Restore-BuildEnvironment -Previous $previous
    if ($sample) {
        if ((Get-FileHash -LiteralPath $sample.FullName -Algorithm SHA256).Hash -ne $sampleHash) {
            throw 'MP3 sample changed during validation.'
        }
        Write-Output 'MP3 source SHA-256 unchanged: PASS'
    }
}
