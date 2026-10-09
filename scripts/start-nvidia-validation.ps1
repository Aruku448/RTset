param(
    [Parameter(Mandatory = $true)]
    [string]$LauncherPath
)

$ErrorActionPreference = 'Stop'
$resolvedLauncher = (Resolve-Path -LiteralPath $LauncherPath).Path
if (-not (Test-Path -LiteralPath $resolvedLauncher -PathType Leaf)) {
    throw 'LauncherPath must point to the launcher executable.'
}
$previous = $env:NV_ALLOW_RAYTRACING_VALIDATION
$existingLauncher = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -match '^(javaw|HMCL.*)\.exe$' -and
    $_.CommandLine -match '(?i)hmcl|org\.jackhuang'
}
if ($existingLauncher) {
    throw 'HMCL is already running. Fully exit HMCL and run this script again; an existing launcher cannot inherit NVIDIA validation settings.'
}
try {
    # A launcher that is already running will not inherit this environment. Exit it first.
    $env:NV_ALLOW_RAYTRACING_VALIDATION = '1'
    Write-Host 'Start the test instance and press F8. Look for NVIDIA driver RT validation ENABLED in latest.log.'
    & $resolvedLauncher
} finally {
    $env:NV_ALLOW_RAYTRACING_VALIDATION = $previous
}
