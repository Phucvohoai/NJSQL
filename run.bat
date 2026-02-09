@echo off
setlocal EnableDelayedExpansion
title Run NJSQL Engine
cls

chcp 65001 > nul

echo Checking Maven and dependencies...
where mvn >nul 2>nul
if %errorlevel% neq 0 (
    echo ERROR: Maven not found! Install from https://maven.apache.org/
    pause
    exit /b 1
)

REM Auto download dependencies to libs if needed
if not exist libs mkdir libs
set JAR_COUNT=0
for %%f in (libs\*.jar) do set /a JAR_COUNT+=1

if !JAR_COUNT! LSS 5 (
    echo Downloading dependencies via Maven...
    call mvn dependency:copy-dependencies -DoutputDirectory=libs -DincludeScope=runtime -q
    echo Dependencies downloaded to libs folder.
)

echo Compiling NJSQL source code...
if not exist bin mkdir bin

echo Cleaning old compiled classes...
if exist bin\njsql rmdir /s /q bin\njsql 2>nul
if exist target\classes rmdir /s /q target\classes 2>nul

echo Generating Protobuf and compiling with Maven...
call mvn clean compile -q
if errorlevel 1 (
    echo Compile failed!
    pause
    exit /b
)

echo Copying compiled classes to bin...
xcopy /E /I /Y /Q target\classes\* bin\ >nul 2>&1

echo Building classpath from libs...
set CLASSPATH=bin
for %%f in (libs\*.jar) do (
    set "CLASSPATH=!CLASSPATH!;%%f"
)

echo ========================================
echo   BUILD SUCCESSFUL
echo ========================================
echo.
echo To use the database:
echo 1. Run: NJSQL.bat
echo 2. Start executing SQL commands
echo.
echo ========================================
pause
