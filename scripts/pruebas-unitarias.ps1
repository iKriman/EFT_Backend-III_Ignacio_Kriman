$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
# Docker puede escribir avisos informativos en stderr en Windows PowerShell 5.1.
# Se comprueba el codigo de salida real, no la presencia de texto en stderr.
$root = (Get-Location).Path
New-Item -ItemType Directory -Force docs/evidencias | Out-Null
$previousPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    docker run --rm -v "${root}:/workspace" -v banco-xyz-maven:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B test 2>&1 | Tee-Object docs/evidencias/pruebas-unitarias.txt
    $dockerExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousPreference
}
if ($dockerExitCode -ne 0) { throw "Fallaron las pruebas o Docker (salida $dockerExitCode); revisar docs/evidencias/pruebas-unitarias.txt" }
