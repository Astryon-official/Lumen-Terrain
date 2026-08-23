@echo off
rem LTE 2.1 stress test runner.
rem Usage: tools\stresstest\run_stress.bat [path\to\mod.jar]
setlocal
set JAR=%~1
if "%JAR%"=="" set JAR=build\libs\lumen-terrain-engine-2.1.0.jar

if not exist "%JAR%" (
    echo JAR not found: %JAR%
    exit /b 2
)

rem Resolve to an absolute path BEFORE changing directories.
for %%i in ("%JAR%") do set JAR=%%~fi

set WORK=%TEMP%\lte-stress-%RANDOM%
mkdir "%WORK%" || exit /b 2
pushd "%WORK%"

echo Extracting classes from %JAR% ...
jar xf "%JAR%" com native fabric.mod.json || goto :fail

echo Compiling stress test ...
javac -cp . -d . "%~dp0LTEStressTest.java" || goto :fail

echo Running stress test ...
java --enable-native-access=ALL-UNNAMED -Xmx1g -cp . LTEStressTest
set RC=%ERRORLEVEL%

popd
rd /s /q "%WORK%"
exit /b %RC%

:fail
popd
rd /s /q "%WORK%"
exit /b 1
