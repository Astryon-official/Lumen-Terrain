# Manual diagnostic: boot A-vanilla, issue forceload add, trace counts.
$ErrorActionPreference = 'Stop'
$dir = 'C:\Users\heath\lte-bench\A-vanilla'
$java = 'C:\Users\heath\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe'

if (Test-Path "$dir\world") { Remove-Item -Recurse -Force "$dir\world" }

$p = Start-Process -FilePath $java `
    -ArgumentList @('-Xms4096m','-Xmx4096m','-jar','fabric-server-launch.jar','nogui') `
    -WorkingDirectory $dir `
    -RedirectStandardOutput 'C:\Users\heath\lte-bench\logs\diag-out.log' `
    -RedirectStandardError 'C:\Users\heath\lte-bench\logs\diag-err.log' `
    -PassThru -WindowStyle Hidden
Write-Output "pid=$($p.Id)"

while ($true) {
    Start-Sleep -Seconds 2
    if ($p.HasExited) { throw 'server exited' }
    try {
        $c = New-Object System.Net.Sockets.TcpClient('127.0.0.1', 25575)
        $c.Close(); break
    } catch { Write-Output 'waiting for rcon...' }
}
Start-Sleep -Seconds 4

$rcon = 'C:\Users\heath\Lumen-Terrain\tools\bench\rcon.ps1'
Write-Output '--- ADD RESPONSE ---'
& $rcon -Commands "forceload add -8 -8 7 7"
Start-Sleep -Seconds 10
for ($i = 0; $i -lt 8; $i++) {
    Write-Output "--- poll $i ---"
    & $rcon -Commands "forceload query"
    Start-Sleep -Seconds 5
}
& $rcon -Commands "stop"
Start-Sleep -Seconds 8
if (-not $p.HasExited) { $p.Kill() }
Write-Output 'DIAG-DONE'
