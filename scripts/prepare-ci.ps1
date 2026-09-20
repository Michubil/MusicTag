#Requires -Version 7.6

[CmdletBinding()]
param([string]$SdkPath = $env:ANDROID_HOME)

. "$PSScriptRoot\BuildSupport.ps1"
if ($env:GITHUB_ACTIONS -ne 'true') { throw 'SDK provisioning is only intended for the GitHub-hosted runner.' }
if (-not $SdkPath -or -not (Test-Path -LiteralPath $SdkPath -PathType Container)) {
    throw 'The Windows runner must provide ANDROID_HOME.'
}
$settings = getBuildSettings
$java = Join-Path (getJdkPath) 'bin\java.exe'
$manager = Join-Path $SdkPath 'cmdline-tools/latest/lib/sdkmanager-classpath.jar'
if (-not (Test-Path -LiteralPath $manager -PathType Leaf)) { throw 'Android Command Line Tools are missing from the runner.' }
$sdkArguments = @('-cp', $manager, 'com.android.sdklib.tool.sdkmanager.SdkManagerCli', "--sdk_root=$SdkPath")
@('y') * 100 | & $java @sdkArguments '--licenses'
if ($LASTEXITCODE -ne 0) { throw 'Android SDK license acceptance failed.' }
invokeCheckedNative -Executable $java -Arguments ($sdkArguments + @(
    "platforms;android-$($settings.CompileSdk).0", "build-tools;$($settings.BuildToolsVersion)"
))
