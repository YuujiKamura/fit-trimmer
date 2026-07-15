@echo off
chcp 65001
set FIT_FILE="F:\Insta360\20260712\Afternoon_Ride.fit"
set VIDEO_FILE="F:\Insta360\20260712\VID_20260712_163908_005.mp4"
echo 🚀 Launching FitTrimmer TEST MODE (5 Seconds)...
call gradlew.bat :shared-core:runFitCLI --args="%FIT_FILE% %VIDEO_FILE% --encode --test"
pause
