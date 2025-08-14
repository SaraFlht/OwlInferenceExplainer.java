@echo off
echo === LLM-ORBench Sequential Processor ===

REM Set memory settings for large ontology processing
set JAVA_OPTS=-Xms4g -Xmx16g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+UseStringDeduplication

REM Set default directories if not provided
set ONTOLOGIES_DIR=%1
if "%ONTOLOGIES_DIR%"=="" set ONTOLOGIES_DIR=src\main\resources\OWL2DL-1_1hop

set OUTPUT_DIR=%2
if "%OUTPUT_DIR%"=="" set OUTPUT_DIR=.\output\LLM-ORBench

echo Java Options: %JAVA_OPTS%
echo Ontologies Directory: %ONTOLOGIES_DIR%
echo Output Directory: %OUTPUT_DIR%

REM Create output directory if it doesn't exist
if not exist "%OUTPUT_DIR%" mkdir "%OUTPUT_DIR%"

REM Check if JAR file exists
if not exist "target\LLM-ORBench-1.0-SNAPSHOT.jar" (
    echo JAR file not found. Building project...
    call mvn clean package -DskipTests
)

REM Run the application
echo Starting sequential processing...
java %JAVA_OPTS% -jar "target\LLM-ORBench-1.0-SNAPSHOT.jar" "%ONTOLOGIES_DIR%" "%OUTPUT_DIR%"

echo Processing completed. Check %OUTPUT_DIR% for results.
pause