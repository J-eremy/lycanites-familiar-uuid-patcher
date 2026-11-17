# Patch a Lycanites Mobs 2.0.8.10 jar so that it reports a fixed Minecraft UUID
# to the author's online familiars service.
#
# Uses only PowerShell and .NET that ship with Windows 10/11. No Python, no Java,
# no internet connection needed.
#
# Normally started by Patch-Windows.bat. Can also be run directly:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\Patch-Jar.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\Patch-Jar.ps1 -Yes
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\Patch-Jar.ps1 -Uuid <uuid> -Yes

param(
    [string]$Uuid = '',
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'

$Root      = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$InDir     = Join-Path $Root 'put-jar-here'
$OutDir    = Join-Path $Root 'patched'
$ClassDir  = Join-Path $Root 'classes'
$ConfigName = 'lycanitesmobs-familiar-uuid.txt'
$ModVersion = '2.0.8.10'
$DefaultUuid = 'a0aed996-9645-44e2-9468-5679fc25f44a'
$UuidPattern = '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'

$Pkg     = 'com/lycanitesmobs/core/pets/'
# Note: each element is parenthesised on purpose. Without the parentheses the
# comma binds tighter than '+' in PowerShell and the whole array collapses into
# one space-separated string.
$Replace = @(
    ($Pkg + 'PlayerFamiliars.class'),
    ($Pkg + 'PlayerFamiliars$FamiliarLoader.class'),
    ($Pkg + 'PlayerFamiliars$TrustAllCertificates.class')
)
$Drop     = @(($Pkg + 'PlayerFamiliars$1.class'))
$Marker   = $Pkg + 'PlayerFamiliars$TrustAllCertificates.class'
$Required = $Pkg + 'PlayerFamiliars.class'

function Fail([string]$Message) {
    Write-Host ''
    Write-Host "ERROR: $Message" -ForegroundColor Red
    Write-Host ''
    exit 1
}

function Initialize-ZipAssemblies {
    try { Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop } catch { }
    try { Add-Type -AssemblyName System.IO.Compression -ErrorAction Stop } catch { }
}

function Get-ZipEntryNames([string]$Path) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $names = @()
        foreach ($entry in $zip.Entries) { $names += $entry.FullName }
        return $names
    }
    finally { $zip.Dispose() }
}

function Read-ZipText([string]$Path, [string]$EntryName) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = $zip.GetEntry($EntryName)
        if ($null -eq $entry) { return $null }
        $stream = $entry.Open()
        try {
            $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
            try { return $reader.ReadToEnd() }
            finally { $reader.Dispose() }
        }
        finally { $stream.Dispose() }
    }
    finally { $zip.Dispose() }
}

