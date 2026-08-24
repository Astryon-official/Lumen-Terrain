# LTE A/B terrain-generation benchmark driver.
# Boots a dedicated server fresh-world per repetition, drives a fixed
# forceload workload over RCON, times it, samples CPU/RAM, appends CSV.
param(
    [Parameter(Mandatory=$true)][string]$InstanceDir,
    [Parameter(Mandatory=$true)][string]$ConfigName,
    [int]$Reps = 3,
    [string]$JavaExe = 'C:\Users\heath\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe',
    [string]$OutCsv = 'C:\Users\heath\lte-bench\results\bench-results.csv',
    [string]$LogDir = 'C:\Users\heath\lte-bench\logs'
)

$ErrorActionPreference = 'Stop'
$RconPort = 25575
$RconPass = 'ltebench2026'
# Coordinates are BLOCK coords; forceload converts to chunks (256-chunk cap/call).
$WarmupCmd    = 'forceload add -128 -128 127 127'   # 16x16 chunks around spawn
$WarmupTarget = 256
# Timed region: chunks [16..47]x[16..47] = 32x32 = 1024, disjoint from warmup/spawn.
$TimedCmds    = @(
    'forceload add 256 256 511 511',
    'forceload add 512 256 767 511',
    'forceload add 256 512 511 767',
    'forceload add 512 512 767 767'
)
$TimedTarget  = 1024
$JvmArgs = @('-Xms4096m','-Xmx4096m','-XX:+UseG1GC','-XX:MaxGCPauseMillis=50')

function Write-Log($msg) {
    $stamp = (Get-Date).ToString('HH:mm:ss')
    Write-Host "[$stamp] $msg"
}

function Test-RconUp {
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $c.Connect('127.0.0.1', $RconPort)
        $c.Close()
        return $true
    } catch { return $false }
}

# Returns payload string of first response after auth.
function Invoke-Rcon([string[]]$cmds) {
    & (Join-Path $PSScriptRoot 'rcon.ps1') -Port $RconPort -Pass $RconPass -Commands $cmds
}

function Wait-StableCount([int]$target, [int]$timeoutSec, [ref]$elapsedSec) {
    $swTotal = [Diagnostics.Stopwatch]::StartNew()
    $stable = 0; $last = -1
    while ($swTotal.Elapsed.TotalSeconds -lt $timeoutSec) {
        Start-Sleep -Milliseconds 700
        $q = Invoke-Rcon @('forceload query')
        $cnt = $null
        foreach ($line in $q) {
            if ($line -match '(\d+)\s+force') {
                if (-not $cnt -or $Matches[1] -gt $cnt) { $cnt = $Matches[1] }
            }
        }
        if ($null -eq $cnt) { throw "unparseable forceload query: $($q -join '; ')" }
        if ([int]$cnt -ge $target -and [int]$cnt -eq $last) { $stable++ } else { $stable = 0 }
        $last = [int]$cnt
        if ($stable -ge 2) {
            $elapsedSec.Value = [math]::Round($swTotal.Elapsed.TotalSeconds, 2)
            return $true
        }
    }
    return $false
}

if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }
$resultsDir = Split-Path $OutCsv
if (-not (Test-Path $resultsDir)) { New-Item -ItemType Directory -Path $resultsDir | Out-Null }
if (-not (Test-Path $OutCsv)) {
    Add-Content -Path $OutCsv -Value 'config,rep,chunks,time_s,cps,ms_per_chunk,cpu_avg_pct,cpu_peak_pct,ws_avg_gb,ws_peak_gb,status,lte_log_evidence' -Encoding utf8
}

$java = (Get-Item $JavaExe).FullName.Trim()
$serverJar = Join-Path $InstanceDir 'fabric-server-launch.jar'

