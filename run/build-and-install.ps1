$ErrorActionPreference = 'Stop'
# Builds Logic Blocks with the Blueprints project's tools, installs the plugin on the Blueprints test server,
# and copies the client mod to run\client-mods. Expects blueprints-mc next to this project (or around it).
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
# blueprints-mc is usually next to this project; also accept this project sitting anywhere inside it.
$blueprints = [IO.Path]::GetFullPath((Join-Path $project '../blueprints-mc'))
if (-not (Test-Path (Join-Path $blueprints '.tools'))) {
    $dir = Split-Path $project -Parent
    while ($dir -and -not (Test-Path (Join-Path $dir '.tools/jdk'))) { $dir = Split-Path $dir -Parent }
    if ($dir) { $blueprints = $dir }
}
$server = Join-Path $blueprints 'run/local-paper'
if (-not (Test-Path $server)) { Write-Host "Can't find the test server at $server" -ForegroundColor Red; exit 1 }
try { $s = [IO.File]::Open((Join-Path $server 'blueprints-test-world/session.lock'), 'Open', 'ReadWrite', 'None'); $s.Close() }
catch [IO.FileNotFoundException] { } catch { Write-Host 'The test server is still running. Type stop in its console first, then run this again.' -ForegroundColor Red; exit 1 }
$jdk = Get-ChildItem -LiteralPath (Join-Path $blueprints '.tools/jdk') -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'bin/java.exe') } | Select-Object -First 1
if ($null -eq $jdk) { throw 'JDK missing in blueprints-mc\.tools\jdk' }
$env:JAVA_HOME = $jdk.FullName
$env:GRADLE_USER_HOME = Join-Path $blueprints '.tools/gradle-home'

Write-Host '1/2 Building the Logic Blocks plugin and running its tests...' -ForegroundColor Cyan
Push-Location $project
& (Join-Path $blueprints '.tools/gradle/gradle-9.1.0/bin/gradle.bat') build --console=plain
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { Write-Host "PLUGIN BUILD OR TESTS FAILED (exit $code). Report: logic-plugin\build\reports\tests\test\index.html" -ForegroundColor Red; exit $code }
Copy-Item -LiteralPath (Join-Path $project 'logic-plugin/build/libs/logic-plugin-0.1.0.jar') -Destination (Join-Path $server 'plugins/LogicBlocks.jar') -Force
Write-Host 'Plugin installed on the test server.' -ForegroundColor Green

Write-Host '2/2 Building the Fabric client mod...' -ForegroundColor Cyan
$client = Join-Path $project 'fabric-client'
Push-Location $client
& .\gradlew.bat build --console=plain
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { Write-Host "CLIENT BUILD FAILED (exit $code)." -ForegroundColor Red; exit $code }
$mods = Join-Path $PSScriptRoot 'client-mods'
New-Item -ItemType Directory -Force -Path $mods | Out-Null
Get-ChildItem $mods -Filter '*.jar' | Remove-Item -Force
Get-ChildItem (Join-Path $client 'build/libs') -Filter 'logic-blocks-client-*.jar' | Where-Object { $_.Name -notmatch 'sources' } | Copy-Item -Destination $mods
Write-Host "Client mod copied to: $mods" -ForegroundColor Green
Write-Host 'Copy it into your Fabric 26.2 mods folder (next to Fabric API and the Blueprints mod).' -ForegroundColor Green
Write-Host 'BUILD PASSED. Start the server with start.cmd in blueprints-mc\run\local-paper.' -ForegroundColor Green
