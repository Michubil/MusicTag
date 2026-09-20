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
    # Public certificate fingerprint from MusicTag-v0.4.1.apk, not a private key or APK checksum.
    $expectedSigner = '28a567c40e5f7e21dab3194695b8c885c82b62f186438b1739546eeb7b958fd7'
    if (($signature -join "`n") -notmatch "certificate SHA-256 digest: $expectedSigner" -or ($signature -join "`n") -notmatch 'Number of signers: 1') {
        throw 'Signing identity changed: APK must remain update-compatible with Music Tag 0.4.1.'
    }
    if (($signature -join "`n") -notmatch 'Verified using v2 scheme .*: true') { throw 'V2 signature is missing.' }
    $badging = & (Join-Path $context.BuildTools 'aapt2.exe') dump badging $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK manifest inspection failed.' }
    $expectedVersion = [regex]::Escape($context.Version)
    if (($badging -join "`n") -notmatch "package: name='top.michubil.musictag' versionCode='$($context.VersionCode)' versionName='$expectedVersion'") { throw 'APK package, version code or version name is incorrect.' }
    if (($badging -join "`n") -notmatch "sdkVersion:'$($context.MinSdk)'") { throw "Expected minSdk $($context.MinSdk)." }
    if (($badging -join "`n") -match '(?m)^application-debuggable') { throw 'Release APK must not be debuggable.' }
    if (($badging -join "`n") -match (getDeniedStoragePermissionPattern)) {
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
            if ($name -match '(^|/)(scripts|apk|docs|\.jj|\.git|\.local-signing|\.gradle|\.kotlin|\.android-sdk)/|\.(ps1|bat|cmd|sh|keystore|jks)$|(^|/)DebugProbesKt\.bin$') {
                throw "Development or signing file must not be packaged: $name"
            }
            if ($name -match '^lib/' -and $name -notmatch '^lib/arm64-v8a/') { throw "Unsupported ABI: $name" }
            if ($name -match '\.so$' -and $name -notmatch '^lib/arm64-v8a/[^/]+\.so$') { throw "Native library outside the ARM64 directory: $name" }
            if ($name -match '\.so$') {
                if ($entry.Length -gt 64MB) { throw "Native library exceeds validation size limit: $name" }
                $stream = $entry.Open()
                $memory = [IO.MemoryStream]::new()
                try { $stream.CopyTo($memory); $bytes = $memory.ToArray() } finally { $stream.Dispose(); $memory.Dispose() }
                if ($bytes.Length -lt 64 -or [BitConverter]::ToUInt32($bytes, 0) -ne 0x464C457F -or $bytes[4] -ne 2 -or $bytes[5] -ne 1 -or [BitConverter]::ToUInt16($bytes, 18) -ne 183) {
                    throw "Expected a little-endian ELF64 AArch64 library: $name"
                }
                $table = [BitConverter]::ToUInt64($bytes, 32)
                $size = [BitConverter]::ToUInt16($bytes, 54)
                $count = [BitConverter]::ToUInt16($bytes, 56)
                if ($size -lt 56 -or $table + [uint64]$size * $count -gt [uint64]$bytes.Length) { throw "Malformed ELF program headers: $name" }
                $loadSegments = 0
                for ($index = 0; $index -lt $count; $index++) {
                    $offset = [int]($table + [uint64]$index * $size)
                    if ([BitConverter]::ToUInt32($bytes, $offset) -eq 1) {
                        $loadSegments++
                        $alignment = [BitConverter]::ToUInt64($bytes, $offset + 48)
                        $fileOffset = [BitConverter]::ToUInt64($bytes, $offset + 8)
                        $address = [BitConverter]::ToUInt64($bytes, $offset + 16)
                        if ($alignment -gt 1 -and (($alignment -band ($alignment - 1)) -ne 0 -or ($fileOffset % $alignment) -ne ($address % $alignment))) {
                            throw "Invalid ELF LOAD segment alignment: $name"
                        }
                    }
                }
                if ($loadSegments -eq 0) { throw "ELF has no LOAD segments: $name" }
                Write-Output "ELF structure: PASS ($name)"
            }
        }
    } finally {
        $archive.Dispose()
    }
    Write-Output 'Signature, ARM64-only, ZIP alignment, and development-file exclusion: PASS'
} finally {
    restoreBuildEnvironment -Previous $previous
}
