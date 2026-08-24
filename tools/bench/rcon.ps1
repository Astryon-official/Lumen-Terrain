# Minimal Minecraft RCON client (Source RCON protocol).
param(
    [string]$RconHost = '127.0.0.1',
    [int]$Port = 25575,
    [string]$Pass = 'ltebench2026',
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Commands
)

$ErrorActionPreference = 'Stop'

function Read-Exact([System.Net.Sockets.NetworkStream]$s, [int]$n) {
    $buf = New-Object byte[] $n
    $off = 0
    while ($off -lt $n) {
        $r = $s.Read($buf, $off, $n - $off)
        if ($r -le 0) { throw 'connection closed' }
        $off += $r
    }
    return ,$buf
}

function Read-Packet([System.Net.Sockets.NetworkStream]$s) {
    $lenBuf = Read-Exact $s 4
    $len = [BitConverter]::ToInt32($lenBuf, 0)
    $body = Read-Exact $s $len
    $id   = [BitConverter]::ToInt32($body, 0)
    $type = [BitConverter]::ToInt32($body, 4)
    $payload = [Text.Encoding]::UTF8.GetString($body, 8, $len - 10)
    return [pscustomobject]@{ id = $id; type = $type; payload = $payload }
}

function Send-Packet([System.Net.Sockets.NetworkStream]$s, [int]$id, [int]$type, [string]$payload) {
    $pl   = [Text.Encoding]::UTF8.GetBytes($payload)
    $size = 4 + 4 + $pl.Length + 2
    $ms   = New-Object System.IO.MemoryStream
    $b    = New-Object System.IO.BinaryWriter($ms)
    $b.Write([int]$size); $b.Write([int]$id); $b.Write([int]$type)
    $b.Write($pl); $b.Write([byte]0); $b.Write([byte]0)
    $b.Flush()
    $s.Write($ms.ToArray(), 0, $ms.Length)
}

$client = New-Object System.Net.Sockets.TcpClient($RconHost, $Port)
$stream = $client.GetStream()
$stream.ReadTimeout = 30000

Send-Packet $stream 1 3 $Pass
$auth = Read-Packet $stream
if ($auth.id -eq -1) { throw 'rcon auth failed' }

$n = 10
foreach ($cmd in $Commands) {
    Send-Packet $stream $n 2 $cmd
    $resp = Read-Packet $stream
    Write-Output ("OK<{0}> {1}" -f $cmd, $resp.payload.Trim())
    $n++
}

$stream.Close(); $client.Close()
