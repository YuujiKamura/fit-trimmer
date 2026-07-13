$filePath = "C:\Users\yuuji\fit-trimmer\composeApp\src\desktopMain\kotlin\FitTrimmerMainContent.kt"
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$content = [System.IO.File]::ReadAllText($filePath, $utf8NoBom)

# Helper function for exact string replacement using [regex]::Escape
function Replace-String($orig, $target, $replacement) {
    $escapedTarget = [regex]::Escape($target)
    return $orig -replace $escapedTarget, $replacement
}

# 1. ControlPlaneStatus の追加
if (-not $content.Contains("object ControlPlaneStatus")) {
    $target = "import kotlinx.coroutines.Dispatchers"
    $replacement = "object ControlPlaneStatus {`r`n    @Volatile var isEncoding: Boolean = false`r`n}`r`n`r`nimport kotlinx.coroutines.Dispatchers"
    $content = Replace-String $content $target $replacement
}

# 2. CpCommand.EarlyFinish の追加
if (-not $content.Contains("is CpCommand.EarlyFinish ->")) {
    $target = "is CpCommand.SetLayout -> settings = cmd.settings"
    $replacement = "is CpCommand.EarlyFinish -> {`r`n                                    viewModel.isEarlyFinish = true`r`n                                    viewModel.batchStatusText = if (settings.language == `"ja`") `"\u4e2d\u9593\u30a8\u30af\u30b9\u30dd\u30fc\u30c8\u4e2d...`" else `"Exporting progress...`"`r`n                                }`r`n                                is CpCommand.SetLayout -> settings = cmd.settings"
    $content = Replace-String $content $target $replacement
}

# 3. isEncoding = true の置換
$target = "isEncoding = true"
$replacement = "isEncoding = true`r`n                                        ControlPlaneStatus.isEncoding = true"
$content = Replace-String $content $target $replacement

# 4. finally ブロックの isEncoding = false の置換
$target1 = "isEncoding = false`r`n                                            viewModel.isEarlyFinish"
$replacement1 = "isEncoding = false`r`n                                            ControlPlaneStatus.isEncoding = false`r`n                                            viewModel.isEarlyFinish"
$content = Replace-String $content $target1 $replacement1

$target2 = "isEncoding = false`n                                            viewModel.isEarlyFinish"
$replacement2 = "isEncoding = false`n                                            ControlPlaneStatus.isEncoding = false`n                                            viewModel.isEarlyFinish"
$content = Replace-String $content $target2 $replacement2

# 5. UpdateProgress の isEncoding = cmd.isEncoding 置換
$target = "isEncoding = cmd.isEncoding"
$replacement = "isEncoding = cmd.isEncoding`r`n                                     ControlPlaneStatus.isEncoding = cmd.isEncoding"
$content = Replace-String $content $target $replacement

# 6. getState 内の isEncoding = isEncoding, 置換
$target = "isEncoding = isEncoding,"
$replacement = "isEncoding = ControlPlaneStatus.isEncoding,"
$content = Replace-String $content $target $replacement

[System.IO.File]::WriteAllText($filePath, $content, $utf8NoBom)
Write-Host "SUCCESS: Main.kt patched successfully for ControlPlaneStatus via regex helper."
