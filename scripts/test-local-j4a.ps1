param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $J4aArgs
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$jar = Get-ChildItem -LiteralPath (Join-Path $repoRoot "build\libs") -Filter "*-all.jar" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($null -eq $jar) {
    Push-Location $repoRoot
    try {
        & .\gradlew.bat shadowJar --no-daemon
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    } finally {
        Pop-Location
    }

    $jar = Get-ChildItem -LiteralPath (Join-Path $repoRoot "build\libs") -Filter "*-all.jar" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

if ($null -eq $jar) {
    throw "No runnable j4a jar found under build\libs after shadowJar."
}

if ($J4aArgs.Count -eq 0) {
    $J4aArgs = @("--help")
}

Write-Host "Using jar: $($jar.FullName)"
& java -jar $jar.FullName @J4aArgs
exit $LASTEXITCODE
