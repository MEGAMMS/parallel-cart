@echo off
REM run-jmh-all.bat
REM Runs all JMH benchmarks and generates a summary report.

setlocal enabledelayedexpansion

echo [JMH] Compiling test classes...
mvn test-compile -q

if errorlevel 1 (
    echo [JMH] Compilation failed.
    exit /b 1
)

echo [JMH] Running all benchmarks...

REM Define benchmark classes
set BENCHMARKS=ProductServiceBenchmark CacheConfigBenchmark CheckoutBenchmark InventoryRetryBenchmark

for %%B in (%BENCHMARKS%) do (
    echo [JMH] Running %%B...
    mvn exec:java -Dexec.mainClass="com.parallelcart.benchmark.%%B" -q > jmh-report-%%B.txt 2>&1
    echo [JMH] Results saved to jmh-report-%%B.txt
)

echo [JMH] All benchmarks complete. Results:
dir /b jmh-report-*.txt
