[CmdletBinding()]
param(
    [string]$GameDirectory = 'C:\Program Files (x86)\mc\DS2\.minecraft\versions\26.2-NeoForge_26.2.0.88',
    [string]$InstanceDirectory,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradle = Join-Path $repo 'gradlew.bat'

if (-not (Test-Path -LiteralPath $GameDirectory -PathType Container)) {
    throw "Game directory does not exist: $GameDirectory. Use -GameDirectory."
}
if ([string]::IsNullOrWhiteSpace($InstanceDirectory)) {
    $InstanceDirectory = Join-Path $GameDirectory 'mods'
}

$configDirectory = Join-Path $GameDirectory 'config'
$config = Join-Path $configDirectory 'fml.toml'
New-Item -ItemType Directory -Force -Path $configDirectory | Out-Null

$content = if (Test-Path -LiteralPath $config) {
    Get-Content -LiteralPath $config -Raw -Encoding UTF8
} else {
    ''
}
if ($content -match '(?m)^\s*earlyWindowControl\s*=') {
    $content = [regex]::Replace($content, '(?m)^\s*earlyWindowControl\s*=.*$', 'earlyWindowControl = false')
} else {
    $content = $content.TrimEnd() + [Environment]::NewLine + 'earlyWindowControl = false' + [Environment]::NewLine
}
Set-Content -LiteralPath $config -Value $content -Encoding UTF8

Write-Host "Configured Vulkan startup: $config" -ForegroundColor Green
Write-Host 'PCL launch arguments must include: --graphicsBackend vulkan' -ForegroundColor Yellow

if ($SkipBuild) {
    Write-Host 'Configuration only; skipped build and installation.' -ForegroundColor Yellow
    exit 0
}

$arguments = @(
    'installToInstance',
    "-Pgame_directory=$GameDirectory",
    "-Pinstance_directory=$InstanceDirectory",
    '--no-configuration-cache'
)

Push-Location $repo
try {
    & $gradle @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle installation failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

Write-Host "RTest installed to: $InstanceDirectory" -ForegroundColor Green
Write-Host 'Use NeoForge 26.2 and keep --graphicsBackend vulkan in the PCL launch arguments.'
