@echo off
rem Stops the local test server, builds and tests Logic Blocks, installs the plugin on the server and the client
rem mod in Minecraft's mods folder, then offers to start the server. Options: see run\DevInstall.java
rem (for example: build-and-install.cmd --start). Settings: LOGIC_SERVER_DIR, LOGIC_MODS_DIR, JAVA_HOME.
setlocal
cd /d "%~dp0.."
set "JAVA_EXE=java"
if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    for /d %%D in ("%~dp0..\..\blueprints-mc\.tools\jdk\*") do if exist "%%D\bin\java.exe" set "JAVA_EXE=%%D\bin\java.exe"
)
"%JAVA_EXE%" "%~dp0DevInstall.java" %*
pause
