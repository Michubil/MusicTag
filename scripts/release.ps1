#Requires -Version 7.6

[CmdletBinding(DefaultParameterSetName = 'Bump')]
param(
    [Parameter(ParameterSetName = 'Bump')]
    [ValidateSet('patch', 'minor', 'major')]
    [string]$Bump,
    [Parameter(ParameterSetName = 'Version')]
    [ValidatePattern('^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$')]
    [string]$Version,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\BuildSupport.ps1"
if ($PSBoundParameters.ContainsKey('Bump') -eq $PSBoundParameters.ContainsKey('Version')) {
    throw 'Specify exactly one of -Bump or -Version.'
}

foreach ($tool in @('jj', 'gh')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Required tool is unavailable: $tool" }
}
$settings = getBuildSettings
if ($settings.Version -notmatch '^([0-9]+)\.([0-9]+)\.([0-9]+)$') {
    throw "Current versionName must be MAJOR.MINOR.PATCH: $($settings.Version)"
}
$parts = @([long]$Matches[1], [long]$Matches[2], [long]$Matches[3])
$target = if ($PSCmdlet.ParameterSetName -eq 'Version') { $Version } else {
    switch ($Bump) {
        patch { "$($parts[0]).$($parts[1]).$($parts[2] + 1)" }
        minor { "$($parts[0]).$($parts[1] + 1).0" }
        major { "$($parts[0] + 1).0.0" }
    }
}
$next = @($target.Split('.') | ForEach-Object { [long]::Parse($_) })
for ($index = 0; $index -lt 3; $index++) {
    if ($next[$index] -gt $parts[$index]) { break }
    if ($next[$index] -lt $parts[$index] -or $index -eq 2) {
        throw "Target version must be newer than $($settings.Version)."
    }
}
$nextCode = [int]$settings.VersionCode + 1
$tag = "v$target"

$bookmarks = @(invokeCheckedNative jj @('log', '-r', '@', '--no-graph', '-T',
    'bookmarks.map(|b| b.name()).join("\n") ++ "\n"') | Where-Object { $_ })
if ($bookmarks.Count -ne 1 -or $bookmarks[0] -cne 'dev') {
    throw 'Current change must have exactly the dev bookmark.'
}
$bookmark = $bookmarks[0]
$description = ((invokeCheckedNative jj @('log', '-r', '@', '--no-graph', '-T', 'description.first_line()')) -join '').Trim()
$conflict = ((invokeCheckedNative jj @('log', '-r', '@', '--no-graph', '-T', 'conflict')) -join '').Trim()
if (-not $description -or $conflict -ne 'false') { throw 'Current bookmarked change needs a description and no conflicts.' }
if ($description -match '^chore\(release\): prepare v') { throw 'This change already prepares a release; inspect it before starting another.' }
$candidate = ((invokeCheckedNative jj @('log', '-r', '@', '--no-graph', '-T', 'commit_id')) -join '').Trim()
$onMain = @(invokeCheckedNative jj @('log', '-r', '@ & ancestors(main@origin)', '--no-graph', '-T', 'commit_id'))
if ($onMain.Count -gt 0) { throw 'Current bookmark already points into main.' }
if ($DryRun) {
    $base = @(invokeCheckedNative jj @('log', '-r', 'main@origin & ancestors(@)', '--no-graph', '-T', 'commit_id'))
    if ($base.Count -eq 0) { throw 'Current change is not based on the fetched main history.' }
    $knownTags = @(invokeCheckedNative jj @('tag', 'list', '-a', '-T', 'name ++ "\n"'))
    if ($tag -in $knownTags) { throw "$tag already exists; inspect it before releasing." }
}

Write-Output "Current version : $($settings.Version) ($($settings.VersionCode))"
Write-Output "Target version  : $target ($nextCode)"
Write-Output "Candidate       : $bookmark ($candidate)"
if ($DryRun) {
    Write-Output "Dry run: would update Gradle, test, push $bookmark, merge its PR into main, then push $tag."
    return
}

$origin = @(invokeCheckedNative jj @('git', 'remote', 'list') | Where-Object { $_ -match '^origin\s+' })
if ($origin.Count -ne 1) {
    throw 'Jujutsu has no origin remote. Configure the repository remote before releasing.'
}
$originUrl = ($origin[0] -split '\s+', 2)[1]
if ($originUrl -notmatch '^(?:https://github\.com/|git@github\.com:)([^/]+)/([^/]+?)(?:\.git)?$') {
    throw 'Origin must be a GitHub repository URL to create its PR.'
}
$repository = "$($Matches[1])/$($Matches[2])"
& gh auth status
if ($LASTEXITCODE -ne 0) { throw 'GitHub CLI is not authenticated; run gh auth login before releasing.' }
$mergeOptions = ((invokeCheckedNative gh @('repo', 'view', $repository, '--json', 'squashMergeAllowed')) -join [Environment]::NewLine) | ConvertFrom-Json
if (-not $mergeOptions.squashMergeAllowed) { throw 'The dev branch needs squash merges; enable them for this repository.' }
$mergeMethod = '--squash'
invokeCheckedNative jj @('git', 'fetch', '--remote', 'origin') | Out-Null
$latestBase = @(invokeCheckedNative jj @('log', '-r', 'main@origin & ancestors(@)', '--no-graph', '-T', 'commit_id'))
if ($latestBase.Count -ne 1) { throw 'Candidate is behind or diverged from origin/main; synchronize it before releasing.' }
$knownTags = @(invokeCheckedNative jj @('tag', 'list', '-a', '-T', 'name ++ "\n"'))
if ($tag -in $knownTags) { throw "$tag already exists; inspect it before releasing." }

$gradleFile = Join-Path $settings.Root 'app\build.gradle.kts'
$source = [IO.File]::ReadAllText($gradleFile)
$codePattern = '(?m)^(\s*versionCode\s*=\s*)\d+(\s*)$'
$namePattern = '(?m)^(\s*versionName\s*=\s*)"[^"]+"(\s*)$'
if ([regex]::Matches($source, $codePattern).Count -ne 1 -or [regex]::Matches($source, $namePattern).Count -ne 1) {
    throw 'Expected exactly one versionCode and versionName assignment in app/build.gradle.kts.'
}
Write-Output '[1/8] Preparing Gradle version'
invokeCheckedNative jj @('new', '@', '-m', "chore(release): prepare $tag") | Out-Null
$source = [regex]::Replace($source, $codePattern, { param($m) "$($m.Groups[1].Value)$nextCode$($m.Groups[2].Value)" })
$source = [regex]::Replace($source, $namePattern, { param($m) $m.Groups[1].Value + '"' + $target + '"' + $m.Groups[2].Value })
[IO.File]::WriteAllText($gradleFile, $source, [Text.UTF8Encoding]::new($false))
$updated = getBuildSettings
if ([int]$updated.VersionCode -ne $nextCode -or $updated.Version -cne $target) {
    throw 'Gradle version update did not produce the requested values.'
}
invokeCheckedNative jj @('bookmark', 'move', $bookmark, '--to', '@') | Out-Null

Write-Output '[2/8] Running local checks'
& "$PSScriptRoot\test.ps1"
if (-not $?) { throw 'Local checks failed; no branch or tag was pushed.' }
$head = ((invokeCheckedNative jj @('log', '-r', '@', '--no-graph', '-T', 'commit_id')) -join '').Trim()

Write-Output "[3/8] Pushing $bookmark"
invokeCheckedNative jj @('git', 'push', '--remote', 'origin', '--bookmark', $bookmark) | Out-Null

Write-Output '[4/8] Creating or reusing PR'
$prs = @(invokeCheckedNative gh @('pr', 'list', '--repo', $repository, '--head', $bookmark, '--base', 'main',
    '--state', 'open', '--json', 'number,url', '--jq', '.[] | "\(.number) \(.url)"'))
if ($prs.Count -gt 1) { throw "Multiple open PRs target main from $bookmark." }
if ($prs.Count -eq 0) {
    $title = ((invokeCheckedNative jj @('log', '-r', '@-', '--no-graph', '-T', 'description.first_line()')) -join '').Trim()
    if (-not $title) { $title = $description }
    $prUrl = ((invokeCheckedNative gh @('pr', 'create', '--repo', $repository, '--head', $bookmark, '--base', 'main',
        '--title', $title, '--body', "Includes the verified $tag version update.")) -join '').Trim()
    $prNumber = [int](($prUrl -split '/')[-1])
} else {
    $prNumber = [int](($prs[0] -split ' ')[0])
    $prUrl = ($prs[0] -split ' ')[1]
}
if (-not $prNumber) { throw 'Could not identify the release candidate PR.' }
$pr = ((invokeCheckedNative gh @('pr', 'view', "$prNumber", '--repo', $repository, '--json',
    'headRefOid,baseRefName', '--jq', '.')) -join [Environment]::NewLine) | ConvertFrom-Json
if ($pr.baseRefName -cne 'main' -or $pr.headRefOid -cne $head) {
    throw 'The PR head or base differs from the verified release candidate.'
}
invokeCheckedNative gh @('pr', 'merge', "$prNumber", '--repo', $repository, '--auto', $mergeMethod,
    '--match-head-commit', $head) | Out-Null

Write-Output "[5/8] Waiting for PR #$prNumber to merge"
$deadline = (Get-Date).AddMinutes(30)
do {
    $pr = ((invokeCheckedNative gh @('pr', 'view', "$prNumber", '--repo', $repository, '--json',
        'state,mergeCommit,mergeStateStatus,reviewDecision', '--jq', '.')) -join [Environment]::NewLine) | ConvertFrom-Json
    if ($pr.state -eq 'MERGED') { break }
    if ($pr.state -ne 'OPEN') { throw "PR #$prNumber closed without merging: $prUrl" }
    $checks = @(& gh pr checks "$prNumber" --repo $repository --required --json bucket --jq '.[].bucket')
    if ($LASTEXITCODE -notin @(0, 8)) { throw "Cannot read required checks for PR #${prNumber}: $prUrl" }
    if ($checks -contains 'fail' -or $checks -contains 'cancel') { throw "Required PR checks failed: $prUrl" }
    Write-Output "Waiting for PR #$prNumber ($($pr.mergeStateStatus), review: $($pr.reviewDecision)); $($checks -join ', ')"
    if ((Get-Date) -ge $deadline) { throw "Timed out waiting for PR #$prNumber to merge: $prUrl" }
    Start-Sleep -Seconds 20
} while ($true)
if (-not $pr.mergeCommit.oid) { throw 'Merged PR has no merge commit ID.' }

Write-Output '[6/8] Fetching merged main'
invokeCheckedNative jj @('git', 'fetch', '--remote', 'origin') | Out-Null
$merged = @(invokeCheckedNative jj @('log', '-r', "$($pr.mergeCommit.oid) & ancestors(main@origin)",
    '--no-graph', '-T', 'commit_id'))
if ($merged.Count -ne 1) { throw 'Fetched main does not contain the merged PR commit.' }
$mainFile = (invokeCheckedNative jj @('file', 'show', '-r', 'main@origin', 'app/build.gradle.kts')) -join [Environment]::NewLine
$expectedName = '(?m)^\s*versionName\s*=\s*"' + [regex]::Escape($target) + '"\s*$'
$expectedCode = "(?m)^\s*versionCode\s*=\s*$nextCode\s*$"
if ($mainFile -notmatch $expectedName -or $mainFile -notmatch $expectedCode) {
    throw 'Merged main has a different versionName or versionCode.'
}
$localMain = ((invokeCheckedNative jj @('log', '-r', 'main', '--no-graph', '-T', 'commit_id')) -join '').Trim()
$remoteMain = ((invokeCheckedNative jj @('log', '-r', 'main@origin', '--no-graph', '-T', 'commit_id')) -join '').Trim()
if ($localMain -ne $remoteMain) {
    $oldMain = @(invokeCheckedNative jj @('log', '-r', 'main & ancestors(main@origin)', '--no-graph', '-T', 'commit_id'))
    if ($oldMain.Count -ne 1) { throw 'Local main diverged from fetched origin/main; inspect before tagging.' }
    invokeCheckedNative jj @('bookmark', 'move', 'main', '--to', 'main@origin') | Out-Null
}
# Squash 合并不把 dev 的提交带进 main，所以把 dev 重置到 main，下一次 PR 才只含新提交。
invokeCheckedNative jj @('bookmark', 'set', 'dev', '-r', 'main@origin') | Out-Null
invokeCheckedNative jj @('new', 'dev') | Out-Null
Write-Output '  dev reset to main; the remote dev branch catches up on your next push.'
$knownTags = @(invokeCheckedNative jj @('tag', 'list', '-a', '-T', 'name ++ "\n"'))
if ($tag -in $knownTags) { throw "$tag already exists; inspect it before releasing." }

Write-Output "[7/8] Creating $tag"
invokeCheckedNative jj @('tag', 'set', $tag, '-r', 'main') | Out-Null
Write-Output '[8/8] Pushing release tag'
invokeCheckedNative jj @('git', 'push', '--remote', 'origin', '--tag', $tag) | Out-Null
Write-Output "Release trigger complete: $tag"
