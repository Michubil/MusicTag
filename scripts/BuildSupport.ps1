#Requires -Version 7.6

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-BuildContext {
    param([string]$SdkPath)

    $projectRoot = Split-Path -Parent $PSScriptRoot
    if (-not $SdkPath) { $SdkPath = $env:ANDROID_HOME }
    if (-not $SdkPath) { $SdkPath = $env:ANDROID_SDK_ROOT }
    if (-not $SdkPath) {
        $localProperties = Join-Path $projectRoot 'local.properties'
        if (Test-Path -LiteralPath $localProperties) {
            $sdkLine = Get-Content -LiteralPath $localProperties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
            if ($sdkLine) { $SdkPath = ($sdkLine -replace '^sdk\.dir=', '').Replace('\:', ':').Replace('\\', '\') }
        }
    }
    if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    if (-not (Test-Path -LiteralPath $SdkPath -PathType Container)) {
        throw 'Android SDK not found. Pass -SdkPath or set ANDROID_HOME.'
    }
    $sdk = (Resolve-Path -LiteralPath $SdkPath).Path
    $config = Get-Content -LiteralPath (Join-Path $projectRoot 'app\build.gradle.kts') -Raw
    $version = [regex]::Match($config, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
    $versionCode = [regex]::Match($config, 'versionCode\s*=\s*(\d+)').Groups[1].Value
    $minSdk = [regex]::Match($config, 'minSdk\s*=\s*(\d+)').Groups[1].Value
    $buildToolsVersion = [regex]::Match($config, 'buildToolsVersion\s*=\s*"([^"]+)"').Groups[1].Value
    if (-not $version -or -not $versionCode -or -not $minSdk -or -not $buildToolsVersion) { throw 'Version, minSdk or Build Tools version is missing from app/build.gradle.kts.' }
    $buildTools = Join-Path $sdk "build-tools\$buildToolsVersion"
    foreach ($tool in @('lib\apksigner.jar', 'zipalign.exe', 'aapt2.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $buildTools $tool))) { throw "Missing Build Tools $buildToolsVersion/$tool." }
    }
    $jdk = Get-JdkPath
    [pscustomobject]@{ Root = $projectRoot; Sdk = $sdk; BuildTools = $buildTools; Jdk = $jdk; Version = $version; VersionCode = [int]$versionCode; MinSdk = [int]$minSdk }
}

function Get-DeniedStoragePermissionPattern {
    'android\.permission\.(MANAGE_EXTERNAL_STORAGE|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|READ_MEDIA_AUDIO|READ_MEDIA_IMAGES|READ_MEDIA_VIDEO)'
}

function Get-JdkPath {
    $jdk = $env:JAVA_HOME
    if (-not $jdk) { $jdk = Split-Path -Parent (Split-Path -Parent (Get-Command java -ErrorAction Stop).Source) }
    $java = Join-Path $jdk 'bin\java.exe'
    $javaVersion = & $java -version 2>&1
    if ($LASTEXITCODE -ne 0 -or ($javaVersion -join ' ') -notmatch 'version "25(?:[."]|-)') {
        throw 'JDK 25 is required. Set JAVA_HOME to a JDK 25 installation.'
    }
    return $jdk
}

function Set-BuildEnvironment {
    param($Context)
    $previous = @{}
    foreach ($name in @('ANDROID_HOME', 'ANDROID_SDK_ROOT', 'JAVA_HOME')) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    $env:ANDROID_HOME = $Context.Sdk
    $env:ANDROID_SDK_ROOT = $Context.Sdk
    $env:JAVA_HOME = $Context.Jdk
    return $previous
}

function Restore-BuildEnvironment {
    param([hashtable]$Previous)
    foreach ($name in $Previous.Keys) { [Environment]::SetEnvironmentVariable($name, $Previous[$name], 'Process') }
}

function Invoke-CheckedNative {
    param([string]$Executable, [string[]]$Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($Executable)) failed with exit code $LASTEXITCODE." }
}

function Test-BuildScriptSyntax {
    $entryDirectories = @($PSScriptRoot, (Split-Path -Parent $PSScriptRoot))
    $legacyEntries = @(Get-ChildItem -LiteralPath $entryDirectories -File |
        Where-Object { $_.Name -match '\.(bat|cmd|sh)$|^gradlew$' })
    if ($legacyEntries.Count -gt 0) { throw "Legacy shell entry points: $($legacyEntries.Name -join ', ')" }
    $scriptFiles = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File)
    $scriptFiles += Get-Item -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) 'gradlew.ps1')
    foreach ($file in $scriptFiles) {
        $parseErrors = $null
        $parseTokens = $null
        $scriptAst = [Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$parseTokens, [ref]$parseErrors)
        if ($parseErrors.Count -gt 0) { throw ($parseErrors | Out-String) }
        if ((Get-Content -LiteralPath $file.FullName -TotalCount 1) -ne '#Requires -Version 7.6') {
            throw "Script must require PowerShell 7.6: $($file.Name)"
        }
        $legacyCommands = $scriptAst.FindAll({
            param($node)
            $node -is [Management.Automation.Language.CommandAst] -and
                $node.GetCommandName() -match '(^|[\\/])(powershell|cmd|bash|sh)(\.exe)?$'
        }, $true)
        if ($legacyCommands.Count -gt 0 -or (Select-String -LiteralPath $file.FullName -Pattern '\.(bat|cmd)\b')) {
            throw "Legacy shell entry point in $($file.Name). Use pwsh and native tools directly."
        }
    }
}

function Get-ReleaseApk {
    param($Context)
    $directory = Join-Path $Context.Root 'app\build\outputs\apk\release'
    $metadata = Get-Content -LiteralPath (Join-Path $directory 'output-metadata.json') -Raw | ConvertFrom-Json
    $outputs = @($metadata.elements)
    if ($outputs.Count -ne 1) { throw 'Expected one ARM64 release APK.' }
    $expectedName = "MusicTag-v$($Context.Version).apk"
    if ($outputs[0].versionName -ne $Context.Version -or $outputs[0].versionCode -ne $Context.VersionCode -or $outputs[0].outputFile -ne $expectedName) {
        throw 'Release metadata does not match the current version or expected APK name.'
    }
    (Resolve-Path -LiteralPath (Join-Path $directory $expectedName)).Path
}

function Write-TestSummary {
    param($Context, [switch]$RequireMp3Sample)
    $samplePassed = $false
    foreach ($module in @('app', 'core/designsystem')) {
        $reports = Join-Path $Context.Root "$module/build/test-results/testDebugUnitTest"
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
            foreach ($case in $suite.SelectNodes('testcase')) {
                if ($case.GetAttribute('classname') -eq 'top.michubil.musictag.data.id3.Mp3ArtworkTest' -and
                    $case.GetAttribute('name') -eq 'externalMp3SampleDecodesWithoutChangingSource' -and
                    $case.SelectNodes('failure|error|skipped').Count -eq 0) { $samplePassed = $true }
            }
        }
        if ($total -eq 0 -or $total -eq $skipped -or $failed -gt 0 -or $errors -gt 0) { throw "Invalid or failing test results for $module." }
        Write-Output "${module}: $($total - $skipped) passed, $skipped skipped; reports: $reports"
    }
    if ($RequireMp3Sample -and -not $samplePassed) { throw 'Requested MP3 sample regression did not pass or was skipped.' }
}
