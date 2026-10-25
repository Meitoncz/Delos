# Vendors the PyroWave codec into app/src/main/jni/pyrowave from the official Vibepollo client,
# Nonary/moonlight-qt: PyroWave at the bitstream commit Vibepollo checks (186f0393) with Nonary's patches
# already applied, the Granite subset it builds with, volk and the Vulkan headers. Delos builds it from
# this source with ndk-build (see app/src/main/jni/pyrowave/Android.mk); nothing prebuilt is used.
#
# Run from the repository root:  powershell -ExecutionPolicy Bypass -File tools\pyrowave\vendor.ps1
# To update: change $Commit to a newer moonlight-qt commit whose pyrowave/VENDOR.txt names the bitstream
# commit Vibepollo uses, rerun, rebuild and test a PyroWave stream.

$ErrorActionPreference = 'Stop'
$Commit = 'e4693208c9c1abefdd8c1cd44600e613b3fc14fb'
$Repo = 'https://github.com/Nonary/moonlight-qt.git'
$Root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$Target = Join-Path $Root 'app\src\main\jni\pyrowave'
$Work = Join-Path ([System.IO.Path]::GetTempPath()) "delos-pyrowave-$Commit"

if (-not (Test-Path $Work)) {
    git clone --quiet --filter=blob:none --no-checkout $Repo $Work
    git -C $Work sparse-checkout set --no-cone /pyrowave/
    git -C $Work checkout --quiet $Commit
}

$Source = Join-Path $Work 'pyrowave'
# Keep our build file and notes; replace the vendored source
foreach ($dir in @('pyrowave')) {
    $path = Join-Path $Target $dir
    if (Test-Path $path) { Remove-Item -Recurse -Force $path }
}
New-Item -ItemType Directory -Force (Join-Path $Target 'pyrowave') | Out-Null

# Everything but tests, benchmarks, evaluation data and desktop tools
$exclude = @('eval-results', 'pyrowave_c_test.cpp', 'pyrowave_c_interop_test.cpp', 'bench.cpp', 'sandbox.cpp',
    'viewer.cpp', 'evaluator.cpp', 'psnr.cpp', 'encode_desktop.cpp', 'pyrowave-eval-gen.py', 'metrics', 'assets')
Get-ChildItem (Join-Path $Source 'pyrowave') | Where-Object { $exclude -notcontains $_.Name } |
    Copy-Item -Destination (Join-Path $Target 'pyrowave') -Recurse -Force
Copy-Item (Join-Path $Source 'volk_platform.c') $Target -Force
Copy-Item (Join-Path $Source 'VENDOR.txt') (Join-Path $Target 'VENDOR-moonlight-qt.txt') -Force

# Delos' own fixes on top (tools/pyrowave/patches, paths from the repository root)
foreach ($patch in Get-ChildItem (Join-Path $PSScriptRoot 'patches') -Filter '*.patch' | Sort-Object Name) {
    git -C $Root apply --whitespace=nowarn $patch.FullName
    if ($LASTEXITCODE -ne 0) { throw "Patch $($patch.Name) no longer applies" }
}

Set-Content -Encoding utf8 (Join-Path $Target 'VENDOR.txt') @"
PyroWave for Delos, vendored by tools/pyrowave/vendor.ps1 from Nonary/moonlight-qt
$Commit (pyrowave/), which is the official Vibepollo client. Do not edit by hand.

See VENDOR-moonlight-qt.txt for the PyroWave, Granite, volk and Vulkan header commits and the patches
applied to them, and tools/pyrowave/patches for Delos' own fixes on top. The PyroWave bitstream carries no version: the host and Delos must use the same
PyroWave commit (PYROWAVE_BITSTREAM_ID in moonlight-common-c's Limelight.h).

Licenses: PyroWave and Granite MIT (pyrowave/LICENSE, pyrowave/Granite/LICENSE), volk MIT,
Vulkan headers Apache-2.0 / MIT.
"@
Write-Host "Vendored PyroWave from moonlight-qt $Commit into $Target"
