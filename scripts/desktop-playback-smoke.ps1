param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [int]$TimeoutSeconds = 120,
    [switch]$ReuseExisting,
    [switch]$KeepRunning
)

$ErrorActionPreference = "Stop"

function Write-Step($message) {
    Write-Host "[smoke] $message"
}

function Send-CpCommand($port, $json) {
    $client = [Net.Sockets.TcpClient]::new("127.0.0.1", $port)
    try {
        $stream = $client.GetStream()
        $encoding = [Text.UTF8Encoding]::new($false)
        $writer = [IO.StreamWriter]::new($stream, $encoding)
        $reader = [IO.StreamReader]::new($stream, $encoding)
        $writer.AutoFlush = $true
        $writer.WriteLine($json)
        return $reader.ReadLine()
    } finally {
        $client.Close()
    }
}

function Read-CpSession {
    $sessionPath = Join-Path $env:USERPROFILE ".fittrimmer_cp.json"
    if (-not (Test-Path $sessionPath)) {
        return $null
    }
    try {
        return (Get-Content $sessionPath -Raw | ConvertFrom-Json)
    } catch {
        return $null
    }
}

function Wait-CpSession($deadline) {
    while ((Get-Date) -lt $deadline) {
        $session = Read-CpSession
        if ($session -and $session.port -and $session.pid) {
            try {
                $null = Send-CpCommand $session.port '{"type":"get_state"}'
                return $session
            } catch {
            }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Control Plane did not become available within timeout."
}

function Find-Ffmpeg($repoRoot) {
    $candidates = @(
        (Join-Path $repoRoot "temp_work\bin\ffmpeg.exe"),
        (Join-Path $repoRoot "shared-core\build\processedResources\desktop\main\bin\ffmpeg.exe"),
        "ffmpeg.exe",
        "ffmpeg"
    )
    foreach ($candidate in $candidates) {
        $cmd = Get-Command $candidate -ErrorAction SilentlyContinue
        if ($cmd) {
            return $cmd.Source
        }
        if (Test-Path $candidate) {
            return (Resolve-Path $candidate).Path
        }
    }
    throw "ffmpeg was not found. Build or run once so temp_work\bin\ffmpeg.exe exists."
}

function Ensure-TestVideo($repoRoot) {
    $outDir = Join-Path $repoRoot "temp_work\smoke"
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
    $videoPath = Join-Path $outDir "playback_smoke.mp4"
    if ((Test-Path $videoPath) -and ((Get-Item $videoPath).Length -gt 1024)) {
        return $videoPath
    }

    $ffmpeg = Find-Ffmpeg $repoRoot
    Write-Step "Generating smoke video with $ffmpeg"
    $args = @(
        "-y",
        "-f", "lavfi",
        "-i", "testsrc2=size=640x360:rate=30",
        "-t", "8",
        "-c:v", "libopenh264",
        "-pix_fmt", "yuv420p",
        $videoPath
    )
    $p = Start-Process -FilePath $ffmpeg -ArgumentList $args -NoNewWindow -Wait -PassThru
    if ($p.ExitCode -ne 0 -or -not (Test-Path $videoPath)) {
        throw "Failed to generate smoke video. ffmpeg exit code: $($p.ExitCode)"
    }
    return $videoPath
}

function Get-AppWindowRect($appPid) {
    Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class SmokeWin32Rect {
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
}
"@
    $proc = Get-Process -Id $appPid -ErrorAction Stop
    $deadline = (Get-Date).AddSeconds(20)
    while ((Get-Date) -lt $deadline) {
        $proc.Refresh()
        if ($proc.MainWindowHandle -ne 0) {
            $rect = New-Object SmokeWin32Rect+RECT
            [SmokeWin32Rect]::GetWindowRect($proc.MainWindowHandle, [ref]$rect) | Out-Null
            if (($rect.Right - $rect.Left) -gt 400 -and ($rect.Bottom - $rect.Top) -gt 300) {
                return $rect
            }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Could not locate app window for pid $appPid."
}

function Click-At($x, $y) {
    Add-Type -AssemblyName System.Windows.Forms
    Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public class SmokeMouse {
    [DllImport("user32.dll")] public static extern void mouse_event(int dwFlags, int dx, int dy, int dwData, int dwExtraInfo);
}
"@
    [System.Windows.Forms.Cursor]::Position = New-Object System.Drawing.Point($x, $y)
    [SmokeMouse]::mouse_event(0x0002, 0, 0, 0, 0)
    Start-Sleep -Milliseconds 100
    [SmokeMouse]::mouse_event(0x0004, 0, 0, 0, 0)
}

function Get-State($port) {
    return (Send-CpCommand $port '{"type":"get_state"}' | ConvertFrom-Json)
}

function Assert-Advanced($port, $fromMs, $message) {
    $deadline = (Get-Date).AddSeconds(8)
    while ((Get-Date) -lt $deadline) {
        $state = Get-State $port
        if ($state.videoCurrentTimeMs -gt ($fromMs + 750)) {
            return $state.videoCurrentTimeMs
        }
        Start-Sleep -Milliseconds 400
    }
    $state = Get-State $port
    throw "$message Current=$($state.videoCurrentTimeMs), baseline=$fromMs"
}

function Assert-Stopped($port, $message) {
    $a = (Get-State $port).videoCurrentTimeMs
    Start-Sleep -Seconds 2
    $b = (Get-State $port).videoCurrentTimeMs
    if ([Math]::Abs($b - $a) -gt 250) {
        throw "$message Before=$a, after=$b"
    }
    return $b
}

function Assert-Near($actual, $expected, $tolerance, $message) {
    if ([Math]::Abs($actual - $expected) -gt $tolerance) {
        throw "$message Actual=$actual, expected=$expected, tolerance=$tolerance"
    }
}

function Assert-SeekNear($port, $targetMs, $toleranceMs, $message) {
    $deadline = (Get-Date).AddSeconds(6)
    while ((Get-Date) -lt $deadline) {
        $state = Get-State $port
        if ([Math]::Abs($state.videoCurrentTimeMs - $targetMs) -le $toleranceMs) {
            return $state.videoCurrentTimeMs
        }
        Start-Sleep -Milliseconds 250
    }
    $state = Get-State $port
    throw "$message Current=$($state.videoCurrentTimeMs), target=$targetMs, tolerance=$toleranceMs"
}

$deadline = (Get-Date).AddSeconds($TimeoutSeconds)
$session = Read-CpSession
$startedProcess = $null
if ($session) {
    try {
        $null = Send-CpCommand $session.port '{"type":"get_state"}'
    } catch {
        $session = $null
    }
}

if ($session -and -not $ReuseExisting) {
    throw "An existing FitTrimmer Control Plane session is active at port $($session.port), pid $($session.pid). Use -ReuseExisting or close it first."
}

if (-not $session) {
    Write-Step "Launching GUI"
    $outLog = Join-Path $env:TEMP "fittrimmer-smoke-run.out.log"
    $errLog = Join-Path $env:TEMP "fittrimmer-smoke-run.err.log"
    $startedProcess = Start-Process -FilePath (Join-Path $RepoRoot "gradlew.bat") `
        -ArgumentList ":composeApp:run" `
        -WorkingDirectory $RepoRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -PassThru
    Write-Step "Gradle launcher pid: $($startedProcess.Id)"
}

$session = Wait-CpSession $deadline
$port = [int]$session.port
Write-Step "Control Plane is ready on port $port, pid $($session.pid)"

$videoPath = Ensure-TestVideo $RepoRoot
$escapedVideo = $videoPath.Replace("\", "\\")
$setFiles = "{`"type`":`"set_files`",`"fit`":`"`",`"video`":`"$escapedVideo`",`"startUtc`":`"2026-01-01T00:00:00Z`"}"
Write-Step "Loading smoke video: $videoPath"
Send-CpCommand $port $setFiles | Out-Null

Start-Sleep -Seconds 3
$state = Get-State $port
if ($state.videoPath -ne $videoPath) {
    throw "Smoke video was not loaded. State videoPath=$($state.videoPath)"
}
if ($state.videoLengthMs -lt 7000 -or $state.videoLengthMs -gt 9000) {
    throw "Unexpected smoke video length. videoLengthMs=$($state.videoLengthMs)"
}
$videoLengthMs = [int]$state.videoLengthMs
Write-Step "Smoke video duration: ${videoLengthMs}ms"

Write-Step "Checking CP seek while paused"
Send-CpCommand $port '{"type":"pause"}' | Out-Null
Send-CpCommand $port '{"type":"seek","timeMs":3200}' | Out-Null
$seeked = Assert-SeekNear $port 3200 500 "CP seek while paused did not land near target."
Write-Step "CP seek while paused ok: $seeked"

Write-Step "Checking CP play/pause"
Send-CpCommand $port '{"type":"pause"}' | Out-Null
Send-CpCommand $port '{"type":"seek","timeMs":0}' | Out-Null
Start-Sleep -Milliseconds 500
$base = (Get-State $port).videoCurrentTimeMs
Send-CpCommand $port '{"type":"play"}' | Out-Null
$advanced = Assert-Advanced $port $base "CP play did not advance playback."
Send-CpCommand $port '{"type":"pause"}' | Out-Null
$stopped = Assert-Stopped $port "CP pause did not stop playback."
Write-Step "CP playback ok: $base -> $advanced -> $stopped"

Write-Step "Checking CP seek while playing"
Send-CpCommand $port '{"type":"pause"}' | Out-Null
Send-CpCommand $port '{"type":"seek","timeMs":1200}' | Out-Null
Start-Sleep -Milliseconds 500
Send-CpCommand $port '{"type":"play"}' | Out-Null
Start-Sleep -Milliseconds 900
Send-CpCommand $port '{"type":"seek","timeMs":5200}' | Out-Null
$seeked = Assert-SeekNear $port 5200 600 "CP seek while playing did not land near target."
$advancedAfterSeek = Assert-Advanced $port $seeked "Playback did not continue after seek while playing."
Send-CpCommand $port '{"type":"pause"}' | Out-Null
Write-Step "CP seek while playing ok: $seeked -> $advancedAfterSeek"

Write-Step "Checking preview click toggle"
Send-CpCommand $port '{"type":"capture"}' | Out-Null
Send-CpCommand $port '{"type":"pause"}' | Out-Null
Send-CpCommand $port '{"type":"seek","timeMs":0}' | Out-Null
Start-Sleep -Milliseconds 500
$rect = Get-AppWindowRect ([int]$session.pid)
$width = $rect.Right - $rect.Left
$height = $rect.Bottom - $rect.Top
$clickX = [int]($rect.Left + ($width * 0.62))
$clickY = [int]($rect.Top + ($height * 0.37))

$base = (Get-State $port).videoCurrentTimeMs
Click-At $clickX $clickY
$advanced = Assert-Advanced $port $base "Preview click did not start playback."
Click-At $clickX $clickY
$stopped = Assert-Stopped $port "Preview click did not stop playback."
Write-Step "Preview click playback ok: $base -> $advanced -> $stopped"

Write-Step "Checking EOF settles"
Send-CpCommand $port '{"type":"pause"}' | Out-Null
$nearEndSeek = [Math]::Max(0, $videoLengthMs - 400)
$seekJson = "{`"type`":`"seek`",`"timeMs`":$nearEndSeek}"
Send-CpCommand $port $seekJson | Out-Null
Start-Sleep -Milliseconds 700
Send-CpCommand $port '{"type":"play"}' | Out-Null
$eofDeadline = (Get-Date).AddSeconds(6)
$nearEnd = $false
while ((Get-Date) -lt $eofDeadline) {
    $state = Get-State $port
    if ((-not $state.isPlaying) -and $state.videoDisplayCurrentTimeMs -eq $state.videoLengthMs) {
        $nearEnd = $true
        break
    }
    Start-Sleep -Milliseconds 250
}
if (-not $nearEnd) {
    $state = Get-State $port
    throw "Playback did not reach display EOF. Current=$($state.videoCurrentTimeMs), display=$($state.videoDisplayCurrentTimeMs), length=$($state.videoLengthMs)"
}
$eofStateA = Get-State $port
$eofA = $eofStateA.videoCurrentTimeMs
$displayA = $eofStateA.videoDisplayCurrentTimeMs
Start-Sleep -Milliseconds 1200
$eofStateB = Get-State $port
$eofB = $eofStateB.videoCurrentTimeMs
$displayB = $eofStateB.videoDisplayCurrentTimeMs
if ([Math]::Abs($eofB - $eofA) -gt 150) {
    throw "EOF did not settle. Before=$eofA, after=$eofB"
}
if ($displayB -ne $videoLengthMs) {
    throw "EOF display did not settle at video length. Current=$eofB, display=$displayB, length=$videoLengthMs"
}
Write-Step "EOF settled ok: current $eofA -> $eofB, display $displayA -> $displayB"

Write-Step "Checking replay from EOF starts at beginning"
Click-At $clickX $clickY
$replayStart = Assert-SeekNear $port 0 700 "Preview click at EOF did not seek back to the beginning."
$replayAdvanced = Assert-Advanced $port $replayStart "Preview click at EOF did not restart playback from the beginning."
if ($replayAdvanced -gt 3500) {
    throw "Preview click at EOF did not restart near the beginning. Current=$replayAdvanced"
}
Send-CpCommand $port '{"type":"pause"}' | Out-Null
Write-Step "Replay from EOF ok: $replayStart -> $replayAdvanced"

if (-not $KeepRunning -and $startedProcess) {
    Write-Step "Stopping launched app process tree"
    try {
        Stop-Process -Id ([int]$session.pid) -Force -ErrorAction SilentlyContinue
    } catch {
    }
}

Write-Step "PASS"
