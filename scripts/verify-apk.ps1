#Requires -Version 7.6

[CmdletBinding()]
param(
    [string]$SdkPath,
    [string]$ApkPath
)

. "$PSScriptRoot\BuildSupport.ps1"
$context = getBuildContext -SdkPath $SdkPath
$previous = setBuildEnvironment -Context $context
try {
    $apk = if ($ApkPath) { (Resolve-Path -LiteralPath $ApkPath).Path } else { getReleaseApk -Context $context }
    $signature = & (Join-Path $context.Jdk 'bin\java.exe') --enable-native-access=ALL-UNNAMED -jar (Join-Path $context.BuildTools 'lib\apksigner.jar') verify --verbose --print-certs $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $signatureText = $signature -join "`n"
    # Public certificate fingerprint from MusicTag-v0.4.1.apk, not a private key or APK checksum.
    $expectedSigner = '28a567c40e5f7e21dab3194695b8c885c82b62f186438b1739546eeb7b958fd7'
    if ($signatureText -notmatch "certificate SHA-256 digest: $expectedSigner" -or $signatureText -notmatch 'Number of signers: 1') {
        throw 'Signing identity changed: APK must remain update-compatible with Music Tag 0.4.1.'
    }
    if ($signatureText -notmatch 'Verified using v2 scheme .*: true') { throw 'V2 signature is missing.' }
    $badging = & (Join-Path $context.BuildTools 'aapt2.exe') dump badging $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK manifest inspection failed.' }
    $badgingText = $badging -join "`n"
    $expectedVersion = [regex]::Escape($context.Version)
    if ($badgingText -notmatch "package: name='top.michubil.musictag' versionCode='$($context.VersionCode)' versionName='$expectedVersion'") { throw 'APK package, version code or version name is incorrect.' }
    if ($badgingText -notmatch "sdkVersion:'$($context.MinSdk)'") { throw "Expected minSdk $($context.MinSdk)." }
    if ($badgingText -match '(?m)^application-debuggable') { throw 'Release APK must not be debuggable.' }
    if ($badgingText -match (getDeniedStoragePermissionPattern)) {
        throw 'SAF must not request broad storage or media permissions.'
    }
    Write-Output 'SAF permissions and existing Music Tag signing identity: PASS'
    invokeCheckedNative -Executable (Join-Path $context.BuildTools 'zipalign.exe') -Arguments @('-c', '4', $apk)

    $archive = [IO.Compression.ZipFile]::OpenRead($apk)
    try {
        foreach ($notice in @('LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md')) {
            if (-not $archive.GetEntry("assets/licenses/androidgui/$notice")) { throw "Missing packaged license/attribution: $notice" }
        }
        foreach ($entry in $archive.Entries) {
            $name = $entry.FullName
            if ($name -match '(^|/)\.local-signing/|(^|/)([^/]+\.(keystore|jks)|keystore\.properties|\.env(\.[^/]*)?|DebugProbesKt\.bin)$') {
                throw "Sensitive or debug file must not be packaged: $name"
            }
            if ($name -match '^lib/' -and $name -notmatch '^lib/arm64-v8a/') { throw "Unsupported ABI: $name" }
            if ($name -match '\.so$' -and $name -notmatch '^lib/arm64-v8a/[^/]+\.so$') { throw "Native library outside the ARM64 directory: $name" }
        }
    } finally {
        $archive.Dispose()
    }
    Write-Output 'Signature, ARM64-only, ZIP alignment, and sensitive-file exclusion: PASS'
} finally {
    restoreBuildEnvironment -Previous $previous
}
