@echo off
rem Builds arena.jar without Maven: compiles src\main\java into out\ and packs the jar.
setlocal
cd /d "%~dp0"
call "%~dp0find-jdk.cmd" || exit /b 1
if exist out rmdir /s /q out
"%JDK%\bin\javac.exe" -encoding UTF-8 --release 21 -d out -sourcepath src\main\java src\main\java\arena\Main.java
if errorlevel 1 (
    echo Build FAILED
    exit /b 1
)
"%JDK%\bin\jar.exe" --create --file arena.jar --main-class arena.Main -C out .
if errorlevel 1 (
    echo Packaging FAILED
    exit /b 1
)
echo Build OK: arena.jar
