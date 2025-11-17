@echo off
cd /d "%~dp0"

javac -cp "libs/*;src/main/java;bin" ^
    src/main/java/njsql/benchmark/NJSQLBench.java ^
    -d bin

java -cp "libs/*;bin" njsql.benchmark.NJSQLBench
pause
