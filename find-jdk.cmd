@echo off
rem Sets JDK to a folder with bin\javac.exe: JAVA_HOME, IntelliJ's %USERPROFILE%\.jdks or PATH.
set "JDK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JDK=%JAVA_HOME%"
if not defined JDK for /d %%D in ("%USERPROFILE%\.jdks\*") do if exist "%%D\bin\javac.exe" set "JDK=%%D"
if not defined JDK for %%J in (javac.exe) do if not "%%~$PATH:J"=="" for %%P in ("%%~dp$PATH:J..") do set "JDK=%%~fP"
if defined JDK exit /b 0
echo JDK not found. Install JDK 21 or newer, or set JAVA_HOME.
exit /b 1
