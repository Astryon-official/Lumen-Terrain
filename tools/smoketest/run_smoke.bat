@echo off
rem LTE standalone smoke test runner.
rem Usage: tools\smoketest\run_smoke.bat [path\to\mod.jar]
setlocal
set JAR=%~1
if "%JAR%"=="" set JAR=build\libs\lumen-terrain-engine-2.1.0.jar

if not exist "%JAR%" (
    echo JAR not found: %JAR%
    exit /b 2
)

rem Resolve to an absolute path BEFORE changing directories.
for %%i in ("%JAR%") do set JAR=%%~fi

set WORK=%TEMP%\lte-smoke-%RANDOM%
mkdir "%WORK%" || exit /b 2
pushd "%WORK%"

echo Extracting classes from %JAR% ...
jar xf "%JAR%" com native fabric.mod.json || goto :fail

echo Compiling smoke test ...
javac -cp . -d . "%~dp0LTESmokeTest.java" || goto :fail

echo Running smoke test ...
java -cp . LTESmokeTest
set RC=%ERRORLEVEL%

popd
rd /s /q "%WORK%"
exit /b %RC%

:fail
popd
rd /s /q "%WORK%"
exit /b 1
