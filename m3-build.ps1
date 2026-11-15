# SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
# Keep Maven responsible for reactor selection, lifecycle execution and failures.
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('quick', 'test', 'verify', 'full', 'resume', 'help')]
    [string]$Mode = 'verify',
    [string]$Module,
    [string]$ResumeFrom,
    [ValidateRange(1, 2147483647)]
    [int]$Threads = 1,
    [string]$Maven = $env:M3_MAVEN,
    [string[]]$ExtraArgs = @(),
    [switch]$Help
)

$ErrorActionPreference = 'Stop'
if ($Help -or $Mode -eq 'help') {
    @'
Usage: ./m3-build.ps1 [-Mode quick|test|verify|full] [-Module <selector>]
                      [-Threads <count>] [-Maven <executable>] [-ExtraArgs <array>]
       ./m3-build.ps1 -Mode resume -ResumeFrom <selector> [-ExtraArgs <array>]

Default: verify. quick packages without running tests; full runs clean verify.
-Module selects a reactor project and its prerequisites (-pl/-am).
resume delegates to Maven -rf and cannot be combined with -Module.
Parallel execution is opt-in through -Threads or explicit Maven arguments.
-Maven defaults to M3_MAVEN, then mvn on PATH.
Relative executable paths and Maven paths are resolved from this script's directory.
'@
    return
}

if ($Mode -eq 'resume') {
    if ([string]::IsNullOrWhiteSpace($ResumeFrom) -or $ResumeFrom.StartsWith('-')) {
        throw 'Resume mode requires -ResumeFrom <module>.'
    }
    if ($Module) {
        throw 'Use either -Module or resume mode. Resume uses Maven -rf without implicit -am.'
    }
} elseif ($ResumeFrom) {
    throw '-ResumeFrom is only valid in resume mode.'
}

if ([string]::IsNullOrWhiteSpace($Maven)) { $Maven = 'mvn' }
$goals = switch ($Mode) {
    'quick'  { @('package', '-DskipTests') }
    'test'   { @('test') }
    'verify' { @('verify') }
    'full'   { @('clean', 'verify') }
    'resume' { @('verify', '-rf', $ResumeFrom) }
}
$mavenArgs = @('-B', '-ntp')
if ($PSBoundParameters.ContainsKey('Threads')) {
    $mavenArgs += @('-T', [string]$Threads)
}
if ($Module) { $mavenArgs += @('-pl', $Module, '-am') }
$mavenArgs += $goals
$mavenArgs += $ExtraArgs

$exitCode = 1
Push-Location -LiteralPath $PSScriptRoot
try {
    $command = Get-Command -Name $Maven -CommandType Application -ErrorAction Stop |
        Select-Object -First 1
    # Preserve Maven's exit status even when the caller enabled native-error exceptions.
    $PSNativeCommandUseErrorActionPreference = $false
    & $command.Source @mavenArgs
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $exitCode