function Get-FirstSetting([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    foreach ($line in Get-Content -LiteralPath $Path) {
        $value = $line.Trim().Trim([char]0xFEFF)
        if ($value -ne '' -and -not $value.StartsWith('#')) { return $value }
    }
    return $null
}

Initialize-ZipAssemblies

# ------------------------------------------------------- check our own files

foreach ($name in $Replace) {
    $local = [System.IO.Path]::Combine($ClassDir, $name)
    if (-not (Test-Path -LiteralPath $local)) {
        Fail ("The 'classes' folder is incomplete or damaged (missing " + $name +
              "). Re-download the patcher.")
    }
}

# ---------------------------------------------------------------- find the jar

if (-not (Test-Path -LiteralPath $InDir)) {
    Fail "The folder 'put-jar-here' is missing. Re-download the patcher."
}

$jars = @(Get-ChildItem -LiteralPath $InDir -Filter '*.jar' | Where-Object { -not $_.PSIsContainer })
if ($jars.Count -eq 0) {
    Write-Host ''
    Write-Host "No jar found in: $InDir"
    Write-Host ''
    Write-Host 'Put your Lycanites Mobs jar (the file ending in .jar) into that folder'
    Write-Host 'and run this again.'
    exit 1
}
if ($jars.Count -gt 1) {
    Write-Host ''
    Write-Host "More than one jar is in the 'put-jar-here' folder:"
    foreach ($jar in $jars) { Write-Host ('   ' + $jar.Name) }
    Write-Host ''
    Write-Host 'Keep only the Lycanites Mobs jar in there and run this again.'
    exit 1
}
$source = $jars[0]

# ------------------------------------------------------------- check the jar

$names = Get-ZipEntryNames $source.FullName
if ($names -notcontains $Required) {
    Fail "This does not look like a Lycanites Mobs jar (the mod's classes are missing)."
}

$version = $null
$info = Read-ZipText $source.FullName 'mcmod.info'
if ($info) {
    try {
        $data = $info | ConvertFrom-Json
        foreach ($entry in @($data)) {
            if ($entry.PSObject.Properties.Name -contains 'modid' -and $entry.modid -eq 'lycanitesmobs') {
                $version = $entry.version
                break
            }
        }
    }
    catch { }
}
if ($version -and ($version -notlike "*$ModVersion*")) {
    Fail ("This patcher is built for Lycanites Mobs $ModVersion, but your jar says: $version`n" +
          "Patching a different version can crash the game, so it was stopped.`n" +
          "You need the $ModVersion jar for Minecraft 1.12.2.")
}

# ---------------------------------------------------------------- pick a UUID

$default = $DefaultUuid
$configured = Get-FirstSetting (Join-Path $Root 'UUID.txt')
if ($configured -and ($configured -match $UuidPattern)) {
    $default = $configured.ToLower()
}

if ($Uuid -ne '') {
    if ($Uuid -notmatch $UuidPattern) { Fail "Not a valid UUID: $Uuid" }
    $uuid = $Uuid.ToLower()
}
elseif ($Yes -or (-not [Environment]::UserInteractive)) {
    $uuid = $default
}
else {
    Write-Host ''
    Write-Host 'Which Minecraft UUID should the mod report?'
    Write-Host "(Press Enter to use: $default)"
    $uuid = $null
    for ($attempt = 0; $attempt -lt 3 -and (-not $uuid); $attempt++) {
        $answer = (Read-Host 'UUID').Trim()
        if ($answer -eq '') { $uuid = $default }
        elseif ($answer -match $UuidPattern) { $uuid = $answer.ToLower() }
        else { Write-Host "That is not a valid UUID. Example: $DefaultUuid" }
    }
    if (-not $uuid) { Fail 'No valid UUID entered.' }
}

# ---------------------------------------------------------------- patch it

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$destination = Join-Path $OutDir $source.Name
$alreadyPatched = $names -contains $Marker

Write-Host ''
Write-Host ("Patching " + $source.Name + " ...")

Copy-Item -LiteralPath $source.FullName -Destination $destination -Force

try {
    if (-not $alreadyPatched) {
        $zip = [System.IO.Compression.ZipFile]::Open($destination, [System.IO.Compression.ZipArchiveMode]::Update)
        try {
            foreach ($name in $Replace) {
                $existing = $zip.GetEntry($name)
                if ($null -ne $existing) { $existing.Delete() }

                $entry = $zip.CreateEntry($name, [System.IO.Compression.CompressionLevel]::Optimal)
                $stream = $entry.Open()
                try {
                    $local = [System.IO.Path]::Combine($ClassDir, $name)
                    $bytes = [System.IO.File]::ReadAllBytes($local)
                    $stream.Write($bytes, 0, $bytes.Length)
                }
                finally { $stream.Dispose() }
            }
            foreach ($name in $Drop) {
                $existing = $zip.GetEntry($name)
                if ($null -ne $existing) { $existing.Delete() }
            }
        }
        finally { $zip.Dispose() }
    }

    $configPath = Join-Path $OutDir $ConfigName
    $configLines = @(
        '# Minecraft UUID reported to the Lycanites familiars service.',
        '# This file must sit next to the patched mod jar in your mods folder.',
        '# Delete it to fall back to the built-in default.',
        $uuid
    )
    Set-Content -LiteralPath $configPath -Value $configLines -Encoding ASCII

    # ------------------------------------------------------------ verify it

    $outNames = Get-ZipEntryNames $destination
    foreach ($name in $Replace) {
        if ($outNames -notcontains $name) {
            throw "missing $name"
        }
    }
    foreach ($name in $Drop) {
        if ($outNames -contains $name) {
            throw "stale $name still present"
        }
    }
}
catch {
    # Never leave a half-written jar behind: it must not reach the mods folder.
    if (Test-Path -LiteralPath $destination) {
        Remove-Item -LiteralPath $destination -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath (Join-Path $OutDir $ConfigName)) {
        Remove-Item -LiteralPath (Join-Path $OutDir $ConfigName) -Force -ErrorAction SilentlyContinue
    }
    Fail ("Patching failed: " + $_.Exception.Message + " Nothing was left in the 'patched' folder.")
}

# ---------------------------------------------------------------- tell them

Write-Host ''
if ($alreadyPatched) {
    Write-Host 'That jar was already patched. A copy was written to:'
}
else {
    Write-Host 'Done. Patched jar written to:'
}
Write-Host ('   ' + $destination) -ForegroundColor Green
Write-Host ''
Write-Host 'It will report this UUID:'
Write-Host ('   ' + $uuid) -ForegroundColor Green
Write-Host ''
Write-Host 'NEXT STEPS'
Write-Host "  1. Open your Minecraft 'mods' folder (the one that already contains"
Write-Host '     your Lycanites Mobs jar).'
Write-Host '  2. DELETE or move away the old lycanitesmobs jar. Do not keep both;'
Write-Host '     two copies of the mod will crash the game.'
Write-Host "  3. Copy these two files from the 'patched' folder into 'mods':"
Write-Host ('        ' + $source.Name)
Write-Host ('        ' + $ConfigName)
Write-Host '  4. Start Minecraft 1.12.2 with Forge and log in.'
Write-Host ''
Write-Host "Check progress in the log file 'logs/latest.log'. You should see a line"
Write-Host ('containing: Reporting UUID ' + $uuid)
Write-Host ''
