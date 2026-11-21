@echo off
rem Dedicated server without a window: server.cmd [port] [bots]
setlocal
cd /d "%~dp0"
call "%~dp0build.cmd" || exit /b 1
call "%~dp0find-jdk.cmd" || exit /b 1
"%JDK%\bin\java.exe" -jar arena.jar --server %*
