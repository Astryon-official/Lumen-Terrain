# Generates an import library for the system OpenCL.dll by extracting
# exported symbol names from its PE export table. No admin required.
param(
    [string]$Dll = "C:\Windows\System32\OpenCL.dll",
    [string]$OutDef = "native\third_party\opencl.def"
)

$bytes = [System.IO.File]::ReadAllBytes($Dll)

# --- DOS header ---
$e_lfanew = [BitConverter]::ToInt32($bytes, 0x3C)

# --- COFF header follows 'PE\0\0' ---
$peOffset = $e_lfanew + 4
$numberOfSections = [BitConverter]::ToUInt16($bytes, $peOffset + 2)
$optionalHeaderSize = [BitConverter]::ToUInt16($bytes, $peOffset + 16)
$optionalOffset = $peOffset + 20

$magic = [BitConverter]::ToUInt16($bytes, $optionalOffset)
if ($magic -ne 0x20B) { throw "Not a PE32+ image" }

$dataDirOffset = $optionalOffset + 112   # PE32+ data directories start
$exportDirRva = [BitConverter]::ToUInt32($bytes, $dataDirOffset)
$exportDirSize = [BitConverter]::ToUInt32($bytes, $dataDirOffset + 4)

if ($exportDirRva -eq 0) { throw "No export directory in $Dll" }

# --- section table for RVA->file offset mapping ---
$sectionsOffset = $optionalOffset + $optionalHeaderSize
$sections = @()
for ($i = 0; $i -lt $numberOfSections; $i++) {
    $s = $sectionsOffset + $i * 40
    $sections += @{
        VA   = [BitConverter]::ToUInt32($bytes, $s + 12)
        Size = [BitConverter]::ToUInt32($bytes, $s + 8)
        Raw  = [BitConverter]::ToUInt32($bytes, $s + 20)
    }
}

function RvaToOffset([uint32]$rva) {
    foreach ($sec in $script:sections) {
        if ($rva -ge $sec.VA -and $rva -lt ($sec.VA + $sec.Size)) {
            return ($rva - $sec.VA + $sec.Raw)
        }
    }
    throw "RVA $rva not mapped"
}

# --- export directory ---
$ed = RvaToOffset $exportDirRva
$numberOfNames = [BitConverter]::ToUInt32($bytes, $ed + 24)
$namesRva = [BitConverter]::ToUInt32($bytes, $ed + 32)
$namesOffset = RvaToOffset $namesRva

$names = New-Object System.Collections.Generic.List[string]
for ($i = 0; $i -lt $numberOfNames; $i++) {
    $nameRva = [BitConverter]::ToUInt32($bytes, $namesOffset + $i * 4)
    $p = RvaToOffset $nameRva
    $end = $p
    while ($bytes[$end] -ne 0) { $end++ }
    $names.Add([System.Text.Encoding]::ASCII.GetString($bytes, $p, $end - $p))
}

$dir = Split-Path -Parent $OutDef
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("LIBRARY OpenCL")
$lines.Add("EXPORTS")
foreach ($n in $names) { $lines.Add("    $n") }
[System.IO.File]::WriteAllLines((Join-Path (Get-Location) $OutDef), $lines)

Write-Output ("Wrote {0} with {1} exports" -f $OutDef, $names.Count)
