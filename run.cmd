@echo off
rem Builds and starts the game. Run it twice to test multiplayer on one PC.
setlocal
cd /d "%~dp0"
call "%~dp0build.cmd" || exit /b 1
call "%~dp0find-jdk.cmd" || exit /b 1
"%JDK%\bin\java.exe" -jar arena.jar %*
