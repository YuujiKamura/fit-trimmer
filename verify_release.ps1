# verify_release.ps1
$ErrorActionPreference = "Stop"

# Explicitly use JDK 21 which contains jpackage.exe (fixes Android Studio JBR lacking jpackage.exe)
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"

# Use local temp directory
$extractDir = Join-Path $env:TEMP "FitTrimmer_E2E_Extract"
$tempDir = Join-Path $env:TEMP "FitTrimmer_E2E_Temp"

Write-Host "=== 1. Building MSI Package ===" -ForegroundColor Cyan
# Stop any active Gradle Daemons to clear cached JVM properties, and run without daemon
./gradlew --stop
./gradlew :composeApp:packageMsi --no-daemon

# Find generated MSI file
$msiPath = Get-ChildItem -Path "composeApp/build/compose/binaries/main/msi/" -Filter "*.msi" | Select-Object -First 1
if (-not $msiPath) {
    Write-Error "MSI file not found."
}
Write-Host "MSI Found: $($msiPath.FullName)" -ForegroundColor Green

Write-Host "=== 2. Extracting MSI ===" -ForegroundColor Cyan
if (Test-Path $extractDir) {
    Remove-Item -Path $extractDir -Recurse -Force
}
New-Item -Path $extractDir -ItemType Directory | Out-Null

# Administrative extract (does not require admin privileges or install anything to system)
# Pass arguments as a clean array to avoid complex escape parsing
$msiArgs = @("/a", "`"$($msiPath.FullName)`"", "/qb", "TARGETDIR=`"$extractDir`"")
Start-Process -FilePath "msiexec.exe" -ArgumentList $msiArgs -Wait -NoNewWindow

# Find extracted executable
$exePath = Get-ChildItem -Path $extractDir -Filter "FitTrimmer.exe" -Recurse | Select-Object -First 1
if (-not $exePath) {
    Write-Error "Extracted FitTrimmer.exe not found."
}
Write-Host "Extraction complete. Executable: $($exePath.FullName)" -ForegroundColor Green

Write-Host "=== 3. Creating Dummy Video and FIT Verification ===" -ForegroundColor Cyan
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
Start-Process -FilePath $ffmpegPath -ArgumentList "-y -f lavfi -i testsrc=duration=5:size=640x360:rate=30 -c:v mpeg4 -t 5 `"$dummyVideoPath`"" -Wait -NoNewWindow

Write-Host "Dummy video created: $dummyVideoPath" -ForegroundColor Green

# Use one of the real FIT files in the repository for validation
$realFitPath = "C:\Users\yuuji\fit-trimmer\temp_work\fit\activity_11515795411.fit"
if (-not (Test-Path $realFitPath)) {
    $found = Get-ChildItem -Path "C:\Users\yuuji\fit-trimmer" -Filter "*.fit" -Recurse | Select-Object -First 1
    if ($found) {
        $realFitPath = $found.FullName
    }
}
if (-not (Test-Path $realFitPath)) {
    Write-Error "FIT file for testing not found. Please place a *.fit file in the repository."
}
Write-Host "Using test FIT: $realFitPath" -ForegroundColor Green

Write-Host "=== 4. Executing E2E Tests ===" -ForegroundColor Cyan
# Run the extracted production binary in E2E test mode
$e2eArgs = @("--test-e2e", "--fit", "`"$realFitPath`"", "--video", "`"$dummyVideoPath`"", "--output", "`"$testOutputVideoPath`"")
$process = Start-Process -FilePath $exePath.FullName -ArgumentList $e2eArgs -PassThru -Wait -NoNewWindow

# Clean up extracted temp directory after run to save disk space
try {
    Remove-Item -Path $extractDir -Recurse -Force
    Remove-Item -Path $tempDir -Recurse -Force
} catch {
    # Ignore cleanup errors
}

# Check exit code
if ($process.ExitCode -ne 0) {
    Write-Error "E2E Test Failed (Exit Code: $($process.ExitCode))"
}

Write-Host "=== ALL E2E TESTS PASSED SUCCESSFULLY! ===" -ForegroundColor Green
