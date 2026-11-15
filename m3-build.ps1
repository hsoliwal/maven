# SPDX-FileCopyrightText: 2026 Hitesh Soliwal and Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
# Keep Maven responsible for reactor selection, lifecycle execution and failures.
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('quick', 'test', 'verify', 'full', 'it', 'resume', 'help')]
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
Usage: ./m3-build.ps1 [-Mode quick|test|verify|full|it] [-Module <selector>]
                      [-Threads <count>] [-Maven <executable>] [-ExtraArgs <array>]
       ./m3-build.ps1 -Mode resume -ResumeFrom <selector> [-ExtraArgs <array>]

Default: verify. quick packages without running tests; full runs clean verify.
it activates Maven's existing run-its integration-test profile for verify.
-Module selects a reactor project and its prerequisites (-pl/-am).
resume delegates to Maven -rf and cannot be combined with -Module.
Parallel execution is opt-in through -Threads or explicit Maven arguments.
-Maven defaults to M3_MAVEN, then mvn on PATH.
Relative executable paths and Maven paths are resolved from this script's directory.
'@
    return
}

if ($Module -and [string]::IsNullOrWhiteSpace($Module)) {
    throw '-Module requires a nonblank selector.'
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
    'it'     { @('verify', '-Prun-its') }
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
    $mavenExecutable = $null
    if ($Maven.Contains('/') -or $Maven.Contains('\') -or
            [System.IO.Path]::IsPathRooted($Maven)) {
        # A selected path is literal. Wildcard discovery could launch a different
        # installation when the exact requested executable does not exist.
        $mavenFile = Get-Item -LiteralPath $Maven -ErrorAction SilentlyContinue
        if ($mavenFile -is [System.IO.FileInfo]) {
            $mavenExecutable = $mavenFile.FullName
        }
    } else {
        # Match literal executable names, including Windows executable extensions.
        $windowsHost = [System.IO.Path]::DirectorySeparatorChar -eq '\'
        $literalNames = @($Maven)
        if ($windowsHost) {
            foreach ($extension in ($env:PATHEXT -split ';')) {
                if ($extension) { $literalNames += $Maven + $extension }
            }
        }
        $commandLookup = @{ CommandType = 'Application'; All = $true; ErrorAction = 'SilentlyContinue' }
        # Bare names can contain glob syntax on POSIX. Enumerate those names without
        # passing their literal text to PowerShell's wildcard parser.
        if ($Maven.IndexOfAny([char[]]'*?[]`') -lt 0) {
            $commandLookup.Name = $Maven
        }
        $command = Get-Command @commandLookup |
            Where-Object {
                if ($windowsHost) { $literalNames -contains $_.Name }
                else { $literalNames -ccontains $_.Name }
            } | Select-Object -First 1
        if ($command) { $mavenExecutable = $command.Source }
    }
    if (-not $mavenExecutable) {
        throw "Maven executable not found: $Maven. Supply one literal executable name or path."
    }
    # Preserve Maven's exit status even when the caller enabled native-error exceptions.
    $PSNativeCommandUseErrorActionPreference = $false
    & $mavenExecutable @mavenArgs
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
exit $exitCode
