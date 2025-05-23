@echo off
setlocal EnableDelayedExpansion
title Run NJSQL Engine
cls

REM Đặt encoding UTF-8 cho CMD
chcp 65001 > nul

echo Compiling NJSQL source code...

REM Tạo thư mục bin nếu chưa có
if not exist bin (
    mkdir bin
)

REM Xóa toàn bộ file .class cũ trong bin
echo Cleaning old compiled classes...
for /R bin %%f in (*.class) do (
    del "%%f"
)

REM Thu thập tất cả file .java từ src
echo Collecting source files...
set SOURCES=
for /R src %%f in (*.java) do (
    set "SOURCES=!SOURCES! "%%f""
)

REM Khai báo classpath
set CLASSPATH=.;libs\json-20230227.jar;libs\jackson-core-2.18.3.jar;libs\jackson-annotations-2.18.3.jar;libs\jackson-databind-2.18.3.jar

REM Compile các file Java
echo Compiling Java sources...
javac -cp "!CLASSPATH!" -d bin !SOURCES!

if errorlevel 1 (
    echo Compile failed! Please check your code.
    pause
    exit /b
)

echo.
echo Starting NJSQL...

REM Chạy chương trình
java -cp "bin;!CLASSPATH!" njsql.NJSQL

pause
