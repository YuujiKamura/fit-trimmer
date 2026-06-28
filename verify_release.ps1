# verify_release.ps1
$ErrorActionPreference = "Stop"

# Use local temp directory
$extractDir = Join-Path $env:TEMP "FitTrimmer_E2E_Extract"
$tempDir = Join-Path $env:TEMP "FitTrimmer_E2E_Temp"

Write-Host "=== 1. MSI パッケージのビルド ===" -ForegroundColor Cyan
./gradlew :composeApp:packageMsi

# Find generated MSI file
$msiPath = Get-ChildItem -Path "composeApp/build/compose/binaries/main/msi/" -Filter "*.msi" | Select-Object -First 1
if (-not $msiPath) {
    Write-Error "MSI ファイルが見つかりません。"
}
Write-Host "MSI検出: $($msiPath.FullName)" -ForegroundColor Green

Write-Host "=== 2. MSI の一時展開 ===" -ForegroundColor Cyan
if (Test-Path $extractDir) {
    Remove-Item -Path $extractDir -Recurse -Force
}
New-Item -Path $extractDir -ItemType Directory | Out-Null

# Administrative extract (does not require admin privileges or install anything to system)
Start-Process -FilePath "msiexec.exe" -ArgumentList "/a `"$($msiPath.FullName)`" /qb TARGETDIR=`"$extractDir`"" -Wait -NoNewWindow

# Find extracted executable
$exePath = Get-ChildItem -Path $extractDir -Filter "FitTrimmer.exe" -Recurse | Select-Object -First 1
if (-not $exePath) {
    Write-Error "展開された FitTrimmer.exe が見つかりません。"
}
Write-Host "展開完了。実行ファイル: $($exePath.FullName)" -ForegroundColor Green

Write-Host "=== 3. ダミー動画とテスト用FITファイルの確認 ===" -ForegroundColor Cyan
if (-not (Test-Path $tempDir)) {
    New-Item -Path $tempDir -ItemType Directory | Out-Null
}

$dummyVideoPath = Join-Path $tempDir "e2e_test_source.mp4"
$testOutputVideoPath = Join-Path $tempDir "e2e_test_output.mp4"

# Use bundled ffmpeg if available
$ffmpegPath = "C:\Users\yuuji\fit-trimmer\temp_work\bin\ffmpeg.exe"
if (-not (Test-Path $ffmpegPath)) {
    $ffmpegPath = "ffmpeg"
}
Start-Process -FilePath $ffmpegPath -ArgumentList "-y -f lavfi -i testsrc=duration=5:size=640x360:rate=30 -c:v libx264 -t 5 `"$dummyVideoPath`"" -Wait -NoNewWindow

Write-Host "ダミー動画作成完了: $dummyVideoPath" -ForegroundColor Green

# Use one of the real FIT files in the repository for validation
$realFitPath = "C:\Users\yuuji\fit-trimmer\temp_work\fit\activity_11515795411.fit"
if (-not (Test-Path $realFitPath)) {
    $realFitPath = (Get-ChildItem -Path "C:\Users\yuuji\fit-trimmer" -Filter "*.fit" -Recurse | Select-Object -First 1).FullName
}
if (-not $realFitPath) {
    Write-Error "テスト用の FIT ファイルが見つかりません。リポジトリ内に *.fit ファイルを配置してください。"
}
Write-Host "使用するテスト用FIT: $realFitPath" -ForegroundColor Green

Write-Host "=== 4. E2E テストの実行 ===" -ForegroundColor Cyan
# Run the extracted production binary in E2E test mode
$process = Start-Process -FilePath $exePath.FullName -ArgumentList "--test-e2e --fit `"$realFitPath`" --video `"$dummyVideoPath`" --output `"$testOutputVideoPath`"" -PassThru -Wait -NoNewWindow

# Clean up extracted temp directory after run to save disk space
try {
    Remove-Item -Path $extractDir -Recurse -Force
    Remove-Item -Path $tempDir -Recurse -Force
} catch {
    # Ignore cleanup errors
}

# Check exit code
if ($process.ExitCode -ne 0) {
    Write-Error "E2Eテストが失敗しました (終了コード: $($process.ExitCode))"
}

Write-Host "=== 🎉 ALL E2E TESTS PASSED SUCCESSFULLY! ===" -ForegroundColor Green
