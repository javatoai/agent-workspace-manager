@echo off
setlocal

set "AWM_CLI_HOME=%~dp0.."
set "AWM_JAVA=%AWM_CLI_HOME%\..\runtime\bin\java.exe"

rem Installed CLI: <version>/cli/bin. Green package: app/resources/cli/bin.
if not exist "%AWM_JAVA%" set "AWM_JAVA=%AWM_CLI_HOME%\..\cli-runtime\bin\java.exe"

if not exist "%AWM_JAVA%" if defined JAVA_HOME set "AWM_JAVA=%JAVA_HOME%\bin\java.exe"
if not exist "%AWM_JAVA%" set "AWM_JAVA=java"

"%AWM_JAVA%" -cp "%AWM_CLI_HOME%\lib\*" com.snowball.awm.cli.MainKt %*
set "AWM_EXIT_CODE=%ERRORLEVEL%"
endlocal & exit /b %AWM_EXIT_CODE%
