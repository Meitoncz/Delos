<#
.SYNOPSIS
  Uploads the four metadata credentials to the delos-meta Worker as Worker secrets.

.DESCRIPTION
  Reads the values from environment variables (when set and not blank) or from the repository-root
  local.properties (git-ignored), exactly like the old Android build did, and pipes each value to
  `npx wrangler secret put <NAME>` on standard input. A value is never printed, logged or passed as a
  command-line argument; all output names only the secrets.

  Requires `npx wrangler login` (or CLOUDFLARE_API_TOKEN) to have been done and the Worker to exist
  (`npx wrangler deploy` first).

.PARAMETER PropertiesFile
  Path of the properties file. Default: local.properties in the repository root (or in the main checkout when
  run from a git worktree).

.PARAMETER CheckOnly
  Only report which of the four values were found (names only); upload nothing.
#>
[CmdletBinding()]
param(
    [string]$PropertiesFile,
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'

# Source key in local.properties / environment -> Worker secret name.
$Mapping = [ordered]@{
    'DELOS_IGDB_CLIENT_ID'     = 'IGDB_CLIENT_ID'
    'DELOS_IGDB_CLIENT_SECRET' = 'IGDB_CLIENT_SECRET'
    'DELOS_PCGW_BOT_USERNAME'  = 'PCGW_BOT_USERNAME'
    'DELOS_PCGW_BOT_PASSWORD'  = 'PCGW_BOT_PASSWORD'
}

function Resolve-PropertiesFile {
    if ($PropertiesFile) { return $PropertiesFile }
    $candidates = @((Join-Path $PSScriptRoot '..\..\local.properties'))
    $common = & git -C $PSScriptRoot rev-parse --path-format=absolute --git-common-dir 2>$null
    if ($LASTEXITCODE -eq 0 -and $common) { $candidates += (Join-Path (Split-Path -Parent $common) 'local.properties') }
    foreach ($candidate in $candidates) { if (Test-Path -LiteralPath $candidate) { return $candidate } }
    return $candidates[0]
}

# Java .properties value unescaping (the Android build reads the file with java.util.Properties).
function ConvertFrom-PropertiesValue([string]$Raw) {
    $builder = New-Object System.Text.StringBuilder
    for ($i = 0; $i -lt $Raw.Length; $i++) {
        $c = $Raw[$i]
        if ($c -ne '\' -or $i + 1 -ge $Raw.Length) { [void]$builder.Append($c); continue }
        $i++
        switch -CaseSensitive ($Raw[$i]) {
            't' { [void]$builder.Append("`t") }
            'n' { [void]$builder.Append("`n") }
            'r' { [void]$builder.Append("`r") }
            'f' { [void]$builder.Append("`f") }
            'u' {
                if ($i + 4 -le $Raw.Length - 1) {
                    $hex = $Raw.Substring($i + 1, 4)
                    [void]$builder.Append([char][Convert]::ToInt32($hex, 16))
                    $i += 4
                }
            }
            default { [void]$builder.Append($Raw[$i]) }
        }
    }
    return $builder.ToString()
}

function Read-Properties([string]$Path) {
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path)) { return $values }
    foreach ($line in [System.IO.File]::ReadAllLines($Path)) {
        $text = $line.TrimStart()
        if ($text -eq '' -or $text.StartsWith('#') -or $text.StartsWith('!')) { continue }
        # The key ends at the first unescaped '=', ':' or whitespace.
        $end = -1
        for ($i = 0; $i -lt $text.Length; $i++) {
            if ($text[$i] -eq '\') { $i++; continue }
            if ("$($text[$i])" -in '=', ':', ' ', "`t") { $end = $i; break }
        }
        if ($end -lt 0) { $values[(ConvertFrom-PropertiesValue $text)] = ''; continue }
        $key = ConvertFrom-PropertiesValue $text.Substring(0, $end)
        $rest = $text.Substring($end).TrimStart()
        if ($rest.StartsWith('=') -or $rest.StartsWith(':')) { $rest = $rest.Substring(1).TrimStart() }
        $values[$key] = ConvertFrom-PropertiesValue $rest
    }
    return $values
}

function Invoke-SecretPut([string]$Name, [string]$Value) {
    $npx = (Get-Command npx.cmd -ErrorAction SilentlyContinue)
    if (-not $npx) { $npx = Get-Command npx -ErrorAction Stop }
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $npx.Source
    $info.Arguments = "wrangler secret put $Name"
    $info.WorkingDirectory = $PSScriptRoot
    $info.UseShellExecute = $false
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    # Windows PowerShell 5.1 has no StandardInputEncoding; the value is written as raw UTF-8 bytes instead.
    $process = [System.Diagnostics.Process]::Start($info)
    $outTask = $process.StandardOutput.ReadToEndAsync()
    $errTask = $process.StandardError.ReadToEndAsync()
    # The value travels on standard input only, with no trailing newline.
    $bytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes($Value)
    $process.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
    $process.StandardInput.Close()
    $process.WaitForExit()
    $output = $outTask.Result + $errTask.Result
    return [pscustomobject]@{ ExitCode = $process.ExitCode; Output = $output.Replace($Value, '***') }
}

$path = Resolve-PropertiesFile
$properties = Read-Properties $path
Write-Host "Reading credentials from environment variables and $path"

$found = [ordered]@{}
$missing = @()
foreach ($source in $Mapping.Keys) {
    $name = $Mapping[$source]
    $fromEnvironment = [Environment]::GetEnvironmentVariable($source)
    if ($fromEnvironment -and $fromEnvironment.Trim()) { $found[$name] = $fromEnvironment }
    elseif ($properties.ContainsKey($source) -and $properties[$source]) { $found[$name] = $properties[$source] }
    else { $missing += $name }
}

foreach ($name in $Mapping.Values) {
    if ($found.Contains($name)) { Write-Host "  found    $name" } else { Write-Host "  MISSING  $name" -ForegroundColor Red }
}
if ($missing.Count -gt 0) {
    Write-Error "No value for: $($missing -join ', '). Nothing was uploaded."
    exit 1
}
if ($CheckOnly) { Write-Host 'Check only: nothing uploaded.'; exit 0 }

$failed = @()
foreach ($name in $found.Keys) {
    $result = Invoke-SecretPut $name $found[$name]
    if ($result.ExitCode -eq 0) {
        Write-Host "  uploaded $name" -ForegroundColor Green
    } else {
        Write-Host "  FAILED   $name (wrangler exit code $($result.ExitCode))" -ForegroundColor Red
        if ($result.Output.Trim()) { Write-Host $result.Output.Trim() }
        $failed += $name
    }
}
if ($failed.Count -gt 0) {
    Write-Error "Failed secrets: $($failed -join ', '). Is wrangler logged in and the Worker deployed (npx wrangler deploy)?"
    exit 1
}
Write-Host 'All four secrets uploaded.'
