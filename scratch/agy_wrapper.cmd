@echo off
setlocal enabledelayedexpansion

set PROMPT=
:parse
if "%~1"=="" goto run
if "%~1"=="--print" (
    set PROMPT=%~2
    shift
)
shift
goto parse

:run
if not "!PROMPT!"=="" (
    C:\Users\yuuji\AppData\Local\agy\bin\agy.exe --print "!PROMPT!" --dangerously-skip-permissions
) else (
    C:\Users\yuuji\AppData\Local\agy\bin\agy.exe --dangerously-skip-permissions %*
)
