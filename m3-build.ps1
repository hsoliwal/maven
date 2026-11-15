# SPDX-License-Identifier: Apache-2.0
# Small, opt-in build entry point for this Maven fork.
[CmdletBinding()]
param(
    [ValidateSet('quick', 'test', 'verify', 'full', 'resume')]
    [string]$Mode = 'verify',
    [string]$Module,
    [string]$ResumeFrom,
    [int]$Threads = 1,
    [string[]]$ExtraArgs = @()
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if ($Threads -lt 1) { throw 'Threads must be at least 1.' }
if ($Mode -eq 'resume' -and [string]::IsNullOrWhiteSpace($ResumeFrom)) {
    throw 'Resume mode requires -ResumeFrom <module>.'
}
if ($Mode -ne 'resume' -and $ResumeFrom) {
    throw '-ResumeFrom is only valid in resume mode.'
}

$mvn = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvn) {
    throw 'Maven is not on PATH. Maven core is bootstrapped using an installed Maven (see README.md).'
}
$goals = switch ($Mode) {
    'quick'  { @('package', '-DskipTests') }
    'test'   { @('test') }
    'verify' { @('verify') }
    'full'   { @('clean', 'verify') }
    'resume' { @('verify', '-rf', $ResumeFrom) }
}
$argsList = @('-B', '-ntp', '-T', [string]$Threads)
if ($Module) { $argsList += @('-pl', $Module, '-am') }
$argsList += $goals
$argsList += $ExtraArgs
Push-Location $root
try {
    & $mvn.Source @argsList
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
