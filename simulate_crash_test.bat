@echo off
chcp 65001 > nul
echo 🚀 Simulating encode crash and launching app to verify restore dialog...
call gradlew.bat :composeApp:run --args="--simulate-crash"
