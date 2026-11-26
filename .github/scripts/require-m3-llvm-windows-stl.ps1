# SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
# SPDX-License-Identifier: Apache-2.0
# Host-ABI qualification only. Does not substitute for the source-built LLVM fork proof.
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) {
    throw 'The MSVC STL compatibility proof requires a Windows runner.'
}

$expectedVersion = '22.1.8'
$expectedInstallerSha256 = '16e5709785fef73c854646241c4a92c5cd574318d1b33c63330dd7721903e55c'
$installerUrlSuffix = "llvmorg-$expectedVersion/LLVM-$expectedVersion-win64.exe"

# Chocolatey's pinned package obtains the official upstream LLVM installer and
# verifies its SHA-256. Do not accept the runner's original LLVM 20 on VS 2026,
# or use _ALLOW_COMPILER_AND_STL_VERSION_MISMATCH to disable the STL guard.
& choco upgrade llvm --yes --no-progress --limit-output "--version=$expectedVersion"
if ($LASTEXITCODE -ne 0) {
    throw "Pinned Chocolatey LLVM $expectedVersion installation failed: $LASTEXITCODE"
}
$chocolateyRoot = $env:ChocolateyInstall
if ([string]::IsNullOrWhiteSpace($chocolateyRoot)) {
    $chocolateyRoot = Join-Path $env:ProgramData 'chocolatey'
}
$installerScript = Join-Path $chocolateyRoot 'lib/llvm/tools/chocolateyinstall.ps1'
if (-not (Test-Path -LiteralPath $installerScript -PathType Leaf)) {
    throw "Cannot inspect Chocolatey LLVM installer provenance: $installerScript"
}
$recipe = Get-Content -Raw -LiteralPath $installerScript
if (-not $recipe.Contains($installerUrlSuffix) -or
        -not $recipe.Contains($expectedInstallerSha256)) {
    throw 'Pinned LLVM package does not contain the expected official installer URL and SHA-256.'
}

$llvmHome = Join-Path $env:ProgramFiles 'LLVM'
$llvmBin = Join-Path $llvmHome 'bin'
$tools = @('clang', 'clang++', 'llc', 'opt', 'llvm-as', 'llvm-link')
$receipt = @("host_toolchain`tLLVM $expectedVersion", "upstream_installer_sha256`t$expectedInstallerSha256")
foreach ($tool in $tools) {
    $file = Join-Path $llvmBin "$tool.exe"
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Required LLVM tool is absent: $file"
    }
    $digest = (Get-FileHash -Algorithm SHA256 -LiteralPath $file).Hash.ToLowerInvariant()
    $receipt += "$tool`t$digest`t$file"
}
$clangxx = Join-Path $llvmBin 'clang++.exe'
$banner = & $clangxx --version
if ($LASTEXITCODE -ne 0 -or ($banner | Select-Object -First 1) -notmatch 'clang version 22[.]1[.]8') {
    throw "Installed Clang++ does not match the pinned host release: $banner"
}

$proofDir = Join-Path $env:RUNNER_TEMP 'm3-llvm-windows-stl-proof'
[void](New-Item -ItemType Directory -Force -Path $proofDir)
$source = Join-Path $proofDir 'stl-compat.cpp'
$output = Join-Path $proofDir 'stl-compat.exe'
$cpp20 = @'
#include <iostream>
consteval int answer() { return 42; }
int main() { std::cout << answer() << '\n'; return 0; }
'@
[System.IO.File]::WriteAllText($source, $cpp20, [System.Text.UTF8Encoding]::new($false))
& $clangxx '-std=c++20' '-Wall' '-Wextra' '-Werror' $source '-o' $output
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $output -PathType Leaf)) {
    throw 'LLVM/Windows MSVC STL C++20 compile/link proof failed.'
}
$actual = & $output
if ($LASTEXITCODE -ne 0 -or [string]::Join('', $actual).Trim() -ne '42') {
    throw "LLVM/Windows C++20 executable gave the wrong result: $actual"
}
$receipt += 'windows_msvc_stl_cpp20_compile_link_run' + "`tPASS"
[System.IO.File]::WriteAllLines((Join-Path $proofDir 'host-binary-receipt.tsv'), $receipt,
        [System.Text.UTF8Encoding]::new($false))

# GitHub prepends GITHUB_PATH entries for subsequent steps. Native integration
# sees this exact home; other tools retain their normal independent selectors.
Add-Content -LiteralPath $env:GITHUB_PATH -Value $llvmBin -Encoding utf8
Add-Content -LiteralPath $env:GITHUB_ENV -Value "M3_POLYGLOT_LLVM_TEST_HOME=$llvmHome" -Encoding utf8
Write-Output 'PASS: pinned LLVM 22.1.8 compiles, links and runs C++20 against the real MSVC STL'
