@echo off
setlocal EnableDelayedExpansion
title Run NJSQL Engine
cls

REM Set UTF-8 encoding for CMD
chcp 65001 > nul

echo Starting NJSQL...

set "CURRENT_DIR=%~dp0"

REM Check required jar files
if not exist "%CURRENT_DIR%libs\json-20230227.jar" (
    echo ERROR: json-20230227.jar not found in libs!
    pause
    exit /b
)
if not exist "%CURRENT_DIR%libs\jackson-core-2.18.3.jar" (
    echo ERROR: jackson-core-2.18.3.jar not found in libs!
    pause
    exit /b
)
if not exist "%CURRENT_DIR%libs\jackson-annotations-2.18.3.jar" (
    echo ERROR: jackson-annotations-2.18.3.jar not found in libs!
    pause
    exit /b
)
if not exist "%CURRENT_DIR%libs\jackson-databind-2.18.3.jar" (
    echo ERROR: jackson-databind-2.18.3.jar not found in libs!
    pause
    exit /b
)

REM Set classpath
set "CLASSPATH=%CURRENT_DIR%bin;%CURRENT_DIR%libs\json-20230227.jar;%CURRENT_DIR%libs\jackson-core-2.18.3.jar;%CURRENT_DIR%libs\jackson-annotations-2.18.3.jar;%CURRENT_DIR%libs\jackson-databind-2.18.3.jar"

REM Run NJSQL
java -cp "%CLASSPATH%" njsql.NJSQL

if errorlevel 1 (
    echo Failed to start NJSQL! Please check your setup.
    pause
    exit /b
)

pause
