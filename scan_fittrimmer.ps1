$projectRoot = "C:\Users\yuuji\fit-trimmer"

Write-Host "=== SCANNING FIT-TRIMMER FOR LARGE FILES/DIRS ==="
Write-Host ""

# Top level directory sizes
Write-Host "--- TOP LEVEL SIZES ---"
$topDirs = Get-ChildItem $projectRoot -Directory
foreach ($d in $topDirs) {
    $files = Get-ChildItem $d.FullName -Recurse -File -ErrorAction SilentlyContinue
    $size = 0
    if ($files) {
        $size = ($files | Measure-Object -Property Length -Sum).Sum
    }
    $sizeGB = $size / 1GB
    if ($sizeGB -gt 0.01) {
        Write-Host ("{0,-30} {1,10:N3} GB" -f $d.Name, $sizeGB)
    }
}

# Top level files
$topFiles = Get-ChildItem $projectRoot -File
foreach ($f in $topFiles) {
    $sizeGB = $f.Length / 1GB
    if ($sizeGB -gt 0.005) {
        Write-Host ("{0,-30} {1,10:N3} GB" -f $f.Name, $sizeGB)
    }
}

Write-Host ""
Write-Host "--- LARGEST FILES (Top 25) ---"
Get-ChildItem $projectRoot -Recurse -File -ErrorAction SilentlyContinue |
    Sort-Object Length -Descending |
    Select-Object -First 25 |
    ForEach-Object {
        $relPath = $_.FullName.Substring($projectRoot.Length + 1)
        Write-Host ("{0,-60} {1,10:N3} MB" -f $relPath, ($_.Length / 1MB))
    }
