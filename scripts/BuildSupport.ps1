#Requires -Version 7.6

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function getBuildSettings {
    $projectRoot = Split-Path -Parent $PSScriptRoot
    $config = Get-Content -LiteralPath (Join-Path $projectRoot 'app\build.gradle.kts') -Raw
    $patterns = [ordered]@{
        Version = 'versionName\s*=\s*"([^"]+)"'
        VersionCode = 'versionCode\s*=\s*(\d+)'
        CompileSdk = 'compileSdk\s*=\s*(\d+)'
        MinSdk = 'minSdk\s*=\s*(\d+)'
        BuildToolsVersion = 'buildToolsVersion\s*=\s*"([^"]+)"'
        JavaVersion = 'JavaLanguageVersion\.of\((\d+)\)'
    }
    $settings = [ordered]@{ Root = $projectRoot }
    foreach ($name in $patterns.Keys) {
        $value = [regex]::Match($config, $patterns[$name]).Groups[1].Value
        if (-not $value) { throw "Missing $name in app/build.gradle.kts." }
        $settings[$name] = $value
    }
    [pscustomobject]$settings
}

function getBuildContext {
    param([string]$SdkPath)

    $settings = getBuildSettings
    $projectRoot = $settings.Root
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
    $buildToolsVersion = $settings.BuildToolsVersion
    $buildTools = Join-Path $sdk "build-tools\$buildToolsVersion"
    foreach ($tool in @('lib\apksigner.jar', 'zipalign.exe', 'aapt2.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $buildTools $tool))) { throw "Missing Build Tools $buildToolsVersion/$tool." }
    }
    $jdk = getJdkPath
    [pscustomobject]@{ Root = $projectRoot; Sdk = $sdk; BuildTools = $buildTools; Jdk = $jdk; Version = $settings.Version; VersionCode = [int]$settings.VersionCode; MinSdk = [int]$settings.MinSdk }
}

function getDeniedStoragePermissionPattern {
    'android\.permission\.(MANAGE_EXTERNAL_STORAGE|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|READ_MEDIA_AUDIO|READ_MEDIA_IMAGES|READ_MEDIA_VIDEO)'
}

function getJdkPath {
    $jdk = $env:JAVA_HOME
    if (-not $jdk) { $jdk = Split-Path -Parent (Split-Path -Parent (Get-Command java -ErrorAction Stop).Source) }
    $java = Join-Path $jdk 'bin\java.exe'
    $javaVersion = & $java -version 2>&1
    $required = (getBuildSettings).JavaVersion
    if ($LASTEXITCODE -ne 0 -or ($javaVersion -join ' ') -notmatch "version `"$required(?:[.`"]|-)") {
        throw "JDK $required is required by Gradle. Set JAVA_HOME to that JDK installation."
    }
    return $jdk
}

function setBuildEnvironment {
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

function restoreBuildEnvironment {
    param([hashtable]$Previous)
    foreach ($name in $Previous.Keys) { [Environment]::SetEnvironmentVariable($name, $Previous[$name], 'Process') }
}

function invokeCheckedNative {
    param([string]$Executable, [string[]]$Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($Executable)) failed with exit code $LASTEXITCODE." }
}

function assertBuildScriptSyntax {
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
        $gitCommands = $scriptAst.FindAll({
            param($node)
            $node -is [Management.Automation.Language.CommandAst] -and
                $node.GetCommandName() -match '(^|[\\/])git(\.exe)?$'
        }, $true)
        if ($gitCommands.Count -gt 0) {
            throw "Direct Git command in $($file.Name). Repository operations must use jj; checks and builds must work without repository metadata."
        }
    }
}

function getReleaseApk {
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

function writeTestSummary {
    param($Context)
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
        }
        if ($total -eq 0 -or $failed -gt 0 -or $errors -gt 0 -or $skipped -gt 0) {
            throw "Incomplete test results for ${module}: $total total, $failed failures, $errors errors, $skipped skipped."
        }
        Write-Output "${module}: $total passed; reports: $reports"
    }
}
