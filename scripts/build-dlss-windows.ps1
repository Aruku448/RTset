param(
    [Parameter(Mandatory=$true)][string]$Compiler,
    [string]$SdkArchive,
    [switch]$GpuTest,
    [switch]$CoreValidationOnly
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
try {
    $lockPath = Join-Path $root 'third_party/streamline/sdk-lock.json'
    $lock = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json
    $runtime = Join-Path $root 'src/main/resources/rtest/natives/windows-x86_64/streamline'
    if ($SdkArchive) {
        if ((Get-FileHash -LiteralPath $SdkArchive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $lock.archiveSha256) {
            throw 'Streamline SDK archive hash differs from the pinned release'
        }
        $sdk = Join-Path $root 'tmp/streamline-sdk-build'
        Expand-Archive -LiteralPath $SdkArchive -DestinationPath $sdk -Force
        foreach ($property in $lock.runtimeHashes.PSObject.Properties) {
            if ($property.Name -eq 'rtest_dlss.dll') { continue }
            $source = Join-Path $sdk ('bin/x64/' + $property.Name)
            if ((Get-FileHash -LiteralPath $source).Hash.ToLowerInvariant() -ne $property.Value) { throw ('Runtime hash mismatch: ' + $property.Name) }
            Copy-Item -LiteralPath $source -Destination $runtime
        }
    }
    $output = Join-Path $runtime 'rtest_dlss.dll'
    & $Compiler -std=c++17 -O2 -shared -static '-Wl,--no-insert-timestamp' -Ithird_party/streamline/include -Ithird_party/streamline/vulkan-headers/include native/dlss/rtest_dlss.cpp -o $output
    if ($LASTEXITCODE -ne 0) { throw 'DLSS native compilation failed' }
    $lock.runtimeHashes.'rtest_dlss.dll' = (Get-FileHash -LiteralPath $output).Hash.ToLowerInvariant()
    $lock | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $lockPath -Encoding utf8
    if ($GpuTest) {
        # Keep the test next to its plugins for the executable-relative loader. Always remove it.
        $fixture = Join-Path $runtime 'rtest-dlss-gpu-test.exe'
        try {
            & $Compiler -std=c++17 -O2 -static -Ithird_party/streamline/include -Ithird_party/streamline/vulkan-headers/include native/dlss/rtest_dlss_gpu_test.cpp -o $fixture
            if ($LASTEXITCODE -ne 0) { throw 'DLSS GPU fixture compilation failed' }
            $arguments = @(); if ($CoreValidationOnly) { $arguments += '--core-validation' }
            & $fixture @arguments
            if ($LASTEXITCODE -ne 0) { throw ('DLSS GPU fixture failed: ' + $LASTEXITCODE) }
        } finally { if (Test-Path -LiteralPath $fixture) { Remove-Item -LiteralPath $fixture } }
    }
} finally { Pop-Location }
