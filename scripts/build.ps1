param([ValidateSet('debug','release','test')][string]$Task = 'debug')
$ErrorActionPreference = 'Stop'
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    $tasks = switch ($Task) { debug { @('assembleDebug') }; release { @('assembleRelease') }; test { @('testDebugUnitTest','lintDebug') } }
    & docker compose run --rm android-builder ./gradlew --no-daemon @tasks
    if ($LASTEXITCODE -ne 0) { throw "Container build failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }
