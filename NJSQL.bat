@echo off
setlocal EnableDelayedExpansion
title Run NJSQL Engine
cls

REM Đặt encoding UTF-8 cho CMD
chcp 65001 > nul

echo Starting NJSQL...

REM Xác định thư mục hiện tại
set "CURRENT_DIR=%~dp0"

REM Kiểm tra các file jar trong thư mục libs
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

REM Khai báo classpath với thư viện trong libs + class trong bin
set "CLASSPATH=%CURRENT_DIR%bin;%CURRENT_DIR%libs\json-20230227.jar;%CURRENT_DIR%libs\jackson-core-2.18.3.jar;%CURRENT_DIR%libs\jackson-annotations-2.18.3.jar;%CURRENT_DIR%libs\jackson-databind-2.18.3.jar"

REM Chạy chương trình từ thư mục bin
java -cp "%CLASSPATH%" njsql.NJSQL

if errorlevel 1 (
    echo Failed to start NJSQL! Please check your setup.
    pause
    exit /b
)

pause
