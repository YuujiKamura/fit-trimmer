param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [int]$TimeoutSeconds = 120,
    [switch]$ReuseExisting,
    [switch]$KeepRunning
)

$ErrorActionPreference = "Stop"

function Write-Step($message) {
    Write-Host "[smoke-early-finish] $message" -ForegroundColor Cyan
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


    $ffmpeg = Find-Ffmpeg $repoRoot
    Write-Step "Generating smoke video with $ffmpeg"
    $args = @(
        "-y",
        "-f", "lavfi",
        "-i", "testsrc2=size=640x360:rate=30",
        "-t", "45",
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

# 1. 準備
$videoPath = Ensure-TestVideo $RepoRoot
$fitPath = Join-Path $RepoRoot "temp_work\Lunch_Ride.fit"
if (-not (Test-Path $fitPath)) {
    throw "Required FIT file Lunch_Ride.fit not found at $fitPath"
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
    Write-Step "Closing existing active app session at pid $($session.pid)"
    try {
        Stop-Process -Id ([int]$session.pid) -Force -ErrorAction SilentlyContinue
        Start-Sleep -Seconds 1
    } catch {}
    $session = $null
}

if (-not $session) {
    Write-Step "Launching FitTrimmer GUI for Integration Test"
    $outLog = Join-Path $env:TEMP "fittrimmer-smoke-early-finish.out.log"
    $errLog = Join-Path $env:TEMP "fittrimmer-smoke-early-finish.err.log"
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
Write-Step "Control Plane ready on port $port, pid $($session.pid)"

# 2. ファイルのセット
$escapedVideo = $videoPath.Replace("\", "\\")
$escapedFit = $fitPath.Replace("\", "\\")
Write-Step "Setting files via Control Plane: Fit=$fitPath, Video=$videoPath"
$setFiles = "{`"type`":`"set_files`",`"fit`":`"$escapedFit`",`"video`":`"$escapedVideo`",`"startUtc`":`"2026-06-21T03:00:00Z`"}"
Send-CpCommand $port $setFiles | Out-Null

# 非同期ロードの完了を待機する（最大20秒）
Write-Step "Waiting for video and FIT file to load completely..."
$loadDeadline = (Get-Date).AddSeconds(20)
$loaded = $false
while ((Get-Date) -lt $loadDeadline) {
    $state = Send-CpCommand $port '{"type":"get_state"}' | ConvertFrom-Json
    if ($state.videoLengthMs -gt 0 -and $state.fitPath -ne "" -and $state.videoPath -ne "") {
        $loaded = $true
        break
    }
    Start-Sleep -Milliseconds 500
}
if (-not $loaded) {
    throw "Error: Video or FIT file load timeout."
}
Write-Step "File load complete. Video duration determined: $($state.videoLengthMs)ms"

# 2.5 プレート/路線名スキャンを無効化（即時にエンコードを開始するため）
Write-Step "Disabling plate and road name detection to start encoding immediately"
$state = Send-CpCommand $port '{"type":"get_state"}' | ConvertFrom-Json
$settings = $state.settings
$settings.blurLicensePlates = $false
$settings.enableRoadDetection = $false
$setLayout = @{
    type = "set_layout"
    settings = $settings
} | ConvertTo-Json -Compress
Send-CpCommand $port $setLayout | Out-Null
Start-Sleep -Seconds 1

# 3. 切り取り範囲のセット
Write-Step "Setting trim range: 0.0 - 45.0s"
$setTrim = "{`"type`":`"set_trim`",`"startSeconds`":0.0,`"endSeconds`":45.0}"
Send-CpCommand $port $setTrim | Out-Null
Start-Sleep -Seconds 1

# 4. エンコードの開始
Write-Step "Starting HUD encoding (CpCommand.Fire)"
Send-CpCommand $port '{"type":"fire"}' | Out-Null

# しばらくエンコードを走らせる
Write-Step "Letting encoding run for 1 second..."
Start-Sleep -Seconds 1

# 進捗状況を確認
$rawState = Send-CpCommand $port '{"type":"get_state"}'
Write-Host "DEBUG RAW STATE: $rawState"
$state = $rawState | ConvertFrom-Json
Write-Step "Current state: isEncoding=$($state.isEncoding), progress=$($state.progress)"

if (-not $state.isEncoding) {
    throw "Error: Encoding is not running."
}

# 5. 中間エクスポートをトリガー！！！
Write-Step "Sending early_finish command to trigger intermediate export"
Send-CpCommand $port '{"type":"early_finish"}' | Out-Null

Write-Step "Waiting for encoding process to clean up and export (5 seconds)..."
Start-Sleep -Seconds 5

# 6. 中間ファイルの存在検証
$expectedPartFile = Join-Path $RepoRoot "temp_work\smoke\playback_smoke_part.mp4"
Write-Step "Checking for intermediate part file: $expectedPartFile"

if (-not (Test-Path $expectedPartFile)) {
    throw "FAILED: Intermediate export file was not created: $expectedPartFile"
}

$fileSize = (Get-Item $expectedPartFile).Length
Write-Step "Intermediate export file size: $fileSize bytes"
if ($fileSize -le 1024) {
    throw "FAILED: Intermediate export file size is too small ($fileSize bytes)."
}

Write-Step "SUCCESS: Intermediate export file successfully verified!"

# 7. クリーンアップ
if (-not $KeepRunning -and $startedProcess) {
    Write-Step "Stopping launched app process tree"
    try {
        Stop-Process -Id ([int]$session.pid) -Force -ErrorAction SilentlyContinue
    } catch {}
}

Write-Step "INTEGRATION TEST PASS"
