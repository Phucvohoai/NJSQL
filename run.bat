@echo off
setlocal EnableDelayedExpansion
title Run NJSQL Engine
cls

chcp 65001 > nul

echo Compiling NJSQL source code...

if not exist bin mkdir bin

echo Cleaning old compiled classes...
for /R bin %%f in (*.class) do del "%%f"

echo Collecting source files...
set SOURCES=
for /R src %%f in (*.java) do set "SOURCES=!SOURCES! "%%f""

set CLASSPATH=.;libs\json-20230227.jar;libs\jackson-core-2.18.3.jar;libs\jackson-annotations-2.18.3.jar;libs\jackson-databind-2.18.3.jar

echo Compiling Java sources...
javac -cp "!CLASSPATH!" -d bin !SOURCES!

if errorlevel 1 (
    echo Compile failed!
    pause
    exit /b
)

echo Starting NJSQL...
java -cp "bin;!CLASSPATH!" njsql.NJSQL
pause
