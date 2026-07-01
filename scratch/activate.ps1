$wshell = New-Object -ComObject WScript.Shell
$success = $wshell.AppActivate("FIT Telemetry Trimmer")
Write-Output "Activation result: $success"
