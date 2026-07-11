@echo off
chcp 65001
set FIT_FILE="H:\マイドライブ\20260621\Lunch_Ride.fit"
set VIDEO_FILE="H:\マイドライブ\20260621\VID_20260621_110949_008.mp4"
echo 🚀 Launching Targeted FitTrimmer CLI...
call gradlew.bat :shared-core:runFitCLI --args="%FIT_FILE% %VIDEO_FILE% --encode"
pause
