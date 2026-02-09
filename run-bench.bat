@echo off
cd /d "%~dp0"
chcp 65001 > nul

javac -cp "libs/*;src/main/java;bin" ^
    src/main/java/njsql/benchmark/NJSQLBench.java ^
    -d bin

java -cp "libs/*;bin" njsql.benchmark.NJSQLBench
pause
