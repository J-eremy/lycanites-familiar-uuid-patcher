# Maintainer build (Windows): recompiles the patched class from source, rewrites
# the jar, verifies it, runs the tests, and refreshes the shipped prebuilt classes.
#
# Requires a JDK. End users do NOT run this; they run Patch-Windows.bat, which uses
# the prebuilt classes in ..\classes\.
#
# Usage: .\build.ps1 [original.jar] [output.jar]

param(
    [string]$Original = '..\put-jar-here\lycanitesmobs-1.12.2-2.0.8.10.jar',
    [string]$Output   = '..\patched\lycanitesmobs-1.12.2-2.0.8.10.jar'
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments)
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Exe exited with code $LASTEXITCODE"
    }
}

Push-Location $here
try {
    New-Item -ItemType Directory -Force -Path 'build\tool' | Out-Null
    Invoke-Native 'javac' @('-d', 'build\tool', 'tools\BuildTool.java')
    Invoke-Native 'java'  @('-cp', 'build\tool', 'BuildTool', 'all', $Original, $Output)

    New-Item -ItemType Directory -Force -Path '..\classes' | Out-Null
    Copy-Item -Path 'build\classes\com' -Destination '..\classes' -Recurse -Force
    Write-Host 'refreshed ..\classes\com\lycanitesmobs\core\pets\'
}
finally {
    Pop-Location
}
