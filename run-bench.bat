@echo off
javac -cp "libs/*;src" src/njsql/benchmark/NJSQLBench.java -d bin
java -cp "libs/*;bin" njsql.benchmark.NJSQLBench
pause