for ($rep = 1; $rep -le $Reps; $rep++) {

    Write-Log "=== $ConfigName rep $rep : resetting world ==="
    $world = Join-Path $InstanceDir 'world'
    if (Test-Path $world) { Remove-Item -Recurse -Force $world }

    $outLog = Join-Path $LogDir ("{0}-r{1}-out.log" -f $ConfigName, $rep)
    $errLog = Join-Path $LogDir ("{0}-r{1}-err.log" -f $ConfigName, $rep)

    Write-Log "starting server..."
    $proc = Start-Process -FilePath $java `
        -ArgumentList (@($JvmArgs) + @('-jar', 'fabric-server-launch.jar', 'nogui')) `
        -WorkingDirectory $InstanceDir `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog `
        -PassThru -WindowStyle Hidden

    try {
        # ---- boot wait ----
        $bootSw = [Diagnostics.Stopwatch]::StartNew()
        while (-not (Test-RconUp)) {
            if ($proc.HasExited) { throw "server exited during boot (code $($proc.ExitCode)); see $outLog" }
            if ($bootSw.Elapsed.TotalMinutes -gt 6) { throw 'rcon never came up' }
            Start-Sleep -Milliseconds 800
        }
        Start-Sleep -Seconds 4   # let lifecycle events settle
        Write-Log ("rcon up after {0:n1}s" -f $bootSw.Elapsed.TotalSeconds)

        # ---- JIT warmup (untimed) ----
        Write-Log 'warmup: forceload 256-chunk spawn area...'
        [void](Invoke-Rcon @($WarmupCmd))
        $e = 0
        if (-not (Wait-StableCount $WarmupTarget 600 ([ref]$e))) { throw 'warmup never stabilized' }
        Write-Log ("warmup done ({0:n1}s, untimed)" -f $e)

        # ---- locate perf-counter instance for this PID ----
        $idSample = (Get-Counter '\Process(*)\ID Process' -ErrorAction SilentlyContinue)
        $inst = $idSample.CounterSamples |
            Where-Object { $_.CookedValue -eq $proc.Id } |
            Select-Object -First 1 -ExpandProperty InstanceName
        if (-not $inst) { throw "perf counter instance not found for pid $($proc.Id)" }
        $cpuPath  = "\Process($inst)\% Processor Time"
        $wsPath   = "\Process($inst)\Working Set"
        Write-Log "counter instance: $inst"

        # ---- TIMED PHASE ----
        Write-Log 'timed phase: forceload 1024 chunks (4 quadrants)...'
        [void](Invoke-Rcon $TimedCmds)

        $t0 = Get-Date
        $cpuVals = New-Object System.Collections.Generic.List[double]
        $wsVals  = New-Object System.Collections.Generic.List[double]

        $e = 0
        $done = $false
        while (-not $done) {
            Start-Sleep -Seconds 1

            $s = Get-Counter -Counter @($cpuPath, $wsPath) -ErrorAction SilentlyContinue
            if ($s) {
                $cpuVals.Add([double]($s.CounterSamples[0].CookedValue))
                $wsVals.Add(([double]($s.CounterSamples[1].CookedValue)) / 1GB)
            }

            $done = Wait-StableCount $TimedTarget 90 ([ref]$e)
            if ((Get-Date) - $t0 -gt [TimeSpan]::FromMinutes(25)) { throw 'timed phase timeout' }
        }
        $t1 = Get-Date
        $genSec = [math]::Round(($t1 - $t0).TotalSeconds, 2)

        # ---- stop cleanly ----
        [void](Invoke-Rcon @('stop'))
    }
    finally {
        if (-not $proc.HasExited) {
            Start-Sleep -Seconds 3
            if (-not $proc.HasExited) { $proc.Kill() }
        }
    }
    $proc.WaitForExit()

    Start-Sleep -Seconds 2   # counters flush
    $cpuAvg = if ($cpuVals.Count) { [math]::Round(($cpuVals | Measure-Object -Average).Average / 12, 1) } else { -1 }
    $cpuPk  = if ($cpuVals.Count) { [math]::Round(($cpuVals | Measure-Object -Maximum).Maximum / 12, 1) } else { -1 }
    $wsAvg  = if ($wsVals.Count)  { [math]::Round(($wsVals  | Measure-Object -Average).Average, 2) } else { -1 }
    $wsPk   = if ($wsVals.Count)  { [math]::Round(($wsVals  | Measure-Object -Maximum).Maximum, 2) } else { -1 }

    $chunks = $TimedTarget
    $cps    = [math]::Round($chunks / $genSec, 1)
    $msChk  = [math]::Round($genSec * 1000 / $chunks, 2)

    # LTE evidence from this rep's server log
    $lteLines = Select-String -Path $outLog -Pattern '\[LTE\]|OpenCL|LumenTerrainEngine' |
                Select-Object -ExpandProperty Line -Unique
    $lteSummary = ($lteLines | Select-Object -First 25) -join ' || '

    $row = ('{0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10},"{11}"' -f `
        $ConfigName, $rep, $chunks, $genSec, $cps, $msChk,
        $cpuAvg, $cpuPk, $wsAvg, $wsPk, 'ok', ($lteSummary -replace '"', "'"))
    Add-Content -Path $OutCsv -Value $row -Encoding utf8
    Write-Log ("RESULT {0} r{1}: {2}s, {3} cps, {4} ms/chk, cpuavg {5}% , ws {6}->{7} GB" -f `
        $ConfigName, $rep, $genSec, $cps, $msChk, $cpuAvg, $wsAvg, $wsPk)
}

Write-Log 'all reps complete'
