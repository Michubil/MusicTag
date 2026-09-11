#Requires -Version 7.6

# PowerShell entry point for the unchanged Gradle Wrapper distribution.
# Configure the Gradle JVM in gradle.properties; arguments are forwarded without shell parsing.
. "$PSScriptRoot\scripts\BuildSupport.ps1"
$gradleJava = Join-Path (Get-JdkPath) 'bin\java.exe'
& $gradleJava '-Xmx64m' '-Xms64m' '-Dorg.gradle.appname=gradlew' -jar "$PSScriptRoot\gradle\wrapper\gradle-wrapper.jar" @args
exit $LASTEXITCODE
