@echo off
REM profile-with-jfr.bat
REM Records a Java Flight Recorder (JFR) snapshot on Windows.
REM Usage: profile-with-jfr.bat <java-process-id> [duration-seconds]

setlocal enabledelayedexpansion

set PID=%1
set DURATION=%2
if "%DURATION%"=="" set DURATION=60

set TIMESTAMP=%date:~-4,4%%date:~-10,2%%date:~-7,2%-%time:~0,2%%time:~3,2%%time:~6,2%
set TIMESTAMP=%TIMESTAMP: =0%
set OUTPUT_FILE=parallel-cart-%TIMESTAMP%.jfr

echo [JFR Profiling] PID: %PID% | Duration: %DURATION%s | Output: %OUTPUT_FILE%

REM Start JFR recording
jcmd %PID% JFR.start name=parallel-cart-profile settings=profile duration=%DURATION%s filename=%OUTPUT_FILE%

echo [JFR Profiling] Recording for %DURATION% seconds...
timeout /t %DURATION% /nobreak >nul

REM Dump the recording
jcmd %PID% JFR.dump name=parallel-cart-profile filename=%OUTPUT_FILE%

echo [JFR Profiling] Recording saved to: %OUTPUT_FILE%
echo [JFR Profiling] Open with JDK Mission Control (jmc) to analyze CPU, allocations, and locks.
